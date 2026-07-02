package com.dating.match.recommend;

import com.dating.match.client.UserServiceClient;
import com.dating.match.client.UserServiceClient.BhCandidate;
import com.dating.match.client.UserServiceClient.DhCandidate;
import com.dating.match.manager.FeedManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.SwipeHistoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Candidate recall for D1 daily queue generation.
 *
 * Two independent pools:
 * 1. DH pool: listDhCandidates with preference-based wide filters
 * 2. BH pool: nearbyUsers with geographic + activity filters
 *
 * Both pools exclude previously-swiped and mutually-blocked users.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateRecaller {

    private final UserServiceClient userClient;
    private final SwipeHistoryManager swipeHistoryManager;
    private final MatchManager matchManager;

    private static final int POOL_TARGET = 240;

    /**
     * Recall DH candidates based on user preference.
     *
     * @param userId        the target user
     * @param pref          user preference profile
     * @param excludeUserIds user IDs to exclude (swiped + blocked + matched)
     * @return list of DH candidates (up to POOL_TARGET)
     */
    public List<DhCandidate> recallDhPool(long userId, PreferenceBuilder.UserPreference pref,
                                           List<Long> excludeUserIds) {
        int targetGender = userClient.oppositeGender(userId);
        // Wide filters: ±2 std on age/beauty, all races
        int ageMin = Math.max(18, (int) (pref.ageMean() - 2 * pref.ageStd()));
        int ageMax = Math.min(99, (int) (pref.ageMean() + 2 * pref.ageStd()));
        int beautyMin = Math.max(0, (int) (pref.beautyMean() - 2 * pref.beautyStd()));
        int beautyMax = Math.min(100, (int) (pref.beautyMean() + 2 * pref.beautyStd()));

        return userClient.listDhCandidates(targetGender, ageMin, ageMax, beautyMin, beautyMax,
                List.of(), excludeUserIds, POOL_TARGET);
    }

    /**
     * Recall BH candidates based on geographic proximity and activity.
     *
     * @param userId        the target user
     * @param excludeUserIds user IDs to exclude
     * @return list of BH candidates (0 to POOL_TARGET)
     */
    public List<BhCandidate> recallBhPool(long userId, List<Long> excludeUserIds) {
        int targetGender = userClient.oppositeGender(userId);
        // D1: wider radius (200km) and same 7-day activity filter
        return userClient.nearbyUsers(userId, 200,
                18, 99, 0, 100,
                List.of(), 7, POOL_TARGET, excludeUserIds);
    }

    /**
     * Build the complete exclude list for a user:
     * all swiped targets + all matched users + mutually blocked users.
     */
    public List<Long> buildExcludeList(long userId) {
        List<Long> exclude = new ArrayList<>(swipeHistoryManager.findAllSwipedTargetIds(userId));
        exclude.addAll(matchManager.findAllMatchedUserIds(userId));
        // TODO: add blocked users when user-service supports it
        return exclude.stream().distinct().toList();
    }
}
