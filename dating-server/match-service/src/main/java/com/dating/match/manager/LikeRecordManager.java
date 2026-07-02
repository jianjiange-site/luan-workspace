package com.dating.match.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dating.match.entity.LikeRecord;
import com.dating.match.mapper.LikeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Manager for like_record table. Single-table CRUD only (redline #1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LikeRecordManager {

    private final LikeRecordMapper mapper;

    /**
     * UPSERT a like record.
     * ON CONFLICT (from_user_id, to_user_id) DO UPDATE.
     * Returns the record (inserted or updated).
     */
    public void upsert(LikeRecord record) {
        LikeRecord existing = findByPair(record.getFromUserId(), record.getToUserId());
        if (existing != null) {
            record.setId(existing.getId());
            record.setCreatedAt(existing.getCreatedAt());
            mapper.updateById(record);
        } else {
            mapper.insert(record);
        }
    }

    /**
     * Find a like record by (from, to) pair.
     */
    public LikeRecord findByPair(long fromUserId, long toUserId) {
        return mapper.selectOne(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getFromUserId, fromUserId)
                .eq(LikeRecord::getToUserId, toUserId));
    }

    /**
     * Delete like records between a pair (both directions).
     * Called when a match is created to clean up "secret crush" state.
     */
    public void deleteBothDirections(long userIdA, long userIdB) {
        mapper.delete(new LambdaQueryWrapper<LikeRecord>()
                .and(w -> w
                        .and(w2 -> w2.eq(LikeRecord::getFromUserId, userIdA).eq(LikeRecord::getToUserId, userIdB))
                        .or(w2 -> w2.eq(LikeRecord::getFromUserId, userIdB).eq(LikeRecord::getToUserId, userIdA))));
    }

    /**
     * List who liked me, paginated by liked_at DESC.
     */
    public List<LikeRecord> listByToUser(long toUserId, int offset, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getToUserId, toUserId)
                .orderByDesc(LikeRecord::getLikedAt)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }

    /**
     * Count DH likes received by a user in the last 24 hours.
     */
    public long countDhLikes24h(long toUserId) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(24);
        return mapper.selectCount(new LambdaQueryWrapper<LikeRecord>()
                .eq(LikeRecord::getToUserId, toUserId)
                .eq(LikeRecord::getFromUserType, 2) // DH
                .ge(LikeRecord::getLikedAt, since));
    }

    /**
     * Get all from_user_ids that have liked or been liked by a given user.
     * Used to build exclude lists for DH candidate selection.
     */
    public List<Long> findRelatedUserIds(long userId) {
        List<LikeRecord> records = mapper.selectList(new LambdaQueryWrapper<LikeRecord>()
                .and(w -> w.eq(LikeRecord::getFromUserId, userId).or().eq(LikeRecord::getToUserId, userId)));
        return records.stream()
                .map(r -> r.getFromUserId().equals(userId) ? r.getToUserId() : r.getFromUserId())
                .distinct()
                .toList();
    }
}
