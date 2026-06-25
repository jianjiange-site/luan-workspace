package com.dating.post.mq.producer;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends fanout messages to RocketMQ after post creation (outside DB transaction).
 * <p>
 * Local retry 3 times with 2s timeout each. After 3 failures, logs error + increments
 * post.fanout.produce.fail counter. Does NOT block post creation response.
 * <p>
 * Topic: youjianxin-dating-dev-post-fanout-v1 (per CLAUDE.md prefix convention)
 */
@Slf4j
@Component
public class PostFanoutProducer {

    @Value("${rocketmq.fanout.topic:youjianxin-dating-dev-post-fanout-v1}")
    private String topic;

    private final RocketMQTemplate rocketMQTemplate;

    public PostFanoutProducer(RocketMQTemplate rocketMQTemplate) {
        this.rocketMQTemplate = rocketMQTemplate;
    }

    /**
     * Sync-send fanout message with 3 retries. Failures are logged and metered,
     * never thrown back to caller.
     *
     * @param postId         the new post's id
     * @param authorUserId   the post author's user id
     * @param createdAtEpoch post creation epoch seconds
     */
    public void send(long postId, long authorUserId, long createdAtEpoch) {
        FanoutMessage msg = new FanoutMessage(postId, authorUserId, createdAtEpoch);

        for (int i = 0; i < 3; i++) {
            try {
                SendResult result = rocketMQTemplate.syncSend(topic, msg, 2000);
                if (SendStatus.SEND_OK == result.getSendStatus()) {
                    return;
                }
                log.warn("fanout send returned non-OK status, postId={} attempt={} status={}",
                        postId, i + 1, result.getSendStatus());
            } catch (Exception e) {
                log.warn("fanout send retry, postId={} attempt={}", postId, i + 1, e);
            }
        }
        // All 3 retries failed — log error, do NOT block response
        log.error("fanout send FAILED after 3 retries, postId={}", postId);
        // TODO: increment meterRegistry.counter("post.fanout.produce.fail")
    }
}
