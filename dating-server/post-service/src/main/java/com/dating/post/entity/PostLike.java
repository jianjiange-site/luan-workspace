package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Like idempotent record. Composite PK: (user_id, post_id).
 * No auto-increment id — PK itself prevents duplicates.
 * Status update (not DELETE) on unlike so same row is reused.
 */
@Data
@TableName("post_likes")
public class PostLike {

    private Long userId;

    private Long postId;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
