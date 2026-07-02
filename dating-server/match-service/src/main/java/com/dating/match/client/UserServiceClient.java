package com.dating.match.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client wrapper for user-service.
 * Currently uses stub implementations until user-service protos are published.
 *
 * Calls: batchGetProfile, listDhCandidates, nearbyUsers, getGender.
 */
@Slf4j
@Component
public class UserServiceClient {

    /** Local cache for gender lookups to avoid RPC storms */
    private final Cache<Long, Integer> genderCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .maximumSize(10000)
            .build();

    /**
     * Batch get user profiles by user IDs.
     * Returns map of userId → ProfileData (nickname, age, photoKeys, bio, gender).
     * STUB: returns mock data.
     */
    public Map<Long, ProfileData> batchGetProfile(List<Long> userIds) {
        Map<Long, ProfileData> result = new LinkedHashMap<>();
        for (Long uid : userIds) {
            result.put(uid, new ProfileData(
                    uid, "User_" + uid, 25, List.of("avatar/default.jpg"), "Hello!", 1
            ));
        }
        return result;
    }

    /**
     * List DH candidates with given filters.
     * Called by D0 ColdStartService (with progressive level expansion L0-L3)
     * and D1 D1Generator (with preference-based filters).
     *
     * @param targetGender 1=MALE, 2=FEMALE
     * @param ageMin minimum age
     * @param ageMax maximum age
     * @param beautyMin minimum beauty score
     * @param beautyMax maximum beauty score
     * @param races preferred races (empty = no filter)
     * @param excludeUserIds user IDs to exclude
     * @param limit max results
     * @return list of DH candidates with profile data
     */
    public List<DhCandidate> listDhCandidates(int targetGender, int ageMin, int ageMax,
                                               int beautyMin, int beautyMax,
                                               List<String> races,
                                               List<Long> excludeUserIds,
                                               int limit) {
        // STUB: return empty list until user-service implements this RPC
        log.debug("listDhCandidates stub called: targetGender={}, limit={}", targetGender, limit);
        return Collections.emptyList();
    }

    /**
     * Find nearby BH users using Geo search.
     * Called by D0 ColdStartService and D1 D1Generator.
     *
     * @param userId the requesting user
     * @param radiusKm search radius in km
     * @param ageMin minimum age
     * @param ageMax maximum age
     * @param beautyMin minimum beauty score
     * @param beautyMax maximum beauty score
     * @param races preferred races
     * @param lastActiveWithinDays only return users active within this many days
     * @param limit max results
     * @param excludeUserIds user IDs to exclude
     * @return list of nearby BH candidates
     */
    public List<BhCandidate> nearbyUsers(long userId, double radiusKm,
                                          int ageMin, int ageMax,
                                          int beautyMin, int beautyMax,
                                          List<String> races,
                                          int lastActiveWithinDays,
                                          int limit,
                                          List<Long> excludeUserIds) {
        // STUB: return empty list until user-service implements this RPC
        log.debug("nearbyUsers stub called: userId={}, radiusKm={}", userId, radiusKm);
        return Collections.emptyList();
    }

    /**
     * Get gender for a single user.
     * STUB: returns userId % 2 == 0 ? MALE : FEMALE.
     *
     * @return 1=MALE, 2=FEMALE
     */
    public int getGender(long userId) {
        Integer cached = genderCache.getIfPresent(userId);
        if (cached != null) return cached;
        int gender = (userId % 2 == 0) ? 1 : 2;
        genderCache.put(userId, gender);
        return gender;
    }

    /**
     * Get the opposite gender for a user.
     */
    public int oppositeGender(long userId) {
        return getGender(userId) == 1 ? 2 : 1;
    }

    // ── Inner data classes ──

    /** Profile data returned by batchGetProfile */
    public record ProfileData(long userId, String nickname, int age, List<String> photoKeys,
                               String bio, int gender) {}

    /** DH candidate returned by listDhCandidates */
    public record DhCandidate(long userId, int age, int beautyScore, String race, int gender,
                               long createdAtEpoch) {}

    /** BH candidate returned by nearbyUsers */
    public record BhCandidate(long userId, int age, int beautyScore, String race, int gender,
                               long createdAtEpoch, long lastActiveAtEpoch, double distanceKm) {}
}
