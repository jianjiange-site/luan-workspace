package com.dating.post.job;

import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.common.RedisScripts;
import com.dating.post.manager.PostStatManager;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Flushes like deltas from Redis to PostgreSQL every 60 seconds.
 * <p>
 * Write coalescing pattern:
 *   1. SRANDMEMBER updated_set 100 → find posts with unflushed deltas
 *   2. For each: Lua GET + SET 0 → atomically collect delta and reset counter
 *   3. UPDATE post_stats SET like_count = like_count + delta
 *   4. SREM processed post_ids from updated_set
 * <p>
 * ShedLock ensures only one instance runs across the cluster.
 */
@Slf4j
@Component
public class LikeFlushJob {

    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;
    private final RedisScripts redisScripts;
    private final PostStatManager postStatManager;

    public LikeFlushJob(StringRedisTemplate redis,
                        CacheKeyBuilder keyBuilder,
                        RedisScripts redisScripts,
                        PostStatManager postStatManager) {
        this.redis = redis;
        this.keyBuilder = keyBuilder;
        this.redisScripts = redisScripts;
        this.postStatManager = postStatManager;
    }

    /**
     * Flush like count deltas every 60 seconds.
     * lockAtMostFor=2min prevents zombie lock; lockAtLeastFor=5s debounces.
     */
    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "post.likeFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S")
    public void flushLikes() {
        String updatedSetKey = keyBuilder.postUpdatedSet();
        Long setSize = redis.opsForSet().size(updatedSetKey);
        if (setSize == null || setSize == 0) {
            return;
        }

        // 1. SRANDMEMBER: randomly sample up to 100 posts with unflushed deltas
        Set<String> sampled = redis.opsForSet().distinctRandomMembers(updatedSetKey, BATCH_SIZE);
        if (sampled == null || sampled.isEmpty()) {
            return;
        }

        List<Long> postIds = sampled.stream()
                .filter(id -> id != null && !id.isEmpty())
                .map(Long::parseLong)
                .toList();

        if (postIds.isEmpty()) {
            return;
        }

        DefaultRedisScript<Long> getAndSetZero = redisScripts.getAndSetZero();
        int processed = 0;

        for (Long postId : postIds) {
            try {
                // 2. Lua: atomic GET + SET 0 — no like is ever lost
                String incrKey = keyBuilder.statIncrLikes(postId);
                Long delta = redis.execute(getAndSetZero, List.of(incrKey));

                if (delta != null && delta != 0) {
                    // 3. UPDATE ... SET like_count = like_count + delta
                    postStatManager.incrementLikeCount(postId, delta.intValue());
                }

                // 4. SREM from updated_set (remove even if delta was 0, cleaner set state)
                redis.opsForSet().remove(updatedSetKey, String.valueOf(postId));
                processed++;
            } catch (Exception e) {
                log.error("Like flush failed for postId={}", postId, e);
                // Don't SREM on failure — next job run will retry this post_id
            }
        }

        log.info("Like flush completed: processed={} sampled={}", processed, postIds.size());
    }
}
