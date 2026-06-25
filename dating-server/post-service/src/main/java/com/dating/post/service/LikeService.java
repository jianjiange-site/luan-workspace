package com.dating.post.service;

import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.constant.ErrorCode;
import com.dating.post.constant.LikeStatus;
import com.dating.post.entity.Post;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostLikeManager;
import com.dating.post.manager.PostManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Like/unlike operations with write coalescing pattern.
 * <p>
 * Write path: 1. DB upsert (idempotent) → 2. Redis INCR/DECR delta → 3. SADD updated_set.
 * Read path: DB base + Redis delta (handled in PostReadService).
 * <p>
 * Idempotency: ON CONFLICT DO UPDATE only when status actually changes.
 * Affected rows == 0 → already target state, return immediately.
 */
@Slf4j
@Service
public class LikeService {

    private static final Duration INCR_TTL = Duration.ofDays(7);

    private final PostLikeManager postLikeManager;
    private final PostManager postManager;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    public LikeService(PostLikeManager postLikeManager,
                       PostManager postManager,
                       StringRedisTemplate redis,
                       CacheKeyBuilder keyBuilder) {
        this.postLikeManager = postLikeManager;
        this.postManager = postManager;
        this.redis = redis;
        this.keyBuilder = keyBuilder;
    }

    /**
     * Like or unlike a post. Idempotent: repeating same action is a no-op.
     *
     * @param userId user performing the action
     * @param postId target post
     * @param liked  true = like, false = unlike
     * @return true if state changed, false if already in target state (idempotent)
     * @throws BizException 4005 if post not found
     */
    public boolean actionLike(long userId, long postId, boolean liked) {
        // 1. Verify post exists (single-table lookup)
        Post post = postManager.findByPostId(postId);
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
        }

        // 2. DB upsert (single row per (userId, postId), no row-lock contention on hot posts)
        int status = liked ? LikeStatus.LIKED : LikeStatus.UNLIKED;
        int affected = postLikeManager.upsert(userId, postId, status);

        // 3. Idempotent: already in target state
        if (affected == 0) {
            log.debug("Like action idempotent: userId={} postId={} liked={}", userId, postId, liked);
            return false;
        }

        // 4. Redis: increment/decrement delta + mark as updated
        int delta = liked ? 1 : -1;
        String incrKey = keyBuilder.statIncrLikes(postId);
        redis.opsForValue().increment(incrKey, delta);
        redis.expire(incrKey, INCR_TTL);

        redis.opsForSet().add(keyBuilder.postUpdatedSet(), String.valueOf(postId));

        log.info("Like action: userId={} postId={} liked={}", userId, postId, liked);
        return true;
    }
}
