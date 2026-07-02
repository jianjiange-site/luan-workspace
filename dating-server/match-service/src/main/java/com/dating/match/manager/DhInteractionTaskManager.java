package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.DhInteractionTask;
import com.dating.match.mapper.DhInteractionTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for dh_interaction_task table. Single-table CRUD only (redline #1).
 * Tasks are hard-deleted after execution (not soft-deleted).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DhInteractionTaskManager {

    private final DhInteractionTaskMapper mapper;

    /**
     * Batch insert task rows.
     */
    public void batchInsert(List<DhInteractionTask> tasks) {
        for (DhInteractionTask task : tasks) {
            mapper.insert(task);
        }
    }

    /**
     * Find tasks due for execution, ordered by execute_time.
     */
    public List<DhInteractionTask> findDueForExecution(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<DhInteractionTask>()
                .le(DhInteractionTask::getExecuteTime, OffsetDateTime.now())
                .orderByAsc(DhInteractionTask::getExecuteTime)
                .last("LIMIT " + limit));
    }

    /**
     * Check if a user already has a pending task for a given scene.
     */
    public boolean existsPendingTask(long toUserId, int scene) {
        return mapper.selectCount(new LambdaQueryWrapper<DhInteractionTask>()
                .eq(DhInteractionTask::getToUserId, toUserId)
                .eq(DhInteractionTask::getScene, scene)) > 0;
    }

    /**
     * Hard-delete a task after successful execution.
     */
    public void deleteById(long id) {
        mapper.deleteById(id);
    }

    /**
     * Count overdue tasks (execute_time + 5min < now) for backlog monitoring.
     */
    public long countOverdue() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(5);
        return mapper.selectCount(new LambdaQueryWrapper<DhInteractionTask>()
                .le(DhInteractionTask::getExecuteTime, threshold));
    }
}
