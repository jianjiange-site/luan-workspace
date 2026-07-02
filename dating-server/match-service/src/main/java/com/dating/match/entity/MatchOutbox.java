package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Outbox for async side effects after match creation.
 * Status: PENDING → DONE (success) / DEAD (max retries exceeded).
 */
@Data
@TableName("match_outbox")
public class MatchOutbox {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long matchId;
    /** ENSURE_CONVERSATION | SYSTEM_MSG | DH_OPENING */
    private String action;
    private String payloadJson;
    private Integer attempts;
    private OffsetDateTime nextRetryAt;
    /** PENDING | DONE | DEAD */
    private String status;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @TableLogic
    private Boolean deleted;
}
