package com.dating.post.common;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Pre-compiled Lua scripts for atomic Redis operations.
 */
@Component
public class RedisScripts {

    /**
     * Atomically GET the current value of a key and SET it to 0.
     * Returns the old value. Used by LikeFlushJob / CommentFlushJob for write coalescing.
     *
     * Without Lua: GET → network gap → SET 0 could overwrite a concurrent INCR.
     * With Lua: the entire script runs atomically, no command interleaving possible.
     */
    public DefaultRedisScript<Long> getAndSetZero() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("local v = redis.call('GET', KEYS[1]); redis.call('SET', KEYS[1], 0); return v or 0;");
        script.setResultType(Long.class);
        return script;
    }

    /**
     * GET value as integer, returning 0 if key doesn't exist.
     */
    public static final String GET_OR_ZERO =
            "local v = redis.call('GET', KEYS[1]); return v or 0;";
}
