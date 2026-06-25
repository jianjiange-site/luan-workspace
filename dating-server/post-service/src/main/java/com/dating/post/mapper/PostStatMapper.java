package com.dating.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.post.entity.PostStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PostStatMapper extends BaseMapper<PostStat> {

    /**
     * Increment like count by delta. Uses UPDATE += for write coalescing.
     * Called by LikeFlushJob after Lua GET+SET 0 on Redis.
     */
    @Update("UPDATE post_stats SET like_count = like_count + #{delta}, updated_at = NOW() WHERE post_id = #{postId}")
    int incrementLikeCount(@Param("postId") Long postId, @Param("delta") int delta);

    /**
     * Increment comment count by delta.
     */
    @Update("UPDATE post_stats SET comment_count = comment_count + #{delta}, updated_at = NOW() WHERE post_id = #{postId}")
    int incrementCommentCount(@Param("postId") Long postId, @Param("delta") int delta);
}
