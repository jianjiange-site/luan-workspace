package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Visit record: who visited me.
 * UNIQUE (from_user_id, to_user_id) — UPSERT increments visit_count.
 */
@Data
@TableName("visit_record")
public class VisitRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long fromUserId;
    private Long toUserId;
    /** 1=BH, 2=DH */
    private Integer fromUserType;
    /** 1=PROFILE_VIEW, 2=DH_PLAN_ONLINE, 3=DH_PLAN_OFFLINE */
    private Integer source;
    private Integer visitCount;

    private OffsetDateTime visitedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
