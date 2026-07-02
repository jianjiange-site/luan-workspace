package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.mapper.UserSwipeHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for user_swipe_history table. Single-table CRUD only (redline #1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SwipeHistoryManager {

    private final UserSwipeHistoryMapper mapper;

    /**
     * Check if a (user, target) swipe record already exists.
     */
    public boolean exists(long userId, long targetUserId) {
        return mapper.selectCount(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .eq(UserSwipeHistory::getTargetUserId, targetUserId)) > 0;
    }

    /**
     * Get the last swipe result for a (user, target) pair, or null if none.
     */
    public UserSwipeHistory findByUserAndTarget(long userId, long targetUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .eq(UserSwipeHistory::getTargetUserId, targetUserId));
    }

    /**
     * Insert a new swipe history record.
     */
    public void insert(UserSwipeHistory record) {
        mapper.insert(record);
    }

    /**
     * Check if target has right-swiped or super-hi'd the given user.
     * Used to detect mutual interest (BH↔BH instant match).
     */
    public boolean hasTargetLikedUser(long targetUserId, long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, targetUserId)
                .eq(UserSwipeHistory::getTargetUserId, userId)
                .in(UserSwipeHistory::getDirection, List.of(2, 3)) // RIGHT or SUPER_HI
        ) > 0;
    }

    /**
     * Get all target_user_ids that a user has ever swiped on.
     * Used by recall stage to exclude already-seen cards.
     */
    public List<Long> findAllSwipedTargetIds(long userId) {
        return mapper.selectList(new LambdaQueryWrapper<UserSwipeHistory>()
                        .select(UserSwipeHistory::getTargetUserId)
                        .eq(UserSwipeHistory::getUserId, userId))
                .stream()
                .map(UserSwipeHistory::getTargetUserId)
                .toList();
    }

    /**
     * Check if user had any swipe activity yesterday.
     * Used to determine D1 eligibility.
     */
    public boolean hadActivityYesterday(long userId, OffsetDateTime yesterdayStart, OffsetDateTime todayStart) {
        return mapper.selectCount(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .ge(UserSwipeHistory::getSwipedAt, yesterdayStart)
                .lt(UserSwipeHistory::getSwipedAt, todayStart)) > 0;
    }

    /**
     * Get right-swipe history for the last N days (for preference modeling).
     * Returns target_user_ids with direction=RIGHT.
     */
    public List<UserSwipeHistory> findRightSwipesSince(long userId, OffsetDateTime since) {
        return mapper.selectList(new LambdaQueryWrapper<UserSwipeHistory>()
                .eq(UserSwipeHistory::getUserId, userId)
                .eq(UserSwipeHistory::getDirection, 2) // RIGHT only
                .ge(UserSwipeHistory::getSwipedAt, since));
    }
}
