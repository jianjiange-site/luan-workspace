package com.dating.match.common;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Pre-compiled Lua scripts for atomic Redis operations.
 */
@Component
public class RedisScripts {

    /**
     * Atomically GET the current value of a key and SET it to 0.
     * Used by quota reset and write coalescing patterns.
     */
    public DefaultRedisScript<Long> getAndSetZero() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("local v = redis.call('GET', KEYS[1]); redis.call('SET', KEYS[1], 0); return v or 0;");
        script.setResultType(Long.class);
        return script;
    }

    /** GET value as integer, returning 0 if key doesn't exist. */
    public static final String GET_OR_ZERO =
            "local v = redis.call('GET', KEYS[1]); return v or 0;";

    /**
     * HINCRBY and check limit. Returns 1 if within limit, 0 if exceeded.
     * KEYS[1] = hash key, ARGV[1] = field, ARGV[2] = limit
     */
    public static final String HINCRBY_CHECK =
            "local v = redis.call('HINCRBY', KEYS[1], ARGV[1], 1); " +
            "if tonumber(v) > tonumber(ARGV[2]) then " +
            "  redis.call('HINCRBY', KEYS[1], ARGV[1], -1); " +
            "  return 0; " +
            "end; " +
            "return 1;";
}
