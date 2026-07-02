package com.dating.match.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * DH interaction task: scheduled DH like/visit jobs.
 * Short-lived rows; executor hard-deletes after execution, not soft-delete.
 */
@Data
@TableName("dh_interaction_task")
public class DhInteractionTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** DH user_id (initiator) */
    private Long fromUserId;
    /** Real user_id (receiver) */
    private Long toUserId;
    /** 1=LIKE, 2=VISIT */
    private Integer action;
    /** 1=ONLINE, 2=OFFLINE */
    private Integer scene;
    /** Scheduled execution time */
    private OffsetDateTime executeTime;
    /** Only for LIKE actions */
    private String likeContent;

    private OffsetDateTime createdAt;
}
