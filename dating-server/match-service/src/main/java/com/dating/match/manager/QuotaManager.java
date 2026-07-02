package com.dating.match.manager;

import com.dating.match.common.CacheKeyBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manager for daily quota operations in Redis HASH.
 * Quota is stored as HASH: match:quota:<user_id>:<yyyymmdd>
 * Fields: right_swipe, cards, super_hi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuotaManager {

    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Get today's date string in UTC */
    private String today() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(DATE_FMT);
    }

    /**
     * Atomically increment a quota field and check against limit.
     * Returns true if within limit, false if exceeded.
     */
    public boolean incrementAndCheck(long userId, String field, int limit) {
        String key = keyBuilder.quota(userId, today());
        Long val = redis.opsForHash().increment(key, field, 1);
        redis.expire(key, 36, TimeUnit.HOURS);
        if (val != null && val > limit) {
            // Rollback
            redis.opsForHash().increment(key, field, -1);
            return false;
        }
        return true;
    }

    /**
     * Decrement a quota field (e.g., compensating transaction).
     */
    public void decrement(long userId, String field) {
        String key = keyBuilder.quota(userId, today());
        redis.opsForHash().increment(key, field, -1);
    }

    /**
     * Get current quota usage for a user.
     */
    public Map<Object, Object> getQuota(long userId) {
        String key = keyBuilder.quota(userId, today());
        return redis.opsForHash().entries(key);
    }

    /**
     * Get a specific quota field value.
     */
    public int getField(long userId, String field) {
        String key = keyBuilder.quota(userId, today());
        Object val = redis.opsForHash().get(key, field);
        return val != null ? Integer.parseInt(val.toString()) : 0;
    }

    /** Quota field names */
    public static final String FIELD_RIGHT_SWIPE = "right_swipe";
    public static final String FIELD_CARDS = "cards";
    public static final String FIELD_SUPER_HI = "super_hi";
}
