package com.dating.match.recommend;

import com.dating.match.client.UserServiceClient.BhCandidate;
import com.dating.match.client.UserServiceClient.DhCandidate;
import com.dating.match.manager.FeedManager;
import com.dating.match.manager.SwipeHistoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * D1 daily queue generator: offline batch job that builds personalized feed queues
 * for users who had swipe activity yesterday.
 *
 * Process per user:
 * 1. Preference modeling (30-day right-swipe history)
 * 2. Dual-pool recall (DH 240 + BH 240, independent)
 * 3. Pool-internal ranking (scoring formula)
 * 4. Proportional merge (BH ratio with personalization offset)
 * 5. DEL old feed + RPUSH new 240 cards to Redis LIST
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class D1Generator {

    private final PreferenceBuilder preferenceBuilder;
    private final CandidateRecaller candidateRecaller;
    private final Ranker ranker;
    private final SwipeHistoryManager swipeHistoryManager;
    private final FeedManager feedManager;

    @Value("${app.match.d1.bh-ratio:0.40}")
    private double baseBhRatio;

    @Value("${app.match.d1.preference-enabled:true}")
    private boolean preferenceEnabled;

    @Value("${app.match.d1.preference-offset:0.20}")
    private double preferenceOffset;

    @Value("${app.match.d1-feed-size:240}")
    private int feedSize;

    /**
     * Generate D1 feed queue for a single user.
     * Only call if the user had swipe activity yesterday.
     *
     * @param userId the user to generate feed for
     */
    public void generateForUser(long userId) {
        // 1. Build preference profile
        var pref = preferenceBuilder.build(userId);

        // 2. Build exclude list (already-swiped + already-matched)
        List<Long> excludeUserIds = candidateRecaller.buildExcludeList(userId);

        // 3. Dual-pool recall
        List<DhCandidate> dhPool = candidateRecaller.recallDhPool(userId, pref, excludeUserIds);
        List<BhCandidate> bhPool = candidateRecaller.recallBhPool(userId, excludeUserIds);

        // 4. Pool-internal ranking
        List<DhCandidate> rankedDh = ranker.rankDh(dhPool, pref);
        List<BhCandidate> rankedBh = ranker.rankBh(bhPool, pref, userId);

        // 5. Compute personalized BH ratio
        double finalBhRatio = computeBhRatio(userId, pref);

        // 6. Merge with proportional interleaving
        List<String> mergedCards = merge(topN(rankedDh, feedSize), topN(rankedBh, feedSize), finalBhRatio);

        // 7. DEL old feed + RPUSH new feed
        feedManager.deleteFeed(userId);
        if (!mergedCards.isEmpty()) {
            feedManager.rpush(userId, mergedCards);
        }

        log.info("D1 generated for user {}: dh={}, bh={}, merged={}, bhRatio={}",
                userId, rankedDh.size(), rankedBh.size(), mergedCards.size(), finalBhRatio);
    }

    /**
     * Compute personalized BH ratio: base ratio + preference offset.
     *
     * L1 (global): match.d1.bh_ratio (default 0.40)
     * L2 (personalized): offset based on user's DH/BH right-swipe history
     *   - User swipes mostly BH → increase BH ratio
     *   - User swipes mostly DH → decrease BH ratio
     *
     * Fallback: if preference disabled or < 10 samples, use L1 only.
     */
    private double computeBhRatio(long userId, PreferenceBuilder.UserPreference pref) {
        if (!preferenceEnabled || pref.sampleCount() < 10) {
            return baseBhRatio;
        }
        // user likes DH more → reduce BH ratio (show more DH)
        // dhBhRatio = 0.0 (all BH) → offset = +preferenceOffset
        // dhBhRatio = 1.0 (all DH) → offset = -preferenceOffset
        double offset = (0.5 - pref.dhBhRatio()) * preferenceOffset * 2;
        return clamp(baseBhRatio + offset, 0, 1);
    }

    /**
     * Merge two ranked queues with proportional interleaving.
     * BH shortfall is filled by DH.
     */
    private List<String> merge(List<DhCandidate> dhQueue, List<BhCandidate> bhQueue, double bhRatio) {
        int targetBh = (int) Math.round(feedSize * bhRatio);
        int actualBh = Math.min(targetBh, bhQueue.size());
        int shortfall = targetBh - actualBh;
        int actualDh = Math.min(feedSize - targetBh + shortfall, dhQueue.size());

        List<String> result = new ArrayList<>();
        int interval = Math.max(1, (int) Math.round(1.0 / Math.max(bhRatio, 0.01)));
        int bhIdx = 0, dhIdx = 0;

        while (bhIdx < actualBh || dhIdx < actualDh) {
            int posInGroup = (bhIdx + dhIdx) % interval;
            if (posInGroup == 0 && bhIdx < actualBh) {
                BhCandidate bh = bhQueue.get(bhIdx++);
                result.add(FeedManager.encodeCard(bh.userId(), 1));
            } else if (dhIdx < actualDh) {
                DhCandidate dh = dhQueue.get(dhIdx++);
                result.add(FeedManager.encodeCard(dh.userId(), 2));
            }
        }

        return result;
    }

    private <T> List<T> topN(List<T> list, int n) {
        return list.subList(0, Math.min(n, list.size()));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
