package com.dating.match.recommend;

import com.dating.match.client.UserServiceClient;
import com.dating.match.entity.UserSwipeHistory;
import com.dating.match.manager.SwipeHistoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Builds user preference profile from 30-day right-swipe history.
 *
 * Preferences include:
 * - age_mean, age_std (from target profiles)
 * - beauty_mean, beauty_std (from target profiles)
 * - race_dist (distribution of races among right-swiped targets)
 * - dh_bh_ratio (ratio of DH vs BH in right-swipes)
 *
 * Fallback: < 10 samples → use user's own profile as prior (D0-style).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreferenceBuilder {

    private final SwipeHistoryManager swipeHistoryManager;
    private final UserServiceClient userClient;

    /**
     * Build preference profile for a user based on last 30 days of right-swipes.
     *
     * @param userId the user to build preference for
     * @return preference profile, or null if insufficient data
     */
    public UserPreference build(long userId) {
        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        List<UserSwipeHistory> rightSwipes = swipeHistoryManager.findRightSwipesSince(userId, since);

        if (rightSwipes.size() < 10) {
            log.debug("Insufficient right-swipe data for user {}: {} samples, using prior", userId, rightSwipes.size());
            return buildPriorPreference(userId);
        }

        // Batch-get profiles for all right-swiped targets
        List<Long> targetIds = rightSwipes.stream().map(UserSwipeHistory::getTargetUserId).toList();
        var profiles = userClient.batchGetProfile(targetIds);

        // Compute age stats
        double ageMean = profiles.values().stream()
                .mapToInt(p -> p.age())
                .average().orElse(25);
        double ageStd = Math.sqrt(profiles.values().stream()
                .mapToDouble(p -> Math.pow(p.age() - ageMean, 2))
                .average().orElse(25));

        // Compute beauty stats (STUB: beauty score not in current ProfileData, use age as proxy)
        double beautyMean = 50;
        double beautyStd = 15;

        // Compute race distribution
        Map<String, Double> raceDist = new HashMap<>();
        for (var p : profiles.values()) {
            // STUB: race not in current ProfileData
            raceDist.merge("Asian", 1.0 / profiles.size(), Double::sum);
        }

        // Compute DH/BH ratio
        long dhCount = rightSwipes.stream().filter(s -> s.getTargetUserType() == 2).count();
        double dhBhRatio = rightSwipes.isEmpty() ? 0.5 : (double) dhCount / rightSwipes.size();

        return new UserPreference(ageMean, ageStd, beautyMean, beautyStd, raceDist, dhBhRatio, rightSwipes.size());
    }

    /**
     * Build prior-based preference when insufficient swipe data exists.
     * Uses user's own profile characteristics as the best guess.
     */
    private UserPreference buildPriorPreference(long userId) {
        // STUB: get actual user profile when user-service supports it
        return new UserPreference(25, 5, 50, 15, Map.of("Asian", 1.0), 0.5, 0);
    }

    // ── Inner data class ──

    public record UserPreference(double ageMean, double ageStd, double beautyMean, double beautyStd,
                                  Map<String, Double> raceDist, double dhBhRatio, int sampleCount) {}
}
