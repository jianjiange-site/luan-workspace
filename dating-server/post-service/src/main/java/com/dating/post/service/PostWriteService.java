package com.dating.post.service;

import com.dating.post.client.UserClient;
import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.config.SnowflakeIdGenerator;
import com.dating.post.constant.ErrorCode;
import com.dating.post.constant.PostStatus;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostStat;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import com.dating.post.mq.producer.PostFanoutProducer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Post write operations: create and delete.
 * <p>
 * Transaction boundary covers only DB writes (posts + post_images + post_stats).
 * Redis, cold-start pool, and MQ fanout are best-effort outside the transaction.
 */
@Slf4j
@Service
public class PostWriteService {

    private static final int MAX_CONTENT_LENGTH = 1024;
    private static final int MAX_IMAGES = 9;
    private static final Duration DETAIL_TTL = Duration.ofDays(7);

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final PostReadService postReadService;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;
    private final UserClient userClient;
    private final PostFanoutProducer fanoutProducer;

    public PostWriteService(PostManager postManager,
                            PostStatManager postStatManager,
                            PostReadService postReadService,
                            SnowflakeIdGenerator idGenerator,
                            StringRedisTemplate redis,
                            CacheKeyBuilder keyBuilder,
                            UserClient userClient,
                            PostFanoutProducer fanoutProducer) {
        this.postManager = postManager;
        this.postStatManager = postStatManager;
        this.postReadService = postReadService;
        this.idGenerator = idGenerator;
        this.redis = redis;
        this.keyBuilder = keyBuilder;
        this.userClient = userClient;
        this.fanoutProducer = fanoutProducer;
    }

    /**
     * Create a new post.
     *
     * @param content    post text, 1-1024 chars
     * @param imageKeys  object storage keys (presigned-uploaded), ≤ 9 items
     * @param userId     post author (from gRPC Metadata x-user-id)
     * @return the generated post_id
     * @throws BizException 4001-4004 for validation errors
     */
    public long createPost(String content, List<String> imageKeys, long userId) {
        // 1. Validate
        validateContent(content);
        validateImages(imageKeys);

        // 2. Generate post_id
        long postId = idGenerator.nextId();
        long nowEpoch = Instant.now().getEpochSecond();

        // 3. DB transaction: posts + post_images + post_stats
        Post post = buildPost(postId, userId, content);
        try {
            insertPostWithImages(post, imageKeys);
            insertPostStat(postId);
        } catch (DataAccessException e) {
            log.error("DB insert failed for postId={}", postId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "Failed to create post", e);
        }

        // 4. Redis: cache post detail (best-effort, outside transaction)
        try {
            cachePostDetail(postId, userId, content, imageKeys, nowEpoch);
        } catch (Exception e) {
            log.warn("Redis cache failed for postId={}, detail recoverable from DB", postId, e);
        }

        // 5. Cold-start pool: ZADD by author gender (best-effort)
        try {
            boolean isMale = userClient.isMale(userId);
            String coldStartKey = isMale ? keyBuilder.feedColdStartMale() : keyBuilder.feedColdStartFemale();
            redis.opsForZSet().add(coldStartKey, String.valueOf(postId), nowEpoch);
            redis.expire(coldStartKey, Duration.ofDays(7));
            // Optional trim: keep ≤ 10000
            Long csSize = redis.opsForZSet().size(coldStartKey);
            if (csSize != null && csSize > 10000) {
                redis.opsForZSet().removeRange(coldStartKey, 0, csSize - 10001);
            }
        } catch (Exception e) {
            log.warn("Cold-start pool ZADD failed for postId={}, will be picked up by FeedScoreJob", postId, e);
        }

        // 6. RocketMQ fanout (best-effort, outside transaction, 3 retries)
        try {
            fanoutProducer.send(postId, userId, nowEpoch);
        } catch (Exception e) {
            log.error("Fanout producer failed for postId={}", postId, e);
        }

        log.info("Post created: postId={} userId={} images={}", postId, userId, imageKeys != null ? imageKeys.size() : 0);
        return postId;
    }

