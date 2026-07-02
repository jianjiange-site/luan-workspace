package com.dating.match.scheduler;

import com.dating.match.manager.SwipeHistoryManager;
import com.dating.match.recommend.D1Generator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * D1 daily queue generation scheduler.
 * Runs daily at UTC 07:00 (maps to EST 02:00 / EDT 03:00 approximately).
 *
 * For each user who had swipe activity yesterday:
 * 1. Build preference profile from 30-day history
 * 2. Dual-pool recall (DH + BH)
 * 3. Rank + merge
 * 4. DEL old feed + RPUSH new 240 cards to Redis LIST
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class D1QueueScheduler {

    private final D1Generator d1Generator;
    private final SwipeHistoryManager swipeHistoryManager;
    private final RedissonClient redissonClient;

    // STUB: In production, get active user list from user-service
    private static final List<Long> STUB_USER_IDS = List.of(1001L, 1002L, 1003L);

    /**
     * Run D1 queue generation daily at 07:00 UTC.
     * Redisson lock prevents multiple instances from running simultaneously.
     */
    @Scheduled(cron = "0 0 7 * * *", zone = "UTC")
    @SchedulerLock(name = "match.d1QueueGeneration", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    public void run() {
        String today = LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        RLock lock = redissonClient.getLock("luan:lock:match:d1:" + today);
        if (!lock.tryLock()) {
            log.info("D1 queue generation already running on another instance, skipping");
            return;
        }

        try {
            log.info("D1 queue generation started for {}", today);
            OffsetDateTime yesterdayStart = OffsetDateTime.now(ZoneOffset.UTC)
                    .minusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            OffsetDateTime todayStart = yesterdayStart.plusDays(1);

            int generated = 0;
            for (Long userId : STUB_USER_IDS) {
                // Pre-condition: user had swipe activity yesterday
                if (!swipeHistoryManager.hadActivityYesterday(userId, yesterdayStart, todayStart)) {
                    log.debug("User {} had no activity yesterday, skipping D1 generation", userId);
                    continue;
                }

                try {
                    d1Generator.generateForUser(userId);
                    generated++;
                } catch (Exception e) {
                    log.error("D1 generation failed for user {}", userId, e);
                }
            }

            log.info("D1 queue generation completed: {} users generated", generated);
        } finally {
            lock.unlock();
        }
    }
}
