package com.dating.match.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralized Redis key factory for match-service.
 * Pattern: luan:match:<domain>:<id>
 *
 * Full key table documented in match-service-prd-tech.md §7.3.
 */
@Component
public class CacheKeyBuilder {

    private final String prefix;

    public CacheKeyBuilder(@Value("${app.cache.key-prefix:luan}") String prefix) {
        this.prefix = prefix;
    }

    /** Daily quota HASH for a user on a specific date (yyyyMMdd) */
    public String quota(long userId, String date) {
        return prefix + ":match:quota:" + userId + ":" + date;
    }

    /** Feed LIST for a user */
    public String feed(long userId) {
        return prefix + ":match:feed:" + userId;
    }

    /** Swiped SET for a user (all target_user_ids ever swiped) */
    public String swiped(long userId) {
        return prefix + ":match:swiped:" + userId;
    }

    /** User preference cache HASH */
    public String preference(long userId) {
        return prefix + ":match:pref:" + userId;
    }

    /** D1 cron distributed lock per date */
    public String lockD1(String date) {
        return prefix + ":lock:match:d1:" + date;
    }

    /** Swipe serialization lock per (user, target) */
    public String lockSwipe(long userId, long targetId) {
        return prefix + ":lock:match:swipe:" + userId + ":" + targetId;
    }

    /** DH plan ONLINE cursor */
    public String dhPlanCursorOnline() {
        return prefix + ":match:dh_plan:cursor:online";
    }

    /** DH plan OFFLINE cursor */
    public String dhPlanCursorOffline() {
        return prefix + ":match:dh_plan:cursor:offline";
    }

    /** DH plan cooldown for a user */
    public String dhPlanCooldown(long userId) {
        return prefix + ":match:dh_plan:cooldown:" + userId;
    }

    /** DH plan last scene for a user */
    public String dhPlanLastScene(long userId) {
        return prefix + ":match:dh_plan:last_scene:" + userId;
    }

    /** OnlinePlanGenerator distributed lock */
    public String lockDhPlanOnlineSweep() {
        return prefix + ":lock:match:dh_plan:online_sweep";
    }

    /** OfflinePlanGenerator distributed lock */
    public String lockDhPlanOfflineSweep() {
        return prefix + ":lock:match:dh_plan:offline_sweep";
    }

    /** LikeVisitorTaskExecutor distributed lock */
    public String lockDhPlanExecutor() {
        return prefix + ":lock:match:dh_plan:executor";
    }

    /** Generic lock key */
    public String lock(String resource) {
        return prefix + ":lock:match:" + resource;
    }

    /** 24h DH like count key for rate limiting */
    public String dhLikeCount24h(long userId) {
        return prefix + ":match:dh_plan:like_count_24h:" + userId;
    }

    /** 24h DH visit count key for rate limiting */
    public String dhVisitCount24h(long userId) {
        return prefix + ":match:dh_plan:visit_count_24h:" + userId;
    }
}
