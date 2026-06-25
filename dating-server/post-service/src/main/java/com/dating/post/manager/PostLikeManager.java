package com.dating.post.manager;

import com.dating.post.mapper.PostLikeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Like upsert manager. Delegates to PostLikeMapper.xml for the ON CONFLICT DO UPDATE SQL.
 * Returns affected rows: 0 = idempotent (already target state), 1 = state changed.
 */
@Slf4j
@Component
public class PostLikeManager {

    private final PostLikeMapper postLikeMapper;

    public PostLikeManager(PostLikeMapper postLikeMapper) {
        this.postLikeMapper = postLikeMapper;
    }

    /**
     * Upsert like status for a (userId, postId) pair.
     *
     * @param userId user performing the action
     * @param postId target post
     * @param status 1 = liked, 0 = unliked
     * @return 1 if state actually changed, 0 if already the target state (idempotent)
     */
    public int upsert(Long userId, Long postId, Integer status) {
        return postLikeMapper.upsert(userId, postId, status);
    }
}
