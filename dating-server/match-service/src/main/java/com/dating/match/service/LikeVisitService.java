package com.dating.match.service;

import com.dating.match.client.UserServiceClient;
import com.dating.match.client.UserServiceClient.ProfileData;
import com.dating.match.config.SnowflakeIdGenerator;
import com.dating.match.constant.LikeVisitSourceEnum;
import com.dating.match.entity.LikeRecord;
import com.dating.match.entity.VisitRecord;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.VisitRecordManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Like / Visit service: manages likes-of-me and visits-of-me listings,
 * and async visit recording.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LikeVisitService {

    private final LikeRecordManager likeRecordManager;
    private final VisitRecordManager visitRecordManager;
    private final UserServiceClient userClient;
    private final SnowflakeIdGenerator idGenerator;

    /** Dedicated thread pool for async visit writes */
    private final ExecutorService visitWriteExecutor = Executors.newFixedThreadPool(4);

    /**
     * List who liked me, paginated by liked_at DESC.
     * Profile data is assembled via batch RPC to user-service.
     */
    public LikesResult listLikesOfMe(long userId, int pageSize, int offset) {
        List<LikeRecord> records = likeRecordManager.listByToUser(userId, offset, pageSize);
        if (records.isEmpty()) {
            return new LikesResult(List.of(), false);
        }

        // Batch-get profiles for all likers
        List<Long> likerIds = records.stream().map(LikeRecord::getFromUserId).toList();
        Map<Long, ProfileData> profiles = userClient.batchGetProfile(likerIds);

        List<LikeVO> likes = new ArrayList<>();
        for (LikeRecord r : records) {
            ProfileData p = profiles.get(r.getFromUserId());
            if (p == null) continue;
            likes.add(new LikeVO(
                    r.getFromUserId(),
                    p.nickname(), p.age(), p.photoKeys(),
                    r.getLikedAt().toInstant().toEpochMilli(),
                    r.getLikeContent() != null ? r.getLikeContent() : ""
            ));
        }
        return new LikesResult(likes, records.size() == pageSize);
    }

    /**
     * List who visited me, paginated by visited_at DESC.
     */
    public VisitsResult listVisitsOfMe(long userId, int pageSize, int offset) {
        List<VisitRecord> records = visitRecordManager.listByToUser(userId, offset, pageSize);
        if (records.isEmpty()) {
            return new VisitsResult(List.of(), false);
        }

        List<Long> visitorIds = records.stream().map(VisitRecord::getFromUserId).toList();
        Map<Long, ProfileData> profiles = userClient.batchGetProfile(visitorIds);

        List<VisitVO> visits = new ArrayList<>();
        for (VisitRecord r : records) {
            ProfileData p = profiles.get(r.getFromUserId());
            if (p == null) continue;
            visits.add(new VisitVO(
                    r.getFromUserId(),
                    p.nickname(), p.age(), p.photoKeys(),
                    r.getVisitedAt().toInstant().toEpochMilli(),
                    r.getVisitCount()
            ));
        }
        return new VisitsResult(visits, records.size() == pageSize);
    }

    /**
     * Record a profile visit asynchronously.
     * Self-visit is short-circuited.
     * Write failure only logs WARN (doesn't block caller).
     */
    public void recordVisit(long viewerUserId, long targetUserId) {
        // Self-visit short circuit
        if (viewerUserId == targetUserId) {
            return;
        }

        visitWriteExecutor.submit(() -> {
            try {
                VisitRecord record = new VisitRecord();
                record.setId(idGenerator.nextId());
                record.setFromUserId(viewerUserId);
                record.setToUserId(targetUserId);
                record.setFromUserType(1); // Assume BH visitor
                record.setSource(LikeVisitSourceEnum.PROFILE_VIEW.getCode());
                record.setVisitedAt(OffsetDateTime.now());
                visitRecordManager.upsert(record);
            } catch (Exception e) {
                log.warn("Async visit record write failed: viewer={}, target={}", viewerUserId, targetUserId, e);
            }
        });
    }

    // ── Inner data classes ──

    public record LikesResult(List<LikeVO> likes, boolean hasMore) {}

    public record LikeVO(long fromUserId, String nickname, int age, List<String> photoKeys,
                          long likedAtUnixMs, String likeContent) {}

    public record VisitsResult(List<VisitVO> visits, boolean hasMore) {}

    public record VisitVO(long fromUserId, String nickname, int age, List<String> photoKeys,
                           long visitedAtUnixMs, int visitCount) {}
}
