package com.dating.match.recommend;

import com.dating.match.client.UserServiceClient.BhCandidate;
import com.dating.match.client.UserServiceClient.DhCandidate;
import com.dating.match.manager.SwipeHistoryManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * D1 ranking/scoring module.
 *
 * Scoring formula (only for D1; D0 uses demographic hard sort):
 * S(c) = base_score(c) + mutual_like_bonus(c) + new_bh_bonus(c)
 *
 * base_score(c) =
 *   0.45 * preference_similarity(c, user_pref)
 * + 0.30 * normalize(c.beauty_score)
 * + 0.15 * distance_decay(c, user)     [DH fixed 0.5]
 * + 0.10 * activity_score(c)           [DH fixed 0.5]
 *
 * Two additive bonuses (BH only, stackable):
 * - mutual_like_bonus: +0.20 if target previously liked user
 * - new_bh_bonus: +0.20 if target registered within N days
 */
@Slf4j
@Component
public class Ranker {

    private final SwipeHistoryManager swipeHistoryManager;

    @Value("${app.match.score.mutual-like-bonus:0.20}")
    private double mutualLikeBonus;

    @Value("${app.match.score.new-bh-bonus:0.20}")
    private double newBhBonus;

    @Value("${app.match.score.new-bh-window-days:3}")
    private int newBhWindowDays;

    // Base score weights
    private static final double W_PREF = 0.45;
    private static final double W_BEAUTY = 0.30;
    private static final double W_DISTANCE = 0.15;
    private static final double W_ACTIVITY = 0.10;

    public Ranker(SwipeHistoryManager swipeHistoryManager) {
        this.swipeHistoryManager = swipeHistoryManager;
    }

    /**
     * Score and rank BH candidates for D1 feed.
     */
    public List<BhCandidate> rankBh(List<BhCandidate> candidates, PreferenceBuilder.UserPreference pref,
                                      long userId) {
        long nowEpoch = System.currentTimeMillis() / 1000;
        long newBhThreshold = nowEpoch - newBhWindowDays * 86400L;

        return candidates.stream()
                .map(c -> new ScoredBhCandidate(c, scoreBh(c, pref, userId, nowEpoch, newBhThreshold)))
                .sorted(Comparator.comparingDouble(ScoredBhCandidate::score).reversed())
                .map(ScoredBhCandidate::candidate)
                .toList();
    }

    private double scoreBh(BhCandidate c, PreferenceBuilder.UserPreference pref,
                           long userId, long nowEpoch, long newBhThreshold) {
        // Base score
        double base = W_PREF * preferenceSimilarity(c, pref)
                + W_BEAUTY * normalizeBeauty(c.beautyScore())
                + W_DISTANCE * distanceDecay(c.distanceKm())
                + W_ACTIVITY * activityScore(c.lastActiveAtEpoch(), nowEpoch);

        // Additive bonuses (BH only)
        double bonuses = 0;
        if (swipeHistoryManager.hasTargetLikedUser(c.userId(), userId)) {
            bonuses += mutualLikeBonus;
        }
        if (c.createdAtEpoch() >= newBhThreshold) {
            bonuses += newBhBonus;
        }

        return base + bonuses;
    }

    /**
     * Score and rank DH candidates for D1 feed.
     * DHs don't get mutual_like_bonus or new_bh_bonus.
     */
    public List<DhCandidate> rankDh(List<DhCandidate> candidates, PreferenceBuilder.UserPreference pref) {
        return candidates.stream()
                .map(c -> new ScoredDhCandidate(c, scoreDh(c, pref)))
                .sorted(Comparator.comparingDouble(ScoredDhCandidate::score).reversed())
                .map(ScoredDhCandidate::candidate)
                .toList();
    }

    private double scoreDh(DhCandidate c, PreferenceBuilder.UserPreference pref) {
        return W_PREF * preferenceSimilarity(c, pref)
                + W_BEAUTY * normalizeBeauty(c.beautyScore())
                + W_DISTANCE * 0.5  // DH has no location, neutral
                + W_ACTIVITY * 0.5; // DH always "active", neutral
    }

    // ── Scoring Components ──

    /**
     * Preference similarity: Gaussian PDF product for age × beauty × race.
     * age: |c.age - pref.age_mean| / pref.age_std
     * beauty: |c.beauty - pref.beauty_mean| / pref.beauty_std
     * race: race_dist[c.race] or 0
     */
    private double preferenceSimilarity(BhCandidate c, PreferenceBuilder.UserPreference pref) {
        double ageSim = gaussianPdf(Math.abs(c.age() - pref.ageMean()) / Math.max(pref.ageStd(), 1));
        double beautySim = gaussianPdf(Math.abs(c.beautyScore() - pref.beautyMean()) / Math.max(pref.beautyStd(), 1));
        double raceWeight = pref.raceDist().getOrDefault(c.race(), 0.0);
        return ageSim * beautySim * Math.max(raceWeight, 0.01);
    }

    private double preferenceSimilarity(DhCandidate c, PreferenceBuilder.UserPreference pref) {
        double ageSim = gaussianPdf(Math.abs(c.age() - pref.ageMean()) / Math.max(pref.ageStd(), 1));
        double beautySim = gaussianPdf(Math.abs(c.beautyScore() - pref.beautyMean()) / Math.max(pref.beautyStd(), 1));
        double raceWeight = pref.raceDist().getOrDefault(c.race(), 0.0);
        return ageSim * beautySim * Math.max(raceWeight, 0.01);
    }

    /** Standard normal PDF: exp(-0.5 * z^2) */
    private double gaussianPdf(double z) {
        return Math.exp(-0.5 * z * z);
    }

    /** Normalize beauty score 0-100 to 0-1 */
    private double normalizeBeauty(int beautyScore) {
        return beautyScore / 100.0;
    }

    /** Distance decay: exp(-d / 50km). Closer = higher score. */
    private double distanceDecay(double distanceKm) {
        return Math.exp(-distanceKm / 50.0);
    }

    /** Activity score: exp(-days_since_last_active / 7). Recently active = higher. */
    private double activityScore(long lastActiveEpoch, long nowEpoch) {
        double daysInactive = (nowEpoch - lastActiveEpoch) / 86400.0;
        return Math.exp(-daysInactive / 7.0);
    }

    // ── Inner scoring wrappers ──

    private record ScoredBhCandidate(BhCandidate candidate, double score) {}
    private record ScoredDhCandidate(DhCandidate candidate, double score) {}
}
