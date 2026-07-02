package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.match.entity.VisitRecord;
import com.dating.match.mapper.VisitRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for visit_record table. Single-table CRUD only (redline #1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitRecordManager {

    private final VisitRecordMapper mapper;

    /**
     * UPSERT a visit record, incrementing visit_count on conflict.
     */
    public void upsert(VisitRecord record) {
        VisitRecord existing = findByPair(record.getFromUserId(), record.getToUserId());
        if (existing != null) {
            existing.setVisitCount(existing.getVisitCount() + 1);
            existing.setVisitedAt(OffsetDateTime.now());
            existing.setSource(record.getSource());
            mapper.updateById(existing);
        } else {
            record.setVisitCount(1);
            mapper.insert(record);
        }
    }

    /**
     * Find a visit record by (from, to) pair.
     */
    public VisitRecord findByPair(long fromUserId, long toUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<VisitRecord>()
                .eq(VisitRecord::getFromUserId, fromUserId)
                .eq(VisitRecord::getToUserId, toUserId));
    }

    /**
     * List who visited me, paginated by visited_at DESC.
     */
    public List<VisitRecord> listByToUser(long toUserId, int offset, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<VisitRecord>()
                .eq(VisitRecord::getToUserId, toUserId)
                .orderByDesc(VisitRecord::getVisitedAt)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }

    /**
     * Count DH visits received by a user in the last 24 hours.
     */
    public long countDhVisits24h(long toUserId) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        return mapper.selectCount(new LambdaQueryWrapper<VisitRecord>()
                .eq(VisitRecord::getToUserId, toUserId)
                .eq(VisitRecord::getFromUserType, 2) // DH
                .ge(VisitRecord::getVisitedAt, since));
    }

    /**
     * Get all from_user_ids that have visited or been visited by a given user.
     */
    public List<Long> findRelatedUserIds(long userId) {
        List<VisitRecord> records = mapper.selectList(new LambdaQueryWrapper<VisitRecord>()
                .and(w -> w.eq(VisitRecord::getFromUserId, userId).or().eq(VisitRecord::getToUserId, userId)));
        return records.stream()
                .map(r -> r.getFromUserId().equals(userId) ? r.getToUserId() : r.getFromUserId())
                .distinct()
                .toList();
    }
}
