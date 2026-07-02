package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.match.entity.MatchOutbox;
import com.dating.match.mapper.MatchOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for match_outbox table. Single-table CRUD only (redline #1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxManager {

    private final MatchOutboxMapper mapper;

    /**
     * Insert an outbox record for async retry.
     */
    public void insert(MatchOutbox record) {
        mapper.insert(record);
    }

    /**
     * Find pending outbox records that are due for retry.
     */
    public List<MatchOutbox> findPendingAndDue(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<MatchOutbox>()
                .eq(MatchOutbox::getStatus, "PENDING")
                .le(MatchOutbox::getNextRetryAt, OffsetDateTime.now())
                .orderByAsc(MatchOutbox::getNextRetryAt)
                .last("LIMIT " + limit));
    }

    /**
     * Update outbox status and attempts count.
     */
    public void updateStatus(long id, String status, int attempts, OffsetDateTime nextRetryAt) {
        mapper.update(new LambdaUpdateWrapper<MatchOutbox>()
                .eq(MatchOutbox::getId, id)
                .set(MatchOutbox::getStatus, status)
                .set(MatchOutbox::getAttempts, attempts)
                .set(MatchOutbox::getNextRetryAt, nextRetryAt));
    }

    /**
     * Mark outbox record as DONE.
     */
    public void markDone(long id) {
        mapper.update(new LambdaUpdateWrapper<MatchOutbox>()
                .eq(MatchOutbox::getId, id)
                .set(MatchOutbox::getStatus, "DONE"));
    }

    /**
     * Mark outbox record as DEAD (max retries exceeded).
     */
    public void markDead(long id) {
        mapper.update(new LambdaUpdateWrapper<MatchOutbox>()
                .eq(MatchOutbox::getId, id)
                .set(MatchOutbox::getStatus, "DEAD"));
    }
}
