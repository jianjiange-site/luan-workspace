package com.dating.match.scheduler;

import com.dating.match.client.ImServiceClient;
import com.dating.match.client.UserServiceClient;
import com.dating.match.client.UserServiceClient.DhCandidate;
import com.dating.match.common.CacheKeyBuilder;
import com.dating.match.config.SnowflakeIdGenerator;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.manager.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * OfflinePlanGenerator: generates DH like/visit tasks for users who recently went offline.
 * Runs every 20 minutes.
 *
 * Intent: when user comes back to the app, they see that people liked/visited them
 * while they were away.
 *
 * Filters:
 * 1. lastScene gate: skip if user already got an OFFLINE plan during this offline period
 * 2. Task dedup: skip if user already has pending OFFLINE tasks
 * 3. Type gate: only BH users
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OfflinePlanGenerator {

    private final ImServiceClient imClient;
    private final UserServiceClient userClient;
    private final DhInteractionTaskManager taskManager;
    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final MatchManager matchManager;
    private final SnowflakeIdGenerator idGenerator;
    private final StringRedisTemplate redis;
    private final CacheKeyBuilder keyBuilder;
    private final RedissonClient redissonClient;

    @Value("${app.match.dh-plan.offline-count-range:[3,6]}")
    private String offlineCountRange;

    @Value("${app.match.dh-plan.offline-threshold-seconds:1200}")
    private int offlineThresholdSeconds;

    @Value("${app.match.dh-plan.offline-lookback-seconds:10800}")
    private int offlineLookbackSeconds;

    @Value("${app.match.dh-plan.offline-execute-window-min:30}")
    private int executeWindowMin;

    @Value("${app.match.dh-plan.visit-ratio:0.6}")
    private double visitRatio;

    @Value("${app.match.dh-plan.daily-dh-like-cap:15}")
    private int dailyDhLikeCap;

    @Value("${app.match.dh-plan.daily-dh-visit-cap:25}")
    private int dailyDhVisitCap;

    private static final int BATCH_SIZE = 5000;

    @Scheduled(fixedDelay = 1_200_000) // 20 minutes
    @SchedulerLock(name = "match.offlinePlanGenerator", lockAtMostFor = "PT30M", lockAtLeastFor = "PT10S")
    public void run() {
        RLock lock = redissonClient.getLock(keyBuilder.lockDhPlanOfflineSweep());
        if (!lock.tryLock()) {
            return;
        }
        try {
            execute();
        } finally {
            lock.unlock();
        }
    }

    private void execute() {
        long now = System.currentTimeMillis();
        long offlineThresholdMs = offlineThresholdSeconds * 1000L;
        long maxLookbackMs = offlineLookbackSeconds * 1000L;

        long cursor = getCursor();
        long since = Math.max(cursor, now - maxLookbackMs);
        long until = now - offlineThresholdMs; // Only users offline for ≥ 20min

        if (since >= until) {
            return; // No window to scan
        }

        List<Long> offlineUserIds = imClient.listRecentOfflineUsers(since, until, BATCH_SIZE);
        if (offlineUserIds.isEmpty()) {
            setCursor(now - offlineThresholdMs);
            return;
        }

        int generated = 0;
        for (Long userId : offlineUserIds) {
            try {
                if (shouldSkip(userId)) continue;
                List<DhInteractionTask> tasks = generateTasks(userId, 2); // scene=OFFLINE
                if (!tasks.isEmpty()) {
                    taskManager.batchInsert(tasks);
                    redis.opsForValue().set(keyBuilder.dhPlanLastScene(userId), "OFFLINE");
                    generated++;
                }
            } catch (Exception e) {
                log.error("OfflinePlanGenerator failed for user {}", userId, e);
            }
        }

        setCursor(now - offlineThresholdMs);
        log.info("OfflinePlanGenerator: scanned {} offline users, generated tasks for {} users",
                offlineUserIds.size(), generated);
    }

    private boolean shouldSkip(long userId) {
        // Last scene gate: already processed during this offline period
        String lastScene = redis.opsForValue().get(keyBuilder.dhPlanLastScene(userId));
        if ("OFFLINE".equals(lastScene)) {
            return true;
        }
        // Task table dedup
        if (taskManager.existsPendingTask(userId, 2)) { // scene=OFFLINE
            return true;
        }
        // 24h caps
        if (likeRecordManager.countDhLikes24h(userId) >= dailyDhLikeCap
                && visitRecordManager.countDhVisits24h(userId) >= dailyDhVisitCap) {
            return true;
        }
        return false;
    }

    private List<DhInteractionTask> generateTasks(long userId, int scene) {
        int minCount = 3, maxCount = 6; // Parse from config in production
        int count = ThreadLocalRandom.current().nextInt(minCount, maxCount + 1);

        List<Long> excludeUserIds = new ArrayList<>();
        excludeUserIds.addAll(likeRecordManager.findRelatedUserIds(userId));
        excludeUserIds.addAll(visitRecordManager.findRelatedUserIds(userId));
        excludeUserIds.addAll(matchManager.findAllMatchedUserIds(userId));

        int targetGender = userClient.oppositeGender(userId);
        List<DhCandidate> dhCandidates = userClient.listDhCandidates(
                targetGender, 18, 99, 0, 100, List.of(), excludeUserIds, count);

        if (dhCandidates.isEmpty()) return List.of();

        List<DhInteractionTask> tasks = new ArrayList<>();
        for (DhCandidate dh : dhCandidates) {
            boolean isVisit = ThreadLocalRandom.current().nextDouble() < visitRatio;
            DhInteractionTask task = new DhInteractionTask();
            task.setId(idGenerator.nextId());
            task.setFromUserId(dh.userId());
            task.setToUserId(userId);
            task.setAction(isVisit ? 2 : 1);
            task.setScene(scene);
            long delayMs = ThreadLocalRandom.current().nextLong(0, executeWindowMin * 60_000L);
            task.setExecuteTime(OffsetDateTime.now().plusNanos(delayMs * 1_000_000));
            if (!isVisit) {
                task.setLikeContent("I noticed your profile"); // STUB
            }
            task.setCreatedAt(OffsetDateTime.now());
            tasks.add(task);
        }
        return tasks;
    }

    private long getCursor() {
        String val = redis.opsForValue().get(keyBuilder.dhPlanCursorOffline());
        if (val == null) {
            long init = System.currentTimeMillis() - offlineLookbackSeconds * 1000L;
            setCursor(init);
            return init;
        }
        return Long.parseLong(val);
    }

    private void setCursor(long value) {
        redis.opsForValue().set(keyBuilder.dhPlanCursorOffline(), String.valueOf(value));
    }
}
