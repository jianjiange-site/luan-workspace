package com.dating.post.service;

import com.dating.post.common.CacheKeyBuilder;
import com.dating.post.config.SnowflakeIdGenerator;
import com.dating.post.constant.ErrorCode;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostComment;
import com.dating.post.exception.BizException;
import com.dating.post.manager.PostCommentManager;
import com.dating.post.manager.PostManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Comment operations: create, list (cursor pagination), delete.
 * <p>
 * Write path: 1. INSERT post_comments → 2. ZADD + trim comments ZSet → 3. INCR Redis delta → 4. SADD updated_set.
 * Read path: ZSet first (latest 200 window) → DB fallback for cold posts or beyond 200.
 * <p>
 * Comment counts follow write coalescing: real-time = DB base + Redis delta.
 */
@Slf4j
@Service
public class CommentService {

    private static final int MAX_CONTENT_LENGTH = 512;
    private static final Duration INCR_TTL = Duration.ofDays(7);

    private final PostCommentManager commentManager;
    private final PostManager postManager;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;

    public CommentService(PostCommentManager commentManager,
                          PostManager postManager,
                          SnowflakeIdGenerator idGenerator,
                          StringRedisTemplate redis,
                          CacheKeyBuilder keyBuilder) {
        this.commentManager = commentManager;
        this.postManager = postManager;
        this.idGenerator = idGenerator;
        this.redis = redis;
        this.keyBuilder = keyBuilder;
    }

    /**
     * Create a top-level comment on a post.
     *
     * @param userId  comment author
     * @param postId  target post
     * @param content comment text (1-512 chars)
     * @return the generated comment_id
     * @throws BizException 4005 post not found, 4007/4008 content validation
     */
    public long createComment(long userId, long postId, String content,
                              long rootId, long parentId, long replyToUserId) {
        // 1. Validate
        validateContent(content);
        Post post = postManager.findByPostId(postId);
        if (post == null) {
            throw new BizException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId);
        }

        // 2. Generate comment_id + insert
        long commentId = idGenerator.nextId();
        PostComment comment = buildComment(commentId, postId, userId, content, rootId, parentId, replyToUserId);
        commentManager.insert(comment);

        // 3. Redis ZSet: add to latest 200 window
        String zsetKey = keyBuilder.postComments(postId);
        commentManager.addToZSet(zsetKey, commentId);

        // 4. Redis delta increment + mark updated_set
        String incrKey = keyBuilder.statIncrComments(postId);
        redis.opsForValue().increment(incrKey, 1);
        redis.expire(incrKey, INCR_TTL);
        redis.opsForSet().add(keyBuilder.postUpdatedSet(), String.valueOf(postId));

        log.info("Comment created: commentId={} postId={} userId={}", commentId, postId, userId);
        return commentId;
    }

    /**
     * List comments for a post, cursor pagination.
     * ZSet first (latest 200), DB fallback for beyond-window or cold posts.
     *
     * @param postId   target post
     * @param pageSize items per page
     * @param cursor   comment_id cursor, 0 = first page
     * @return list of CommentInfo + next_cursor + has_more
     */
    public CommentListResult listComments(long postId, int pageSize, long cursor) {
        if (pageSize <= 0 || pageSize > 50) {
            pageSize = 10;
        }

        // 1. Try ZSet first
        String zsetKey = keyBuilder.postComments(postId);
        List<Long> commentIds = commentManager.getCommentIdsFromZSet(zsetKey, cursor, pageSize + 1);

        boolean hasMore;
        long nextCursor;
        List<PostComment> comments;

        if (commentIds.size() >= pageSize + 1) {
            // ZSet has enough data
            commentIds = commentIds.subList(0, pageSize);
            comments = commentIds.stream()
                    .map(commentManager::findByCommentId)
                    .filter(c -> c != null)
                    .collect(Collectors.toList());
            hasMore = true;
            nextCursor = comments.isEmpty() ? 0 : comments.get(comments.size() - 1).getCommentId();
        } else if (!commentIds.isEmpty()) {
            // ZSet returned some but less than pageSize — use what we have
            comments = commentIds.stream()
                    .map(commentManager::findByCommentId)
                    .filter(c -> c != null)
                    .collect(Collectors.toList());
            hasMore = false;
            nextCursor = 0;
        } else {
            // 2. DB fallback: ZSet empty or cursor beyond window
            comments = commentManager.listFromDb(postId, cursor, pageSize + 1);
            hasMore = comments.size() > pageSize;
            if (hasMore) {
                comments = comments.subList(0, pageSize);
            }
            nextCursor = comments.isEmpty() ? 0 : comments.get(comments.size() - 1).getCommentId();
        }

        List<CommentInfo> items = comments.stream()
                .map(c -> new CommentInfo(
                        c.getCommentId(), c.getPostId(), c.getUserId(),
                        c.getRootId(), c.getParentId(), c.getReplyToUserId(),
                        c.getContent(), c.getStatus(),
                        c.getCreatedAt() != null ? c.getCreatedAt().toEpochSecond(java.time.ZoneOffset.UTC) : 0))
                .toList();

        return new CommentListResult(items, nextCursor, hasMore);
    }

    /**
     * Delete a comment. Only the comment author can delete.
     *
     * @param commentId the comment to delete
     * @param userId    requesting user (must be comment author)
     * @throws BizException 4006 not found, 4030 permission denied
     */
    public void deleteComment(long commentId, long userId) {
        PostComment comment = commentManager.findByCommentId(commentId);
        if (comment == null) {
            throw new BizException(ErrorCode.COMMENT_NOT_FOUND, "Comment not found: " + commentId);
        }
        if (!comment.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.PERMISSION_DENIED, "Not the comment author");
        }

        // Soft delete
        commentManager.softDelete(commentId);

        // Remove from ZSet
        String zsetKey = keyBuilder.postComments(comment.getPostId());
        commentManager.removeFromZSet(zsetKey, commentId);

        // DECR delta + mark updated
        String incrKey = keyBuilder.statIncrComments(comment.getPostId());
        redis.opsForValue().decrement(incrKey);
        redis.expire(incrKey, INCR_TTL);
        redis.opsForSet().add(keyBuilder.postUpdatedSet(), String.valueOf(comment.getPostId()));

        log.info("Comment deleted: commentId={} userId={}", commentId, userId);
    }

    // ─── Private helpers ───

    private void validateContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new BizException(ErrorCode.COMMENT_CONTENT_EMPTY, "Comment content must not be empty");
        }
        if (content.trim().length() > MAX_CONTENT_LENGTH) {
            throw new BizException(ErrorCode.COMMENT_CONTENT_TOO_LONG,
                    "Comment too long: " + content.trim().length() + " > " + MAX_CONTENT_LENGTH);
        }
    }

    private PostComment buildComment(long commentId, long postId, long userId, String content,
                                     long rootId, long parentId, long replyToUserId) {
        PostComment c = new PostComment();
        c.setCommentId(commentId);
        c.setPostId(postId);
        c.setUserId(userId);
        c.setContent(content.trim());
        c.setRootId(rootId);
        c.setParentId(parentId);
        c.setReplyToUserId(replyToUserId);
        c.setStatus(1);
        c.setDeleted(0);
        return c;
    }

    // ─── DTOs ───

    public record CommentInfo(
            long commentId, long postId, long userId,
            long rootId, long parentId, long replyToUserId,
            String content, int status, long createdAt
    ) {}

    public record CommentListResult(
            List<CommentInfo> items,
            long nextCursor,
            boolean hasMore
    ) {}
}
