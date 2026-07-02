package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Like record: who liked me (unidirectional, pre-match state).
 * UNIQUE (from_user_id, to_user_id) — one like per pair, UPSERT on replay.
 */
@Data
@TableName("like_record")
public class LikeRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fromUserId;
    private Long toUserId;
    /** 1=BH, 2=DH */
    private Integer fromUserType;
    /** 1=SWIPE_RIGHT, 2=DH_PLAN_ONLINE, 3=DH_PLAN_OFFLINE */
    private Integer source;
    /** DH task content; NULL for real user swipe */
    private String likeContent;

    private OffsetDateTime likedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
