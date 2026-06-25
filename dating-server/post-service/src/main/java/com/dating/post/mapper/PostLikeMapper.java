package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostLike;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {

    /**
     * Upsert like status. ON CONFLICT DO UPDATE only when status actually changes.
     * Returns affected rows: 0 = idempotent (already target state), 1 = state changed.
     */
    int upsert(@Param("userId") Long userId,
               @Param("postId") Long postId,
               @Param("status") Integer status);
}
