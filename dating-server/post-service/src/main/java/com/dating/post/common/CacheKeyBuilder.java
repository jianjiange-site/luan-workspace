package com.dating.post.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralized Redis key factory. All keys use the configured isolation prefix (luan).
 * Pattern: luan:<domain>:<id>
 *
 * Full key table documented in post-service-design.md §6.1.
 */
@Component
public class CacheKeyBuilder {

    private final String prefix;

    public CacheKeyBuilder(@Value("${app.cache.key-prefix:luan}") String prefix) {
        this.prefix = prefix;
    }

    /** Post detail cache: Hash {content, imageKeys, createdAt} */
    public String postDetail(long postId) {
        return prefix + ":post:detail:" + postId;
    }

    /** Like delta counter (unflushed increment) */
    public String statIncrLikes(long postId) {
        return prefix + ":post:stat:incr:" + postId + ":likes";
    }

    /** Comment delta counter (unflushed increment) */
    public String statIncrComments(long postId) {
        return prefix + ":post:stat:incr:" + postId + ":comments";
    }

    /** Set of postIds with unflushed delta (picked up by LikeFlushJob/CommentFlushJob) */
    public String postUpdatedSet() {
        return prefix + ":post:updated_set";
    }

    /** Comments ZSet for a post (score = comment_id, top 200) */
    public String postComments(long postId) {
        return prefix + ":post:comments:" + postId;
    }

    /** Per-user friend timeline ZSet (score = epoch seconds, max 100) */
    public String userTimeline(long userId) {
        return prefix + ":user:timeline:" + userId;
    }

    /** Recommend pool: posts made by male users, seen by female users */
    public String feedPoolRecommendMale() {
        return prefix + ":feed:pool:recommend:male";
    }

    /** Recommend pool: posts made by female users, seen by male users */
    public String feedPoolRecommendFemale() {
        return prefix + ":feed:pool:recommend:female";
    }

    /** Recommend pool tmp key (shadow write before atomic RENAME) */
    public String feedPoolRecommendMaleTmp() {
        return prefix + ":feed:pool:recommend:male:tmp";
    }

    /** Recommend pool tmp key */
    public String feedPoolRecommendFemaleTmp() {
        return prefix + ":feed:pool:recommend:female:tmp";
    }

    /** Cold-start pool: posts made by male users */
    public String feedColdStartMale() {
        return prefix + ":feed:cold_start:pool:male";
    }

    /** Cold-start pool: posts made by female users */
    public String feedColdStartFemale() {
        return prefix + ":feed:cold_start:pool:female";
    }

    /** Per-user Bloom filter for read dedup */
    public String userReadBloom(long userId) {
        return prefix + ":user:read:bloom:" + userId;
    }

    /** Distributed lock key prefix */
    public String lockPost(String resource) {
        return prefix + ":lock:post:" + resource;
    }
}
