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
 * Flushes comment count deltas from Redis to PostgreSQL every 60 seconds.
 * <p>
 * Same write coalescing pattern as LikeFlushJob:
 *   1. Sample from updated_set
 *   2. Lua atomic GET + SET 0 on comment delta keys
 *   3. Increment post_stats.comment_count by delta
 *   4. SREM from updated_set
 */
@Slf4j
@Component
public class CommentFlushJob {

    private static final int BATCH_SIZE = 100;

    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;
    private final RedisScripts redisScripts;
    private final PostStatManager postStatManager;

    public CommentFlushJob(StringRedisTemplate redis,
                           CacheKeyBuilder keyBuilder,
                           RedisScripts redisScripts,
                           PostStatManager postStatManager) {
        this.redis = redis;
        this.keyBuilder = keyBuilder;
        this.redisScripts = redisScripts;
        this.postStatManager = postStatManager;
    }

    @Scheduled(fixedRate = 60_000)
    @SchedulerLock(name = "post.commentFlush",
            lockAtMostFor = "PT2M",
            lockAtLeastFor = "PT5S")
    public void flushComments() {
        String updatedSetKey = keyBuilder.postUpdatedSet();
        Long setSize = redis.opsForSet().size(updatedSetKey);
        if (setSize == null || setSize == 0) {
            return;
        }

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
                // Lua GET + SET 0 on comment delta
                String incrKey = keyBuilder.statIncrComments(postId);
                Long delta = redis.execute(getAndSetZero, List.of(incrKey));

                if (delta != null && delta != 0) {
                    postStatManager.incrementCommentCount(postId, delta.intValue());
                }

                redis.opsForSet().remove(updatedSetKey, String.valueOf(postId));
                processed++;
            } catch (Exception e) {
                log.error("Comment flush failed for postId={}", postId, e);
            }
        }

        log.info("Comment flush completed: processed={} sampled={}", processed, postIds.size());
    }
}
