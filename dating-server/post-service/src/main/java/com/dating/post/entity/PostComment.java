package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Comment entity. Business key is comment_id (snowflake).
 * root_id/parent_id/reply_to_user_id are 0 for top-level comments,
 * reserving fields for future 楼中楼 (nested replies).
 */
@Data
@TableName("post_comments")
public class PostComment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long commentId;

    private Long postId;

    private Long userId;

    private Long rootId;

    private Long parentId;

    private Long replyToUserId;

    private String content;

    private Integer status;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;
}
