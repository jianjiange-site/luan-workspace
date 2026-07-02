package com.dating.match.service;

import com.dating.match.config.SnowflakeIdGenerator;
import com.dating.match.constant.*;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.exception.BizException;
import com.dating.match.manager.FeedManager;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.QuotaManager;
import com.dating.match.manager.SwipeHistoryManager;
import com.dating.match.entity.LikeRecord;
import com.dating.match.entity.Match;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

/**
 * Swipe service: handles LEFT_SWIPE, RIGHT_SWIPE, and SUPER_HI actions.
 *
 * Concurrent safety: Redisson lock per (user_id, target_user_id) serializes swipes.
 * Idempotency: second swipe on same (user, target) returns previous result.
 *
 * Quota: card quota consumed for all actions; right-swipe quota for RIGHT and SUPER_HI.
 * SUPER_HI additionally consumes subscription gift or coins.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SwipeService {

    private final SwipeHistoryManager swipeHistoryManager;
    private final LikeRecordManager likeRecordManager;
    private final FeedManager feedManager;
    private final QuotaService quotaService;
    private final MatchService matchService;
    private final DhDelayedMatchService dhDelayedMatchService;
    private final RedissonClient redissonClient;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Execute a swipe action (LEFT or RIGHT) on a target user.
     *
     * @param userId       the swiping user
     * @param targetUserId the target being swiped on
     * @param direction    LEFT or RIGHT (proto ordinal: 1=LEFT, 2=RIGHT)
     * @param targetUserType 1=BH, 2=DH
     * @return swipe result with optional match_id (>0 if match created)
     */
    public SwipeResult swipe(long userId, long targetUserId, int direction, int targetUserType) {
        SwipeDirectionEnum dir = SwipeDirectionEnum.fromProtoOrdinal(direction);

        // Distributed lock for serialization
        RLock lock = redissonClient.getLock("luan:lock:match:swipe:" + userId + ":" + targetUserId);
        try {
            if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
                throw new BizException(ErrorCode.CONCURRENT_SWIPE, "Concurrent swipe detected, please retry");
            }
            return executeSwipe(userId, targetUserId, dir, targetUserType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.CONCURRENT_SWIPE, "Swipe interrupted");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private SwipeResult executeSwipe(long userId, long targetUserId, SwipeDirectionEnum direction, int targetUserType) {
        // Idempotency check
        UserSwipeHistory existing = swipeHistoryManager.findByUserAndTarget(userId, targetUserId);
        if (existing != null) {
            log.debug("Idempotent swipe: ({}, {}) already swiped direction={}",
                    userId, targetUserId, existing.getDirection());
            return new SwipeResult(0, existing.getDirection());
        }

        // Deduct card quota (common to all actions)
        quotaService.deductCard(userId);

        if (direction == SwipeDirectionEnum.LEFT) {
            return handleLeftSwipe(userId, targetUserId, targetUserType);
        } else {
            return handleRightSwipe(userId, targetUserId, targetUserType);
        }
    }

    /**
     * LEFT_SWIPE: only writes history, no like/match.
     */
    @Transactional
    protected SwipeResult handleLeftSwipe(long userId, long targetUserId, int targetUserType) {
        writeSwipeHistory(userId, targetUserId, targetUserType, SwipeDirectionEnum.LEFT);
        feedManager.markSwiped(userId, targetUserId);
        return new SwipeResult(0, SwipeDirectionEnum.LEFT.getCode());
    }

    /**
     * RIGHT_SWIPE: writes history, deducts right-swipe quota.
     * - BH target: check mutual interest → instant match; otherwise write like_record
     * - DH target: write history + schedule delayed match (15s-2min)
     */
    @Transactional
    protected SwipeResult handleRightSwipe(long userId, long targetUserId, int targetUserType) {
        quotaService.deductRightSwipe(userId);
        writeSwipeHistory(userId, targetUserId, targetUserType, SwipeDirectionEnum.RIGHT);
        feedManager.markSwiped(userId, targetUserId);

        if (targetUserType == 1) {
            // BH target — check mutual interest
            return handleRightSwipeBh(userId, targetUserId);
        } else {
            // DH target — schedule delayed match
            return handleRightSwipeDh(userId, targetUserId);
        }
    }

    /**
     * BH↔BH right swipe: check if target already right-swiped me.
     * Yes → instant match (SWIPE_MATCH).
     * No → write like_record (unidirectional "crush").
     */
    private SwipeResult handleRightSwipeBh(long userId, long targetUserId) {
        boolean targetLikedMe = swipeHistoryManager.hasTargetLikedUser(targetUserId, userId);
        if (targetLikedMe) {
            // Mutual interest! Create match instantly
            Match match = matchService.createMatch(userId, targetUserId, MatchSourceEnum.SWIPE_MATCH);
            return new SwipeResult(match.getId(), SwipeDirectionEnum.RIGHT.getCode());
        } else {
            // Unidirectional — write like_record as "crush"
            LikeRecord record = new LikeRecord();
            record.setId(idGenerator.nextId());
            record.setFromUserId(userId);
            record.setToUserId(targetUserId);
            record.setFromUserType(1); // BH
            record.setSource(LikeVisitSourceEnum.SWIPE_RIGHT.getCode());
            record.setLikedAt(OffsetDateTime.now());
            likeRecordManager.upsert(record);
            return new SwipeResult(0, SwipeDirectionEnum.RIGHT.getCode());
        }
    }

    /**
     * BH→DH right swipe: schedule delayed match (15s-2min).
     * No like_record written — DH delay callback creates match directly.
     */
    private SwipeResult handleRightSwipeDh(long userId, long targetUserId) {
        dhDelayedMatchService.scheduleDelayedMatch(userId, targetUserId);
        return new SwipeResult(0, SwipeDirectionEnum.RIGHT.getCode());
    }

    /**
     * SUPER_HI: premium instant match regardless of target type.
     * Consumes card + right-swipe quota + subscription gift or coins.
     * Creates match immediately (no delay even for DH).
     */
    @Transactional
    public SuperHiResult superHi(long userId, long targetUserId, int targetUserType) {
        RLock lock = redissonClient.getLock("luan:lock:match:swipe:" + userId + ":" + targetUserId);
        try {
            if (!lock.tryLock(5, 3, TimeUnit.SECONDS)) {
                throw new BizException(ErrorCode.CONCURRENT_SWIPE, "Concurrent swipe detected");
            }

            // Idempotency check
            UserSwipeHistory existing = swipeHistoryManager.findByUserAndTarget(userId, targetUserId);
            if (existing != null) {
                log.debug("Idempotent SuperHi: ({}, {})", userId, targetUserId);
                return new SuperHiResult(0, 0);
            }

            // Deduct quotas
            quotaService.deductCard(userId);
            quotaService.deductRightSwipe(userId);
            int coinsUsed = quotaService.deductSuperHi(userId);

            // Write swipe history
            writeSwipeHistory(userId, targetUserId, targetUserType, SwipeDirectionEnum.SUPER_HI);
            feedManager.markSwiped(userId, targetUserId);

            // Create match immediately (even for DH — paid user gets instant result)
            Match match = matchService.createMatch(userId, targetUserId, MatchSourceEnum.SWIPE_SUPER_HI);

            // For DH target, immediately trigger DH opening via im-service
            if (targetUserType == 2) {
                matchService.processOutboxAction(createDhOpeningOutbox(match.getId(), targetUserId, userId));
            }

            log.info("SuperHi: userId={}, targetUserId={}, matchId={}, coinsUsed={}",
                    userId, targetUserId, match.getId(), coinsUsed);
            return new SuperHiResult(match.getId(), coinsUsed);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.CONCURRENT_SWIPE, "SuperHi interrupted");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void writeSwipeHistory(long userId, long targetUserId, int targetUserType, SwipeDirectionEnum direction) {
        UserSwipeHistory history = new UserSwipeHistory();
        history.setId(idGenerator.nextId());
        history.setUserId(userId);
        history.setTargetUserId(targetUserId);
        history.setTargetUserType(targetUserType);
        history.setDirection(direction.getCode());
        history.setSwipedAt(OffsetDateTime.now());
        swipeHistoryManager.insert(history);
    }

    private com.dating.match.entity.MatchOutbox createDhOpeningOutbox(long matchId, long dhUserId, long targetUserId) {
        com.dating.match.entity.MatchOutbox outbox = new com.dating.match.entity.MatchOutbox();
        outbox.setId(idGenerator.nextId());
        outbox.setMatchId(matchId);
        outbox.setAction("DH_OPENING");
        outbox.setPayloadJson("{\"dh_user_id\":" + dhUserId + ",\"target_user_id\":" + targetUserId + "}");
        outbox.setAttempts(0);
        outbox.setNextRetryAt(OffsetDateTime.now());
        outbox.setStatus("PENDING");
        return outbox;
    }

    // ── Inner data classes ──

    public record SwipeResult(long matchId, int direction) {}

    public record SuperHiResult(long matchId, int coinsUsed) {}
}
