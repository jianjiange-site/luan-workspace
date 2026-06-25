package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostStat;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Recommendation feed service implementing three-pool mixing.
 * <p>
 * Three pools:
 * ① Recommend pool (Hacker News score, top 3000/gender, rebuilt every 5 min)
 * ② Friend timeline (RocketMQ write-fanout, per-user ZSet, max 100)
 * ③ Cold-start pool (sync on post create, time-ordered, new-post exposure)
 * <p>
 * 10-position allocation per page:
 *   positions 1,2,4,5,7,8,9,10 → recommend (degrade cold_start → friend)
 *   position 3 → friend (degrade recommend)
 *   position 6 → cold_start (degrade recommend)
 * <p>
 * Same-friend rate limit: ≤1 post per friend per page.
 * Bloom filter dedup: RBloomFilter per user, 5000 capacity, 1% FPP, 7d TTL.
 */
@Slf4j
@Service
public class FeedService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int RECOMMEND_FETCH = 30;
    private static final int FRIEND_FETCH = 5;
    private static final int COLD_START_FETCH = 10;
    private static final int RECOMMEND_POOL_SIZE = 3000;
    private static final Duration BLOOM_TTL = Duration.ofDays(7);
    private static final int RECENT_DAYS = 3;

    // Hacker News scoring constants
    private static final double W_BASE = 10.0;
    private static final double ALPHA = 1.0;
    private static final double BETA = 3.0;
    private static final double GRAVITY = 1.5;

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostReadService postReadService;
    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final RedissonClient redissonClient;
    private final CacheKeyBuilder keyBuilder;

    @Value("${app.feed.bloom-capacity:5000}")
    private int bloomCapacity;

    @Value("${app.feed.bloom-fpp:0.01}")
    private double bloomFpp;

    public FeedService(PostManager postManager,
                       PostStatManager postStatManager,
                       PostReadService postReadService,
                       UserClient userClient,
                       StringRedisTemplate redis,
                       RedissonClient redissonClient,
                       CacheKeyBuilder keyBuilder) {
        this.postManager = postManager;
        this.postStatManager = postStatManager;
        this.postReadService = postReadService;
        this.userClient = userClient;
        this.redis = redis;
        this.redissonClient = redissonClient;
        this.keyBuilder = keyBuilder;
    }

    /**
     * Get recommended feed page for a user.
     *
     * @param userId   current user
     * @param pageSize items per page (default 10, max 50)
     * @param cursor   "recOffset:csOffset" format, "0:0" first page
     * @return feed items + next_cursor + has_more
     */
    public FeedResult getRecommendFeed(long userId, int pageSize, String cursor) {
        if (pageSize <= 0 || pageSize > 50) {
            pageSize = DEFAULT_PAGE_SIZE;
        }

        // Parse cursor
        long recOffset = 0;
        long csOffset = 0;
        if (cursor != null && !cursor.isEmpty()) {
            String[] parts = cursor.split(":");
            if (parts.length == 2) {
                recOffset = Long.parseLong(parts[0]);
                csOffset = Long.parseLong(parts[1]);
            }
        }

        // Get user gender → opposite sex pools
        boolean isMale = userClient.isMale(userId);
        String recommendKey = isMale ? keyBuilder.feedPoolRecommendFemale() : keyBuilder.feedPoolRecommendMale();
        String coldStartKey = isMale ? keyBuilder.feedColdStartFemale() : keyBuilder.feedColdStartMale();
        String timelineKey = keyBuilder.userTimeline(userId);

        // Get Bloom filter for read dedup
        RBloomFilter<String> bloom = getBloomFilter(userId);

        // Fetch three paths in parallel (logically — actual parallelism via thread pool)
        // ① Recommend pool: ZREVRANGE by position range
        Set<String> recommendIds = redis.opsForZSet()
                .reverseRange(recommendKey, recOffset, recOffset + RECOMMEND_FETCH - 1);
        List<Long> recommendList = toLongList(recommendIds);

        // ② Friend timeline: ZREVRANGEBYSCORE recent 7 days, newest 5
        long sevenDaysAgo = Instant.now().minusSeconds(7 * 24 * 3600).getEpochSecond();
        Set<String> friendIds = redis.opsForZSet()
                .reverseRangeByScore(timelineKey, sevenDaysAgo, Double.POSITIVE_INFINITY, 0, FRIEND_FETCH);
        List<Long> friendList = toLongList(friendIds);

        // ③ Cold-start pool: ZREVRANGE by position
        Set<String> coldStartIds = redis.opsForZSet()
                .reverseRange(coldStartKey, csOffset, csOffset + COLD_START_FETCH - 1);
        List<Long> coldStartList = toLongList(coldStartIds);

        // Bloom filter dedup all three paths
        recommendList = filterByBloom(bloom, recommendList);
        friendList = filterByBloom(bloom, friendList);
        coldStartList = filterByBloom(bloom, coldStartList);

        // Merge three ways with position allocation
        List<PostReadService.PostDetail> items = mergeThreeWay(
                recommendList, friendList, coldStartList, pageSize, bloom);

        // Build next cursor
        long nextRecOffset = recOffset + (recommendList.size() >= pageSize ? pageSize : recommendList.size());
        long nextCsOffset = csOffset + 1; // advance cold-start by 1 per page
        String nextCursor = nextRecOffset + ":" + nextCsOffset;
        boolean hasMore = items.size() == pageSize;

        // Collect unique post userIds for rendering (optional: caller can enrich with user info)
        int friendCount = (int) items.stream()
                .filter(p -> friendList.contains(p.postId())).count();
        int coldCount = (int) items.stream()
                .filter(p -> coldStartList.contains(p.postId())).count();
        log.info("Feed returned: userId={} size={} recommend={} friends={} coldStart={}",
                userId, items.size(), items.size() - friendCount - coldCount, friendCount, coldCount);

        return new FeedResult(items, nextCursor, hasMore);
    }

    /**
     * Rebuild the recommend pool (gender-split, Hacker News scored, top 3000).
     * Called by FeedScoreJob every 5 minutes. ShedLock handles multi-instance mutex.
     * <p>
     * Steps:
     * 1. SELECT posts from last 3 days (single-table)
     * 2. selectBatchIds post_stats (single-table, no JOIN)
     * 3. Redis incr compensation for real-time counts
     * 4. Hacker News scoring in memory
     * 5. Gender bucketing via UserClient.getGenders (Caffeine cached)
     * 6. Shadow write to tmp ZSet → trim → EXPIRE → atomic RENAME
     */
    public void rebuildRecommendPool() {
        long startTime = System.currentTimeMillis();
        log.info("Feed pool rebuild started");

        // Step 1: Fetch recent posts (single-table, no JOIN)
        List<Post> posts = postManager.findRecentNormalPosts(RECENT_DAYS);
        if (posts.isEmpty()) {
            log.info("Feed pool rebuild: no posts in last {} days, clearing pools", RECENT_DAYS);
            clearPools();
            return;
        }

        List<Long> postIds = posts.stream().map(Post::getPostId).toList();

        // Step 2: Batch get stats (single-table, no JOIN)
        List<PostStat> stats = postStatManager.findByPostIds(postIds);
        Map<Long, PostStat> statMap = stats.stream()
                .collect(Collectors.toMap(PostStat::getPostId, s -> s));

        // Step 3: Compute scores with Redis delta compensation
        // Step 4: Hacker News scoring in memory
        long nowEpoch = Instant.now().getEpochSecond();
        Map<Long, Double> scores = new HashMap<>();
        for (Post post : posts) {
            PostStat stat = statMap.get(post.getPostId());
            int likeBase = stat != null ? stat.getLikeCount() : 0;
            int commentBase = stat != null ? stat.getCommentCount() : 0;

            // Redis delta compensation
            int likeDelta = getRedisDelta(keyBuilder.statIncrLikes(post.getPostId()));
            int commentDelta = getRedisDelta(keyBuilder.statIncrComments(post.getPostId()));

            double likes = likeBase + likeDelta;
            double comments = commentBase + commentDelta;

            long createdAtEpoch = post.getCreatedAt() != null
                    ? post.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC)
                    : nowEpoch;
            double hoursDiff = (nowEpoch - createdAtEpoch) / 3600.0;

            // Hacker News variant: (W_base + α*likes + β*comments) / (hours + 2)^1.5
            double score = (W_BASE + ALPHA * likes + BETA * comments)
                    / Math.pow(hoursDiff + 2, GRAVITY);
            scores.put(post.getPostId(), score);
        }

        // Step 5: Gender bucketing
        List<Long> distinctUserIds = posts.stream().map(Post::getUserId).distinct().toList();
        Map<Long, Boolean> genderMap = userClient.getGenders(distinctUserIds);

        Map<Long, Double> maleScores = new HashMap<>();
        Map<Long, Double> femaleScores = new HashMap<>();

        for (Post post : posts) {
            Boolean isMale = genderMap.getOrDefault(post.getUserId(), false);
            if (isMale) {
                maleScores.put(post.getPostId(), scores.get(post.getPostId()));
            } else {
                femaleScores.put(post.getPostId(), scores.get(post.getPostId()));
            }
        }

        // Step 6: Shadow write to tmp ZSet → trim to top 3000 → RENAME
        rebuildPoolFixed(keyBuilder.feedPoolRecommendMaleTmp(), keyBuilder.feedPoolRecommendMale(), maleScores);
        rebuildPoolFixed(keyBuilder.feedPoolRecommendFemaleTmp(), keyBuilder.feedPoolRecommendFemale(), femaleScores);

        long duration = System.currentTimeMillis() - startTime;
        log.info("Feed pool rebuilt: candidates={} male={} female={} duration={}ms",
                posts.size(), maleScores.size(), femaleScores.size(), duration);
    }

    // ─── Private helpers ───

    /**
     * Shadow-write scored posts to a tmp ZSet, trim to top 3000, then atomic RENAME.
     * Loop-based ZADD — each post_id member gets its Hacker News score.
     */
    private void rebuildPoolFixed(String tmpKey, String liveKey, Map<Long, Double> scoredPosts) {
        redis.delete(tmpKey);

        if (scoredPosts.isEmpty()) {
            redis.delete(liveKey);
            return;
        }

        // Loop ZADD
        for (Map.Entry<Long, Double> entry : scoredPosts.entrySet()) {
            redis.opsForZSet().add(tmpKey, String.valueOf(entry.getKey()), entry.getValue());
        }

        // Trim to top 3000
        Long size = redis.opsForZSet().size(tmpKey);
        if (size != null && size > RECOMMEND_POOL_SIZE) {
            redis.opsForZSet().removeRange(tmpKey, 0, size - RECOMMEND_POOL_SIZE - 1);
        }

        redis.expire(tmpKey, Duration.ofDays(7));

        // Atomic RENAME
        redis.rename(tmpKey, liveKey);
    }

    private void clearPools() {
        redis.delete(keyBuilder.feedPoolRecommendMale());
        redis.delete(keyBuilder.feedPoolRecommendFemale());
    }

    /**
     * Three-way merge with position allocation and same-friend rate limiting.
     *
     * Position rule:
     *   1,2,4,5,7,8,9,10 → recommend (degrade cold_start → friend)
     *   3 → friend (degrade recommend)
     *   6 → cold_start (degrade recommend)
     *
     * Same-friend: ≤ 1 post per friend user per page.
     */
    private List<PostReadService.PostDetail> mergeThreeWay(
            List<Long> recommend, List<Long> friends, List<Long> coldStart,
            int pageSize, RBloomFilter<String> bloom) {

        // Use queues for easy polling
        Deque<Long> recQ = new ArrayDeque<>(recommend);
        Deque<Long> friendQ = new ArrayDeque<>(friends);
        Deque<Long> csQ = new ArrayDeque<>(coldStart);

        List<PostReadService.PostDetail> result = new ArrayList<>();
        Set<Long> usedFriendUserIds = new HashSet<>(); // same-friend rate limit

        int[] friendPositions = {3};
        int[] coldStartPositions = {6};

        for (int pos = 1; pos <= pageSize; pos++) {
            PostReadService.PostDetail item = null;

            boolean isFriendSlot = contains(friendPositions, pos);
            boolean isCsSlot = contains(coldStartPositions, pos);

            if (isFriendSlot) {
                // Priority: friend → recommend (degrade)
                item = pollFriendCheck(friendQ, recQ, usedFriendUserIds, bloom);
            } else if (isCsSlot) {
                // Priority: cold_start → recommend (degrade)
                item = pollColdStartCheck(csQ, recQ, bloom);
            } else {
                // Default: recommend → cold_start → friend (degrade chain)
                item = pollRecommendCheck(recQ, csQ, friendQ, usedFriendUserIds, bloom);
            }

            if (item != null) {
                result.add(item);
                bloom.add(String.valueOf(item.postId()));
            }
        }

        return result;
    }

    private PostReadService.PostDetail pollFriendCheck(Deque<Long> friendQ, Deque<Long> recQ,
                                                        Set<Long> usedFriendIds,
                                                        RBloomFilter<String> bloom) {
        // Try friend queue first (rate-limited to ≤1 per friend per page)
        while (!friendQ.isEmpty()) {
            Long postId = friendQ.poll();
            PostReadService.PostDetail detail = postReadService.getPostDetailSafe(postId);
            if (detail != null && !bloom.contains(String.valueOf(postId))) {
                if (!usedFriendIds.contains(detail.userId())) {
                    usedFriendIds.add(detail.userId());
                    return detail;
                }
                // Same friend already shown, skip this post
            }
        }
        // Degrade to recommend
        return pollFromQueue(recQ, bloom);
    }

    private PostReadService.PostDetail pollColdStartCheck(Deque<Long> csQ, Deque<Long> recQ,
                                                           RBloomFilter<String> bloom) {
        PostReadService.PostDetail item = pollFromQueue(csQ, bloom);
        if (item != null) return item;
        // Degrade to recommend
        return pollFromQueue(recQ, bloom);
    }

    private PostReadService.PostDetail pollRecommendCheck(Deque<Long> recQ, Deque<Long> csQ,
                                                           Deque<Long> friendQ,
                                                           Set<Long> usedFriendIds,
                                                           RBloomFilter<String> bloom) {
        // Primary: recommend
        PostReadService.PostDetail item = pollFromQueue(recQ, bloom);
        if (item != null) return item;

        // Degrade 1: cold_start
        item = pollFromQueue(csQ, bloom);
        if (item != null) return item;

        // Degrade 2: friend (with rate limit)
        while (!friendQ.isEmpty()) {
            Long postId = friendQ.poll();
            PostReadService.PostDetail detail = postReadService.getPostDetailSafe(postId);
            if (detail != null && !bloom.contains(String.valueOf(postId))) {
                if (!usedFriendIds.contains(detail.userId())) {
                    usedFriendIds.add(detail.userId());
                    return detail;
                }
            }
        }
        return null;
    }

    private PostReadService.PostDetail pollFromQueue(Deque<Long> queue, RBloomFilter<String> bloom) {
        while (!queue.isEmpty()) {
            Long postId = queue.poll();
            if (bloom.contains(String.valueOf(postId))) {
                continue;
            }
            PostReadService.PostDetail detail = postReadService.getPostDetailSafe(postId);
            if (detail != null) {
                return detail;
            }
        }
        return null;
    }

    private RBloomFilter<String> getBloomFilter(long userId) {
        String bloomKey = keyBuilder.userReadBloom(userId);
        RBloomFilter<String> filter = redissonClient.getBloomFilter(bloomKey);
        if (!filter.isExists()) {
            filter.tryInit(bloomCapacity, bloomFpp);
            filter.expire(BLOOM_TTL);
        }
        return filter;
    }

    private List<Long> filterByBloom(RBloomFilter<String> bloom, List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) return Collections.emptyList();
        return postIds.stream()
                .filter(id -> !bloom.contains(String.valueOf(id)))
                .collect(Collectors.toList());
    }

    private List<Long> toLongList(Set<String> set) {
        if (set == null || set.isEmpty()) return Collections.emptyList();
        return set.stream().map(Long::parseLong).collect(Collectors.toList());
    }

    private int getRedisDelta(String key) {
        try {
            String val = redis.opsForValue().get(key);
            return val != null ? Integer.parseInt(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean contains(int[] arr, int target) {
        for (int v : arr) if (v == target) return true;
        return false;
    }

    // ─── DTO ───

    public record FeedResult(
            List<PostReadService.PostDetail> items,
            String nextCursor,
            boolean hasMore
    ) {}
}