    /**
     * Delete a post. Only the post owner can delete.
     *
     * @param postId the post to delete
     * @param userId the user requesting deletion (must be the post author)
     * @throws BizException 4005 if post not found, 4030 if not the owner
     */
    public void deletePost(long postId, long userId) {
        // 1. Verify existence + ownership
        Post post = postManager.findByPostId(postId);
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
        }
        if (!post.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "Not the post owner");
        }

        // 2. Soft-delete
        postManager.softDelete(postId);

        // 3. Delete Redis detail cache
        try {
            redis.delete(keyBuilder.postDetail(postId));
        } catch (Exception e) {
            log.warn("Failed to delete detail cache for postId={}", postId, e);
        }

        // 4. Remove from cold-start pools (best-effort, both genders since we don't re-check)
        try {
            redis.opsForZSet().remove(keyBuilder.feedColdStartMale(), String.valueOf(postId));
            redis.opsForZSet().remove(keyBuilder.feedColdStartFemale(), String.valueOf(postId));
        } catch (Exception e) {
            log.warn("Failed to remove from cold-start pools for postId={}", postId, e);
        }

        // 5. Remove from updated_set (stop flush jobs from processing)
        try {
            redis.opsForSet().remove(keyBuilder.postUpdatedSet(), String.valueOf(postId));
        } catch (Exception e) {
            log.warn("Failed to remove from updated_set for postId={}", postId, e);
        }

        log.info("Post deleted: postId={} userId={}", postId, userId);
    }

    // ─── Private helpers ───

    private void validateContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BizException(ErrorCode.CONTENT_EMPTY, "Content must not be empty");
        }
        String trimmed = content.trim();
        if (trimmed.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.CONTENT_TOO_LONG,
                    "Content too long: " + trimmed.length() + " > " + MAX_CONTENT_LENGTH);
        }
    }

    private void validateImages(List<String> imageKeys) {
        if (imageKeys == null || imageKeys.isEmpty()) {
            return;
        }
        if (imageKeys.size() > MAX_IMAGES) {
            throw new BizException(ErrorCode.IMAGE_COUNT_EXCEEDED,
                    "Too many images: " + imageKeys.size() + " > " + MAX_IMAGES);
        }
        for (String key : imageKeys) {
            if (key == null || key.isBlank()) {
                throw new BizException(ErrorCode.IMAGE_KEY_EMPTY, "Image key must not be empty");
            }
        }
    }

    private Post buildPost(long postId, long userId, String content) {
        Post post = new Post();
        post.setPostId(postId);
        post.setUserId(userId);
        post.setContent(content.trim());
        post.setStatus(PostStatus.NORMAL);
        post.setDeleted(0);
        return post;
    }

    @Transactional(rollbackFor = Exception.class)
    private void insertPostWithImages(Post post, List<String> imageKeys) {
        postManager.insert(post);
        if (imageKeys != null && !imageKeys.isEmpty()) {
            postManager.insertImages(post.getPostId(), imageKeys);
        }
    }

    private void insertPostStat(long postId) {
        PostStat stat = new PostStat();
        stat.setPostId(postId);
        stat.setLikeCount(0);
        stat.setCommentCount(0);
        postStatManager.insert(stat);
    }

    private void cachePostDetail(long postId, long userId, String content, List<String> imageKeys, long nowEpoch) {
        String key = keyBuilder.postDetail(postId);
        Map<String, String> hash = new HashMap<>();
        hash.put("post_id", String.valueOf(postId));
        hash.put("user_id", String.valueOf(userId));
        hash.put("content", content);
        hash.put("image_keys", imageKeys != null
                ? imageKeys.stream().filter(k -> k != null && !k.isBlank()).collect(Collectors.joining(","))
                : "");
        hash.put("created_at", String.valueOf(nowEpoch));
        hash.put("status", String.valueOf(PostStatus.NORMAL));
        redis.opsForHash().putAll(key, hash);
        redis.expire(key, DETAIL_TTL);
    }
}
