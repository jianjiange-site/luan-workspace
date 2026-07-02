package com.dating.match.manager;

import com.dating.match.common.CacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manager for Redis feed LIST and swiped SET operations.
 *
 * Feed: LIST match:feed:<user_id> — elements are "target_user_id:target_user_type"
 * Swiped: SET match:swiped:<user_id> — all target_user_ids ever swiped
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedManager {

    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    // LIST element separator
    private static final String SEP = ":";

    /**
     * LPOP up to 'count' elements from the feed LIST.
     * Returns elements as "target_user_id:target_user_type" strings.
     */
    public List<String> lpop(long userId, int count) {
        String key = keyBuilder.feed(userId);
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String val = redis.opsForList().leftPop(key);
            if (val == null) break;
            result.add(val);
        }
        return result;
    }

    /**
     * RPUSH a batch of feed cards.
     * Each element is "target_user_id:target_user_type".
     */
    public void rpush(long userId, List<String> cards) {
        if (cards.isEmpty()) return;
        String key = keyBuilder.feed(userId);
        redis.opsForList().rightPushAll(key, cards.toArray(new String[0]));
        redis.expire(key, 7, TimeUnit.DAYS);
    }

    /**
     * DEL the feed key (used by D1 cron to overwrite).
     */
    public void deleteFeed(long userId) {
        redis.delete(keyBuilder.feed(userId));
    }

    /**
     * Get feed LIST length.
     */
    public long feedLength(long userId) {
        Long len = redis.opsForList().size(keyBuilder.feed(userId));
        return len != null ? len : 0;
    }

    /**
     * SADD a target_user_id to the swiped SET.
     */
    public void markSwiped(long userId, long targetUserId) {
        redis.opsForSet().add(keyBuilder.swiped(userId), String.valueOf(targetUserId));
    }

    /**
     * SMISMEMBER: check which target_user_ids have been swiped.
     * Returns a boolean array parallel to the input list.
     */
    public List<Boolean> areSwiped(long userId, List<Long> targetUserIds) {
        String key = keyBuilder.swiped(userId);
        // Use pipeline for batch check
        List<Object> results = redis.executePipelined((connection) -> {
            byte[] rawKey = key.getBytes();
            for (Long tid : targetUserIds) {
                connection.sIsMember(rawKey, String.valueOf(tid).getBytes());
            }
            return null;
        });
        if (results == null) return targetUserIds.stream().map(x -> false).toList();
        return results.stream()
                .map(o -> o instanceof Boolean b && b)
                .toList();
    }

    /**
     * Check if a single user has been swiped.
     */
    public boolean isSwiped(long userId, long targetUserId) {
        Boolean exists = redis.opsForSet().isMember(keyBuilder.swiped(userId), String.valueOf(targetUserId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Get all swiped target IDs for a user (for lazy cache rebuild).
     */
    public Set<String> getSwipedSet(long userId) {
        return redis.opsForSet().members(keyBuilder.swiped(userId));
    }

    // ── Utility methods for feed card encoding ──

    /** Encode a card as "target_user_id:target_user_type" */
    public static String encodeCard(long targetUserId, int targetUserType) {
        return targetUserId + SEP + targetUserType;
    }

    /** Decode target_user_id from a card string */
    public static long decodeUserId(String card) {
        return Long.parseLong(card.substring(0, card.indexOf(SEP)));
    }

    /** Decode target_user_type from a card string */
    public static int decodeUserType(String card) {
        return Integer.parseInt(card.substring(card.indexOf(SEP) + 1));
    }
}
