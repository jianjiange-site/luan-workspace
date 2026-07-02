package com.dating.match.service;

import com.dating.match.client.UserServiceClient;
import com.dating.match.client.UserServiceClient.BhCandidate;
import com.dating.match.client.UserServiceClient.DhCandidate;
import com.dating.match.manager.FeedManager;
import com.dating.match.manager.SwipeHistoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * D0 cold-start service: real-time dual-pool recall + merge for new users
 * or users whose feed LIST is empty.
 *
 * Unlike D1 (which uses user preference from 30-day right-swipe history),
 * D0 uses the user's own profile as a prior for preference.
 *
 * DH pool: progressive level expansion L0→L3 to guarantee 240 cards.
 * BH pool: strict single-level filter, shortfall accepted (DH fills gap).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ColdStartService {

    private final UserServiceClient userClient;
    private final SwipeHistoryManager swipeHistoryManager;
    private final FeedManager feedManager;

    @Value("${app.match.cold-start.bh-ratio:0.20}")
    private double bhRatio;

    @Value("${app.match.cold-start.bh.radius-km:100}")
    private double bhRadiusKm;

    @Value("${app.match.cold-start.bh.age-window:5}")
    private int bhAgeWindow;

    @Value("${app.match.cold-start.bh.beauty-window:15}")
    private int bhBeautyWindow;

    @Value("${app.match.cold-start.bh.active-days:7}")
    private int bhActiveDays;

    @Value("${app.match.d0-feed-size:240}")
    private int feedSize;

    /**
     * Build D0 feed and push to Redis LIST for a user.
     * Called when feed LIST is empty (new user or list exhausted).
     */
    public void buildAndPush(long userId) {
        int userGender = userClient.getGender(userId);
        int targetGender = userGender == 1 ? 2 : 1; // Heterosexual assumption
        int userAge = 25; // STUB: would come from user profile
        int userBeauty = 50; // STUB: would come from user profile
        String userRace = "Asian"; // STUB: would come from user profile

        // Get already-swiped target IDs to exclude
        List<Long> excludeUserIds = swipeHistoryManager.findAllSwipedTargetIds(userId);

        // DH pool: progressive level expansion
        List<DhCandidate> dhPool = recallDhPoolProgressive(targetGender, userAge, userBeauty, userRace, excludeUserIds);

        // BH pool: strict single-level filter
        List<BhCandidate> bhPool = recallBhPoolStrict(userId, userAge, userBeauty, userRace, excludeUserIds);

        // Sort DH pool: same race → age diff → beauty
        dhPool.sort(Comparator
                .comparing((DhCandidate c) -> c.race().equals(userRace) ? 0 : 1)
                .thenComparingInt(c -> Math.abs(c.age() - userAge))
                .thenComparingInt(DhCandidate::beautyScore, Comparator.reverseOrder()));

        // Sort BH pool: new BH → same race → age diff → beauty
        long newBhThreshold = System.currentTimeMillis() / 1000 - 3L * 86400; // 3 days
        bhPool.sort(Comparator
                .comparing((BhCandidate c) -> c.createdAtEpoch() >= newBhThreshold ? 0 : 1)
                .thenComparingInt(c -> c.race().equals(userRace) ? 0 : 1)
                .thenComparingInt(c -> Math.abs(c.age() - userAge))
                .thenComparingInt(BhCandidate::beautyScore, Comparator.reverseOrder()));

        // Limit each pool to feedSize
        List<DhCandidate> topDh = dhPool.subList(0, Math.min(feedSize, dhPool.size()));
        List<BhCandidate> topBh = bhPool.subList(0, Math.min(feedSize, bhPool.size()));

        // Merge with BH ratio
        int targetBh = (int) Math.round(feedSize * bhRatio);
        int actualBh = Math.min(targetBh, topBh.size());
        int shortfall = targetBh - actualBh;
        int actualDh = (feedSize - targetBh) + shortfall;
        actualDh = Math.min(actualDh, topDh.size());

        // Interleave: one BH every round(1/bhRatio) DH cards
        List<String> mergedCards = new ArrayList<>();
        int interval = (int) Math.round(1.0 / bhRatio);
        int bhIdx = 0, dhIdx = 0;
        while (bhIdx < actualBh || dhIdx < actualDh) {
            int posInGroup = (bhIdx + dhIdx) % interval;
            if (posInGroup == 0 && bhIdx < actualBh) {
                BhCandidate bh = topBh.get(bhIdx++);
                mergedCards.add(FeedManager.encodeCard(bh.userId(), 1));
            } else if (dhIdx < actualDh) {
                DhCandidate dh = topDh.get(dhIdx++);
                mergedCards.add(FeedManager.encodeCard(dh.userId(), 2));
            }
        }

        // RPUSH to Redis LIST
        if (!mergedCards.isEmpty()) {
            feedManager.rpush(userId, mergedCards);
            log.info("D0 cold-start built: userId={}, bh={}, dh={}, total={}",
                    userId, actualBh, actualDh, mergedCards.size());
        }
    }

    /**
     * DH pool progressive recall: L0→L3 levels, each layer adds more candidates
     * until we reach feedSize or exhaust all levels.
     */
    private List<DhCandidate> recallDhPoolProgressive(int targetGender, int userAge, int userBeauty,
                                                       String userRace, List<Long> excludeUserIds) {
        Set<Long> seen = new HashSet<>();
        List<DhCandidate> all = new ArrayList<>();

        // L0: strictest — same race, ±5 age, ±15 beauty
        accumulate(all, seen, userClient.listDhCandidates(targetGender,
                userAge - 5, userAge + 5, userBeauty - 15, userBeauty + 15,
                List.of(userRace), excludeUserIds, feedSize));
        if (all.size() >= feedSize) return all;

        // L1: expand race
        accumulate(all, seen, userClient.listDhCandidates(targetGender,
                userAge - 5, userAge + 5, userBeauty - 15, userBeauty + 15,
                List.of(), excludeUserIds, feedSize - all.size()));
        if (all.size() >= feedSize) return all;

        // L2: expand age and beauty
        accumulate(all, seen, userClient.listDhCandidates(targetGender,
                userAge - 10, userAge + 10, userBeauty - 25, userBeauty + 25,
                List.of(), excludeUserIds, feedSize - all.size()));
        if (all.size() >= feedSize) return all;

        // L3: no constraints except gender and not-swiped
        accumulate(all, seen, userClient.listDhCandidates(targetGender,
                18, 99, 0, 100,
                List.of(), excludeUserIds, feedSize - all.size()));

        return all;
    }

    /**
     * BH pool strict single-level recall (no progressive expansion).
     */
    private List<BhCandidate> recallBhPoolStrict(long userId, int userAge, int userBeauty,
                                                   String userRace, List<Long> excludeUserIds) {
        int targetGender = userClient.oppositeGender(userId);
        return userClient.nearbyUsers(userId, bhRadiusKm,
                userAge - bhAgeWindow, userAge + bhAgeWindow,
                userBeauty - bhBeautyWindow, userBeauty + bhBeautyWindow,
                List.of(userRace), bhActiveDays, feedSize, excludeUserIds);
    }

    /** Accumulate unique candidates into the list, deduplicating by userId. */
    private void accumulate(List<DhCandidate> all, Set<Long> seen, List<DhCandidate> batch) {
        for (DhCandidate c : batch) {
            if (seen.add(c.userId())) {
                all.add(c);
            }
        }
    }
}
