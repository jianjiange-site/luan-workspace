package com.dating.post.mq.producer;

/**
 * Fanout message sent via RocketMQ after post creation.
 * Minimal payload: only IDs and timestamp — consumer fetches followers at processing time.
 */
public record FanoutMessage(
        long postId,
        long authorUserId,
        long createdAtEpoch
) {}
