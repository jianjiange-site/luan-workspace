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
 * OnlinePlanGenerator: generates DH like/visit tasks for users who are currently online.
 * Runs every 1 minute, scanning new OpenIM sessions since last cursor.
 *
 * Intent: while user is in-app, drip-feed DH interactions every ~2 hours
 * to create a sense of being noticed.
 *
 * Filters:
 * 1. Cooldown: skip if user was processed within last 2 hours
 * 2. Task dedup: skip if user already has pending ONLINE tasks
 * 3. Type gate: only process BH users (DH don't receive interactions)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlinePlanGenerator {

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

    @Value("${app.match.dh-plan.online-count-range:[5,10]}")
    private String onlineCountRange; // Parsed as [5,10]

    @Value("${app.match.dh-plan.online-cooldown-seconds:7200}")
    private int cooldownSeconds;

    @Value("${app.match.dh-plan.online-execute-window-min:30}")
    private int executeWindowMin;

    @Value("${app.match.dh-plan.visit-ratio:0.6}")
    private double visitRatio;

    @Value("${app.match.dh-plan.daily-dh-like-cap:15}")
    private int dailyDhLikeCap;

    @Value("${app.match.dh-plan.daily-dh-visit-cap:25}")
    private int dailyDhVisitCap;

    private static final int MAX_LOOKBACK_MS = 30 * 60_000; // 30 minutes
    private static final int BATCH_SIZE = 5000;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "match.onlinePlanGenerator", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void run() {
        RLock lock = redissonClient.getLock(keyBuilder.lockDhPlanOnlineSweep());
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
        long cursor = getCursor();
        long since = Math.max(cursor, now - MAX_LOOKBACK_MS);
        long until = now;

        // Guard: if cursor is too old, reset with alarm
        if (cursor < now - MAX_LOOKBACK_MS) {
            log.warn("Online cursor too old ({}), resetting to now-1min. Possible scheduler stall.", cursor);
            setCursor(now - 60_000);
            return;
        }

        // Fetch online BH user IDs from im-service
        List<Long> onlineUserIds = imClient.listOnlineUserIds(since, until, BATCH_SIZE);
        if (onlineUserIds.isEmpty()) {
            setCursor(now);
            return;
        }

        int generated = 0;
        for (Long userId : onlineUserIds) {
            try {
                if (shouldSkip(userId)) continue;
                List<DhInteractionTask> tasks = generateTasks(userId, 1); // scene=ONLINE
                if (!tasks.isEmpty()) {
                    taskManager.batchInsert(tasks);
                    // Set cooldown
                    redis.opsForValue().set(keyBuilder.dhPlanCooldown(userId), "1",
                            cooldownSeconds, TimeUnit.SECONDS);
                    // Update last scene
                    redis.opsForValue().set(keyBuilder.dhPlanLastScene(userId), "ONLINE");
                    generated++;
                }
            } catch (Exception e) {
                log.error("OnlinePlanGenerator failed for user {}", userId, e);
            }
        }

        setCursor(now);
        log.info("OnlinePlanGenerator: scanned {} online users, generated tasks for {} users",
                onlineUserIds.size(), generated);
    }

    /**
     * Check if user should be skipped (cooldown, dedup, type gate, 24h caps).
     */
    private boolean shouldSkip(long userId) {
        // Cooldown check
        if (Boolean.TRUE.equals(redis.hasKey(keyBuilder.dhPlanCooldown(userId)))) {
            return true;
        }
        // Task table dedup
        if (taskManager.existsPendingTask(userId, 1)) { // scene=ONLINE
            return true;
        }
        // 24h DH like/visit caps
        if (likeRecordManager.countDhLikes24h(userId) >= dailyDhLikeCap
                && visitRecordManager.countDhVisits24h(userId) >= dailyDhVisitCap) {
            return true;
        }
        return false;
    }

    /**
     * Generate DH interaction tasks for a user.
     * Picks random DH candidates and distributes them as 60% VISIT / 40% LIKE.
     */
    private List<DhInteractionTask> generateTasks(long userId, int scene) {
        int minCount = 5, maxCount = 10; // Parse from config in production
        int count = ThreadLocalRandom.current().nextInt(minCount, maxCount + 1);

        // Build exclude list: already liked/visited/matched DH users
        List<Long> excludeUserIds = new ArrayList<>();
        excludeUserIds.addAll(likeRecordManager.findRelatedUserIds(userId));
        excludeUserIds.addAll(visitRecordManager.findRelatedUserIds(userId));
        excludeUserIds.addAll(matchManager.findAllMatchedUserIds(userId));

        int targetGender = userClient.oppositeGender(userId);
        List<DhCandidate> dhCandidates = userClient.listDhCandidates(
                targetGender, 18, 99, 0, 100, List.of(), excludeUserIds, count);

        if (dhCandidates.isEmpty()) {
            return List.of();
        }

        List<DhInteractionTask> tasks = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (DhCandidate dh : dhCandidates) {
            // Random action: VISIT (60%) or LIKE (40%)
            boolean isVisit = ThreadLocalRandom.current().nextDouble() < visitRatio;

            DhInteractionTask task = new DhInteractionTask();
            task.setId(idGenerator.nextId());
            task.setFromUserId(dh.userId());
            task.setToUserId(userId);
            task.setAction(isVisit ? 2 : 1);
            task.setScene(scene);
            // Random execute_time within [now, now + executeWindowMin]
            long delayMs = ThreadLocalRandom.current().nextLong(0, executeWindowMin * 60_000L);
            task.setExecuteTime(OffsetDateTime.now().plusNanos(delayMs * 1_000_000));
            if (!isVisit) {
                task.setLikeContent("Your smile caught my eye"); // STUB: pick from Nacos templates
            }
            task.setCreatedAt(OffsetDateTime.now());
            tasks.add(task);
        }

        return tasks;
    }

    private long getCursor() {
        String val = redis.opsForValue().get(keyBuilder.dhPlanCursorOnline());
        if (val == null) {
            long init = System.currentTimeMillis() - 60_000;
            setCursor(init);
            return init;
        }
        return Long.parseLong(val);
    }

    private void setCursor(long value) {
        redis.opsForValue().set(keyBuilder.dhPlanCursorOnline(), String.valueOf(value));
    }
}
