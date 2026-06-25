package com.dating.post.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Post statistics (flushed portion only).
 * Real-time count = DB base + Redis delta.
 */
@Data
@TableName("post_stats")
public class PostStat {

    @TableId
    private Long postId;

    private Integer likeCount;

    private Integer commentCount;

    private LocalDateTime updatedAt;
}
