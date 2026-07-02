package com.dating.match.service;

import com.dating.match.constant.MatchSourceEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * DH delayed match service: schedules match creation 15s-2min after a BH right-swipes a DH.
 *
 * Why delayed: instant DH response reveals bot nature. The delay makes it feel natural.
 * Why in-memory TaskScheduler: 15s-2min window is too short to justify a PG table + cron worker.
 * Trade-off: in-flight tasks lost on restart (acceptable per PRD §5.2).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DhDelayedMatchService {

    private final TaskScheduler taskScheduler;
    private final MatchService matchService;

    private static final long MIN_DELAY_MS = 15_000;  // 15 seconds
    private static final long MAX_DELAY_MS = 120_001; // 2 minutes

    /**
     * Schedule a delayed match between a BH user and a DH.
     * After a random delay (15s-2min, uniform distribution), calls matchService.createMatch.
     *
     * @param userId the BH user who right-swiped
     * @param dhId   the DH target
     */
    public void scheduleDelayedMatch(long userId, long dhId) {
        long delayMs = ThreadLocalRandom.current().nextLong(MIN_DELAY_MS, MAX_DELAY_MS);
        Instant executeAt = Instant.now().plusMillis(delayMs);

        taskScheduler.schedule(() -> {
            try {
                log.info("Executing delayed DH match: bhUser={}, dhUser={}, delayMs={}", userId, dhId, delayMs);
                matchService.createMatch(userId, dhId, MatchSourceEnum.SWIPE_MATCH);
            } catch (Exception e) {
                log.error("Delayed DH match failed: bhUser={}, dhUser={}", userId, dhId, e);
                // Swallowed per PRD: lost delayed matches are acceptable
            }
        }, executeAt);

        log.debug("Scheduled delayed DH match: bhUser={}, dhUser={}, executeAt={}", userId, dhId, executeAt);
    }
}
