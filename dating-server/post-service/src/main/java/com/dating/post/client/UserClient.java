package com.dating.post.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * gRPC client wrapper for user-service.
 * <p>
 * Current implementation uses STUBS per design doc §11:
 * - getFriendUserIds returns empty list (write-fanout is no-op until user-service implements friends)
 * - isMale uses userId % 2 == 0 test logic
 * <p>
 * When user-service implements GetFriendList + gender field, swap stub implementations
 * for real gRPC calls via {@code @GrpcClient("user-service") UserServiceBlockingStub}.
 * <p>
 * Caffeine 30s local cache absorbs repeated calls (e.g. same author's posts in FeedScoreJob).
 */
@Slf4j
@Component
public class UserClient {

    // Caffeine cache for gender lookups, 30s TTL per design doc §11
    private final Cache<Long, Boolean> genderCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(10_000)
            .build();

    /**
     * Get friend/contact user IDs for write-fanout.
     * STUB: returns empty list. Replace with gRPC call when user-service is ready.
     *
     * @param userId the user whose friends to fetch
     * @return list of friend user_ids (empty for stub)
     */
    public List<Long> getFriendUserIds(Long userId) {
        // STUB — replace with:
        // var req = GetFriendListRequest.newBuilder().setUserId(userId).build();
        // var resp = stub.getFriendList(req);
        // return resp.getUserIdsList();
        log.debug("getFriendUserIds stub called for userId={}", userId);
        return Collections.emptyList();
    }

    /**
     * Check if a user is male.
     * STUB: userId % 2 == 0 → male. Caffeine 30s cache absorbs repeat calls.
     *
     * @param userId target user
     * @return true if male, false otherwise
     */
    public boolean isMale(Long userId) {
        Boolean cached = genderCache.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        // STUB — replace with gRPC call
        boolean male = userId % 2 == 0;
        genderCache.put(userId, male);
        return male;
    }

    /**
     * Batch get genders for multiple user IDs.
     * Used by FeedScoreJob to bucket posts into male/female pools.
     * Caffeine 30s cache absorbs same-author multi-post lookups.
     *
     * @param userIds distinct list of user IDs
     * @return map of userId → isMale (true = male, false = female)
     */
    public Map<Long, Boolean> getGenders(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }

        // Check cache first, collect misses
        Map<Long, Boolean> result = userIds.stream()
                .distinct()
                .collect(Collectors.toMap(uid -> uid, uid -> genderCache.getIfPresent(uid)));

        List<Long> misses = result.entrySet().stream()
                .filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey)
                .toList();

        if (misses.isEmpty()) {
            return result;
        }

        // STUB — replace with batch gRPC call
        for (Long uid : misses) {
            boolean male = uid % 2 == 0;
            genderCache.put(uid, male);
            result.put(uid, male);
        }

        log.debug("getGenders: {} total, {} cache hits, {} stubbed", userIds.size(), userIds.size() - misses.size(), misses.size());
        return result;
    }

    /**
     * Fallback gender for unknown users (defaults to female).
     * Used when user-service is unavailable.
     */
    private boolean fallbackGender() {
        return false;
    }
}
