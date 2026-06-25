package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.entity.PostStat;
import com.dating.post.mapper.PostStatMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Post statistics CRUD. All count writes use delta-based increment (write coalescing pattern).
 */
@Slf4j
@Component
public class PostStatManager {

    private final PostStatMapper postStatMapper;

    public PostStatManager(PostStatMapper postStatMapper) {
        this.postStatMapper = postStatMapper;
    }

    /**
     * Initialize stat row for a new post.
     */
    public int insert(PostStat stat) {
        return postStatMapper.insert(stat);
    }

    /**
     * Find stat row by post_id.
     */
    public PostStat findByPostId(Long postId) {
        return postStatMapper.selectById(postId);
    }

    /**
     * Batch find stat rows by post_ids (single-table, no JOIN).
     */
    public java.util.List<PostStat> findByPostIds(java.util.List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return postStatMapper.selectBatchIds(postIds);
    }

    /**
     * Increment like count by delta. Called by LikeFlushJob after Lua GET+SET 0.
     * Positive delta = new likes, negative = unlikes merged during the flush window.
     */
    public int incrementLikeCount(Long postId, int delta) {
        return postStatMapper.incrementLikeCount(postId, delta);
    }

    /**
     * Increment comment count by delta.
     */
    public int incrementCommentCount(Long postId, int delta) {
        return postStatMapper.incrementCommentCount(postId, delta);
    }
}
