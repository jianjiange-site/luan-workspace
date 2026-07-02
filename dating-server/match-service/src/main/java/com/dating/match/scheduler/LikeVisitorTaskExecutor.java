package com.dating.match.scheduler;

import com.dating.match.common.CacheKeyBuilder;
import com.dating.match.config.SnowflakeIdGenerator;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.entity.LikeRecord;
import com.dating.match.entity.VisitRecord;
import com.dating.match.manager.DhInteractionTaskManager;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.VisitRecordManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * LikeVisitorTaskExecutor: executes due DH interaction tasks.
 * Runs every 1 minute.
 *
 * For each due task:
 * - action=LIKE → UPSERT like_record (from DH, to BH)
 * - action=VISIT → UPSERT visit_record (from DH, to BH)
 * - On success → hard-delete the task row
 * - On failure → keep the task, retry next cycle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeVisitorTaskExecutor {

    private final DhInteractionTaskManager taskManager;
    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final SnowflakeIdGenerator idGenerator;
    private final RedissonClient redissonClient;
    private final CacheKeyBuilder keyBuilder;

    private static final int BATCH_SIZE = 1000;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "match.likeVisitorTaskExecutor", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void run() {
        RLock lock = redissonClient.getLock(keyBuilder.lockDhPlanExecutor());
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
        List<DhInteractionTask> tasks = taskManager.findDueForExecution(BATCH_SIZE);
        if (tasks.isEmpty()) return;

        int success = 0, failed = 0;
        for (DhInteractionTask task : tasks) {
            try {
                executeTask(task);
                taskManager.deleteById(task.getId());
                success++;
            } catch (Exception e) {
                log.error("Task execution failed: id={}, action={}, from={}, to={}",
                        task.getId(), task.getAction(), task.getFromUserId(), task.getToUserId(), e);
                failed++;
            }
        }

        // Backlog monitoring
        long overdue = taskManager.countOverdue();
        if (overdue > 5000) {
            log.warn("DH interaction task backlog: {} overdue tasks (>5k threshold)", overdue);
        }

        log.debug("LikeVisitorTaskExecutor: processed {} tasks ({} ok, {} fail), {} overdue",
                tasks.size(), success, failed, overdue);
    }

    /**
     * Execute a single task in an independent short transaction.
     */
    @Transactional
    protected void executeTask(DhInteractionTask task) {
        if (task.getAction() == 1) {
            // LIKE
            LikeRecord record = new LikeRecord();
            record.setId(idGenerator.nextId());
            record.setFromUserId(task.getFromUserId()); // DH
            record.setToUserId(task.getToUserId());     // BH
            record.setFromUserType(2); // DH
            record.setSource(task.getScene() == 1 ? 2 : 3); // DH_PLAN_ONLINE or DH_PLAN_OFFLINE
            record.setLikeContent(task.getLikeContent());
            record.setLikedAt(OffsetDateTime.now());
            likeRecordManager.upsert(record);
        } else {
            // VISIT
            VisitRecord record = new VisitRecord();
            record.setId(idGenerator.nextId());
            record.setFromUserId(task.getFromUserId()); // DH
            record.setToUserId(task.getToUserId());     // BH
            record.setFromUserType(2); // DH
            record.setSource(task.getScene() == 1 ? 2 : 3);
            record.setVisitedAt(OffsetDateTime.now());
            visitRecordManager.upsert(record);
        }
    }
}
