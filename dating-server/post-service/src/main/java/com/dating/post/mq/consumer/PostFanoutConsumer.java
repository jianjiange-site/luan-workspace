package com.dating.post.mq.consumer;

import com.dating.post.client.UserClient;
import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.mq.producer.FanoutMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Consumes fanout messages from RocketMQ and performs write-fanout to followers' timelines.
 * <p>
 * Process: pull followers from user-service → ZADD each follower's timeline → trim to 100 → EXPIRE 7d.
 * <p>
 * CONCURRENTLY mode: no ordering requirements between different posts' fanout messages.
 * ZADD idempotency: same (postId, createdAtEpoch) pair is overwrite-safe on replay.
 * <p>
 * Failure: RocketMQ auto-retry up to 16 times → DLQ if all fail. Alert on DLQ backlog.
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "youjianxin-dating-dev-post-fanout-v1",
        consumerGroup = "youjianxin-dating-dev-post-service-fanout",
        consumeMode = ConsumeMode.CONCURRENTLY,
        maxReconsumeTimes = 16,
        accessKey = "rocketmq-student",
        secretKey = "5cafa390b8a42c25"
)
public class PostFanoutConsumer implements RocketMQListener<FanoutMessage> {

    private static final int MAX_TIMELINE_SIZE = 100;
    private static final Duration TIMELINE_TTL = Duration.ofDays(7);

    private final UserClient userClient;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    public PostFanoutConsumer(UserClient userClient,
                              StringRedisTemplate redis,
                              CacheKeyBuilder keyBuilder) {
        this.userClient = userClient;
        this.redis = redis;
        this.keyBuilder = keyBuilder;
    }

    @Override
    public void onMessage(FanoutMessage msg) {
        log.info("Fanout message received: postId={} authorUserId={}", msg.postId(), msg.authorUserId());

        // Pull followers from user-service (RPC exception → auto retry by RocketMQ)
        List<Long> followers = userClient.getFriendUserIds(msg.authorUserId());
        if (followers.isEmpty()) {
            log.debug("No followers for userId={}, fanout complete (no-op)", msg.authorUserId());
            return;
        }

        long fanoutCount = 0;
        for (long follower : followers) {
            // ZADD is idempotent: same score + member = overwrite, no duplicate concern
            String timelineKey = keyBuilder.userTimeline(follower);
            redis.opsForZSet().add(timelineKey, String.valueOf(msg.postId()), msg.createdAtEpoch());

            // Trim timeline to MAX_TIMELINE_SIZE newest posts
            Long size = redis.opsForZSet().size(timelineKey);
            if (size != null && size > MAX_TIMELINE_SIZE) {
                redis.opsForZSet().removeRange(timelineKey, 0, size - MAX_TIMELINE_SIZE - 1);
            }

            redis.expire(timelineKey, TIMELINE_TTL);
            fanoutCount++;
        }

        log.info("Fanout complete: postId={} followers={} fannedOut={}", msg.postId(), followers.size(), fanoutCount);
    }
}
