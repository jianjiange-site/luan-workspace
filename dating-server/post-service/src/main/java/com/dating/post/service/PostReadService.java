package com.dating.post.service;

import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.constant.ErrorCode;
import com.dating.post.constant.PostStatus;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostImage;
import com.dating.post.entity.PostStat;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostManager;
import com.dating.post.manager.PostStatManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Post read operations: detail and user post listing.
 * <p>
 * Post detail: Redis Hash first → DB fallback → rebuild cache.
 * User posts: cursor pagination on (user_id, post_id DESC) index.
 * <p>
 * Real-time counts = DB base + Redis delta (write coalescing pattern).
 */
@Slf4j
@Service
public class PostReadService {

    private static final Duration DETAIL_TTL = Duration.ofDays(7);

    private final PostManager postManager;
    private final PostStatManager postStatManager;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    public PostReadService(PostManager postManager,
                           PostStatManager postStatManager,
                           StringRedisTemplate redis,
                           CacheKeyBuilder keyBuilder) {
        this.postManager = postManager;
        this.postStatManager = postStatManager;
        this.redis = redis;
        this.keyBuilder = keyBuilder;
    }

    /**
     * Get post detail by post_id. Cache-first, DB fallback.
     * Returns post with real-time counts (DB base + Redis delta).
     *
     * @param postId business primary key
     * @return PostDetail DTO (post info + image keys + counts)
     * @throws BizException 4005 if post not found or deleted
     */
    public PostDetail getPostDetail(long postId) {
        // 1. Try Redis cache
        Map<Object, Object> cached = redis.opsForHash().entries(keyBuilder.postDetail(postId));
        if (!cached.isEmpty() && !"0".equals(String.valueOf(cached.get("status")))) {
            return buildDetailFromCache(postId, cached);
        }

        // 2. DB fallback
        return buildDetailFromDb(postId);
    }

    /**
     * List posts by a target user, cursor pagination.
     *
     * @param targetUserId whose posts to list
     * @param pageSize     items per page (max 50)
     * @param cursor       post_id cursor, 0 = first page
     * @return list of PostDetail with next_cursor and has_more
     */
    public UserPostsResult listUserPosts(long targetUserId, int pageSize, long cursor) {
        if (pageSize <= 0 || pageSize > 50) {
            pageSize = 10;
        }

        List<Post> posts = postManager.listByUserId(targetUserId, pageSize + 1, cursor);
        boolean hasMore = posts.size() > pageSize;
        if (hasMore) {
            posts = posts.subList(0, pageSize);
        }

        List<PostDetail> items = posts.stream()
                .map(p -> buildDetailFromEntity(p))
                .toList();

        long nextCursor = items.isEmpty() ? 0 : items.get(items.size() - 1).postId();
        return new UserPostsResult(items, nextCursor, hasMore);
    }

    // ─── Package-private helpers (used by FeedService) ───

    /**
     * Get post detail with try-catch safety for Feed service.
     * Returns null if post is deleted/not-found (caller skips).
     */
    PostDetail getPostDetailSafe(long postId) {
        try {
            return getPostDetail(postId);
        } catch (BizException e) {
            log.warn("Post detail unavailable for feed, postId={} reason={}", postId, e.getMessage());
            return null;
        }
    }

    // ─── Private helpers ───

    private PostDetail buildDetailFromCache(long postId, Map<Object, Object> cached) {
        long userId = Long.parseLong(String.valueOf(cached.get("user_id")));
        String content = String.valueOf(cached.get("content"));
        String imageKeysStr = String.valueOf(cached.get("image_keys"));
        List<String> imageKeys = imageKeysStr.isEmpty()
                ? Collections.emptyList()
                : Arrays.asList(imageKeysStr.split(","));
        long createdAt = Long.parseLong(String.valueOf(cached.get("created_at")));
        int status = Integer.parseInt(String.valueOf(cached.getOrDefault("status", "1")));

        // Real-time counts = DB base + Redis delta
        PostStat stat = postStatManager.findByPostId(postId);
        int baseLikes = stat != null ? stat.getLikeCount() : 0;
        int baseComments = stat != null ? stat.getCommentCount() : 0;

        String likeDeltaStr = redis.opsForValue().get(keyBuilder.statIncrLikes(postId));
        String commentDeltaStr = redis.opsForValue().get(keyBuilder.statIncrComments(postId));
        int likeDelta = likeDeltaStr != null ? Integer.parseInt(likeDeltaStr) : 0;
        int commentDelta = commentDeltaStr != null ? Integer.parseInt(commentDeltaStr) : 0;

        return new PostDetail(postId, userId, content, imageKeys,
                baseLikes + likeDelta, baseComments + commentDelta, createdAt, status);
    }

