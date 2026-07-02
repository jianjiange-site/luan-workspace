package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.match.entity.Match;
import com.dating.match.mapper.MatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for match table. Single-table CRUD only (redline #1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchManager {

    private final MatchMapper mapper;

    /**
     * Insert a match with ON CONFLICT DO NOTHING semantics.
     * Returns the number of rows inserted (1 = new, 0 = already existed).
     */
    public int insertIgnoreConflict(long userIdLow, long userIdHigh, String source) {
        // Try insert; if conflict, return 0
        try {
            Match m = new Match();
            m.setUserIdLow(userIdLow);
            m.setUserIdHigh(userIdHigh);
            m.setMatchedAt(OffsetDateTime.now());
            m.setSource(source);
            return mapper.insert(m);
        } catch (Exception e) {
            // Duplicate key exception — already exists
            log.warn("Duplicate match insert ignored: ({}, {})", userIdLow, userIdHigh);
            return 0;
        }
    }

    /**
     * Find a match by the canonical (low, high) pair.
     */
    public Match findByPair(long userIdLow, long userIdHigh) {
        return mapper.selectOne(new LambdaQueryWrapper<Match>()
                .eq(Match::getUserIdLow, userIdLow)
                .eq(Match::getUserIdHigh, userIdHigh));
    }

    /**
     * List all matches for a user (user can be either low or high).
     */
    public List<Match> listByUser(long userId, int offset, int limit) {
        // Need to check both columns since user could be either low or high.
        // We use two queries in service layer to assemble; here provide helpers.
        return mapper.selectList(new LambdaQueryWrapper<Match>()
                .and(w -> w.eq(Match::getUserIdLow, userId).or().eq(Match::getUserIdHigh, userId))
                .orderByDesc(Match::getMatchedAt)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }

    /**
     * Soft-delete a match.
     */
    public void softDelete(long matchId) {
        mapper.update(new LambdaUpdateWrapper<Match>()
                .eq(Match::getId, matchId)
                .set(Match::getDeleted, true));
    }

    /**
     * Get all matched user IDs for a given user.
     * Used for excluding already-matched users from DH candidate selection.
     */
    public List<Long> findAllMatchedUserIds(long userId) {
        List<Match> matches = mapper.selectList(new LambdaQueryWrapper<Match>()
                .and(w -> w.eq(Match::getUserIdLow, userId).or().eq(Match::getUserIdHigh, userId)));
        return matches.stream()
                .map(m -> m.getUserIdLow().equals(userId) ? m.getUserIdHigh() : m.getUserIdLow())
                .toList();
    }
}
