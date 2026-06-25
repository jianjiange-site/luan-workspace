package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Post image record. Composite PK: (post_id, sort_order).
 * Stores object storage key only, never a full URL.
 */
@Data
@TableName("post_images")
public class PostImage {

    private Long postId;

    private Integer sortOrder;

    private String imageKey;

    private LocalDateTime createdAt;
}
