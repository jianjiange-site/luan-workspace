package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.entity.PostComment;
import com.dating.post.mapper.PostCommentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comment CRUD + Redis ZSet operations.
 * ZSet maintains the latest 200 comment_ids per post as a fast read window.
 * Cold-path fallback goes directly to DB via cursor pagination.
 */
@Slf4j
@Component
public class PostCommentManager {

    private static final int ZSET_MAX_SIZE = 200;
    private static final Duration ZSET_TTL = Duration.ofDays(7);

    private final PostCommentMapper commentMapper;
    private final StringRedisTemplate redis;

    public PostCommentManager(PostCommentMapper commentMapper, StringRedisTemplate redis) {
        this.commentMapper = commentMapper;
        this.redis = redis;
    }

    /**
     * Insert a new comment row. Caller must generate comment_id (snowflake).
     */
    public int insert(PostComment comment) {
        return commentMapper.insert(comment);
    }

    /**
     * Find a comment by business primary key (comment_id), excluding soft-deleted.
     */
    public PostComment findByCommentId(Long commentId) {
        LambdaQueryWrapper<PostComment> q = new LambdaQueryWrapper<>();
        q.eq(PostComment::getCommentId, commentId)
         .eq(PostComment::getDeleted, 0);
        return commentMapper.selectOne(q);
    }

    /**
     * Soft-delete a comment.
     */
    public int softDelete(Long commentId) {
        PostComment c = new PostComment();
        c.setDeleted(1);
        LambdaQueryWrapper<PostComment> q = new LambdaQueryWrapper<>();
        q.eq(PostComment::getCommentId, commentId);
        return commentMapper.update(c, q);
    }

    /**
     * Add comment_id to the post's ZSet, trim to 200 newest, set TTL.
     * Score = comment_id (snowflake, roughly time-ordered).
     */
    public void addToZSet(String zsetKey, long commentId) {
        redis.opsForZSet().add(zsetKey, String.valueOf(commentId), commentId);
        Long size = redis.opsForZSet().size(zsetKey);
        if (size != null && size > ZSET_MAX_SIZE) {
            // Remove oldest (lowest score) entries
            redis.opsForZSet().removeRange(zsetKey, 0, size - ZSET_MAX_SIZE - 1);
        }
        redis.expire(zsetKey, ZSET_TTL);
    }

    /**
     * Remove a comment_id from the ZSet (on comment delete).
     */
    public void removeFromZSet(String zsetKey, long commentId) {
        redis.opsForZSet().remove(zsetKey, String.valueOf(commentId));
    }

    /**
     * Get comment_ids from ZSet for cursor pagination.
     * Returns comment_ids in reverse order (newest first), up to pageSize.
     *
     * @param zsetKey  the post's comment ZSet key
     * @param cursor   the last comment_id from previous page, 0 = first page
     * @param pageSize max items to return
     * @return list of comment_id strings, may be empty
     */
    public List<Long> getCommentIdsFromZSet(String zsetKey, long cursor, int pageSize) {
        // ZREVRANGEBYSCORE: from cursor-1 (exclusive) down to -inf, limit 0..pageSize
        double max = cursor > 0 ? cursor - 1 : Double.POSITIVE_INFINITY;
        Set<String> members = redis.opsForZSet()
                .reverseRangeByScore(zsetKey, Double.NEGATIVE_INFINITY, max, 0, pageSize);
        if (members == null || members.isEmpty()) {
            return Collections.emptyList();
        }
        return members.stream().map(Long::parseLong).collect(Collectors.toList());
    }

    /**
     * DB fallback: cursor-based pagination on top-level comments (root_id=0).
     * Used when ZSet is empty (cold post) or cursor has gone past ZSet window (200+).
     */
    public List<PostComment> listFromDb(long postId, long cursor, int pageSize) {
        LambdaQueryWrapper<PostComment> q = new LambdaQueryWrapper<>();
        q.eq(PostComment::getPostId, postId)
         .eq(PostComment::getRootId, 0L)
         .eq(PostComment::getDeleted, 0);
        if (cursor > 0) {
            q.lt(PostComment::getCommentId, cursor);
        }
        q.orderByDesc(PostComment::getCreatedAt);
        q.last("LIMIT " + pageSize);
        return commentMapper.selectList(q);
    }
}