    private PostDetail buildDetailFromDb(long postId) {
        // Single-table: get post
        Post post = postManager.findByPostId(postId);
        if (post == null || post.getStatus() == PostStatus.DELETED) {
            throw new BizException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
        }

        // Single-table: get images (no JOIN)
        List<PostImage> images = postManager.findImagesByPostId(postId);
        List<String> imageKeys = images.stream()
                .map(PostImage::getImageKey)
                .collect(Collectors.toList());

        // Single-table: get stat
        PostStat stat = postStatManager.findByPostId(postId);
        int baseLikes = stat != null ? stat.getLikeCount() : 0;
        int baseComments = stat != null ? stat.getCommentCount() : 0;

        // Redis delta for real-time count
        int likeDelta = getRedisDelta(keyBuilder.statIncrLikes(postId));
        int commentDelta = getRedisDelta(keyBuilder.statIncrComments(postId));

        PostDetail detail = new PostDetail(postId, post.getUserId(), post.getContent(), imageKeys,
                baseLikes + likeDelta, baseComments + commentDelta,
                post.getCreatedAt() != null ? post.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC) : 0,
                post.getStatus());

        // Rebuild cache (best-effort)
        rebuildDetailCache(detail);

        return detail;
    }

    private PostDetail buildDetailFromEntity(Post post) {
        // Used for list endpoints — lighter, fetch images + stats per post
        List<PostImage> images = postManager.findImagesByPostId(post.getPostId());
        List<String> imageKeys = images.stream().map(PostImage::getImageKey).toList();

        PostStat stat = postStatManager.findByPostId(post.getPostId());
        int baseLikes = stat != null ? stat.getLikeCount() : 0;
        int baseComments = stat != null ? stat.getCommentCount() : 0;
        int likeDelta = getRedisDelta(keyBuilder.statIncrLikes(post.getPostId()));
        int commentDelta = getRedisDelta(keyBuilder.statIncrComments(post.getPostId()));

        return new PostDetail(post.getPostId(), post.getUserId(), post.getContent(), imageKeys,
                baseLikes + likeDelta, baseComments + commentDelta,
                post.getCreatedAt() != null ? post.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC) : 0,
                post.getStatus());
    }

    private int getRedisDelta(String key) {
        try {
            String val = redis.opsForValue().get(key);
            return val != null ? Integer.parseInt(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void rebuildDetailCache(PostDetail detail) {
        try {
            String key = keyBuilder.postDetail(detail.postId());
            Map<String, String> hash = new HashMap<>();
            hash.put("post_id", String.valueOf(detail.postId()));
            hash.put("user_id", String.valueOf(detail.userId()));
            hash.put("content", detail.content());
            hash.put("image_keys", String.join(",", detail.imageKeys()));
            hash.put("created_at", String.valueOf(detail.createdAt()));
            hash.put("status", String.valueOf(detail.status()));
            redis.opsForHash().putAll(key, hash);
            redis.expire(key, DETAIL_TTL);
        } catch (Exception e) {
            log.warn("Failed to rebuild detail cache for postId={}", detail.postId(), e);
        }
    }

    // ─── DTOs ───

    /** Post detail DTO (service-layer, no proto dependency) */
    public record PostDetail(
            long postId,
            long userId,
            String content,
            List<String> imageKeys,
            int likeCount,
            int commentCount,
            long createdAt,
            int status
    ) {}

    /** Cursor-paginated user posts result */
    public record UserPostsResult(
            List<PostDetail> items,
            long nextCursor,
            boolean hasMore
    ) {}
}
