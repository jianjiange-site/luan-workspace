package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Match relationship. user_id_low = min(uid1, uid2), user_id_high = max(uid1, uid2).
 * UNIQUE (user_id_low, user_id_high) prevents duplicate matches.
 */
@Data
@TableName("match")
public class Match {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** min(user1, user2) — canonical ordering */
    private Long userIdLow;
    /** max(user1, user2) — canonical ordering */
    private Long userIdHigh;

    private OffsetDateTime matchedAt;
    /** SWIPE_MATCH | SWIPE_SUPER_HI */
    private String source;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
