package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Swipe history record. One row per (user_id, target_user_id) pair.
 * UNIQUE constraint ensures idempotency.
 */
@Data
@TableName("user_swipe_history")
public class UserSwipeHistory {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long targetUserId;
    /** 1=BH, 2=DH */
    private Integer targetUserType;
    /** 1=LEFT, 2=RIGHT, 3=SUPER_HI */
    private Integer direction;

    private OffsetDateTime swipedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
