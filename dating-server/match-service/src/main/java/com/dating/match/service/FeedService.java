package com.dating.match.service;

import com.dating.match.client.UserServiceClient;
import com.dating.match.client.UserServiceClient.ProfileData;
import com.dating.match.manager.FeedManager;
import com.dating.match.manager.QuotaManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Feed service: GetTodayFeed with LPOP consumption, secondary filter, and auto-rebuild.
 *
 * Flow:
 * 1. Check daily card quota → exhausted → return exhausted=true
 * 2. LPOP from Redis LIST, SMISMEMBER secondary filter
 * 3. If LIST empty → ColdStartService.buildAndPush → LPOP again
 * 4. Batch-get profiles from user-service, assemble Card VOs
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedService {

    private final FeedManager feedManager;
    private final QuotaService quotaService;
    private final ColdStartService coldStartService;
    private final UserServiceClient userClient;

    /**
     * Get today's feed cards for a user.
     *
     * @param userId the requesting user
     * @param count  number of cards to pull (typically 5)
     * @return feed result with cards and exhausted flag
     */
    public FeedResult getTodayFeed(long userId, int count) {
        // 1. Check quota
        if (quotaService.isCardsExhausted(userId)) {
            return new FeedResult(List.of(), true);
        }

        int remaining = remainingCards(userId);
        int need = Math.min(count, remaining);
        if (need <= 0) {
            return new FeedResult(List.of(), true);
        }

        // 2. LPOP from feed list with secondary filter
        List<String> rawCards = new ArrayList<>();
        int attempts = 0;
        int maxAttempts = 3; // Max rebuild attempts to avoid infinite loop

        while (rawCards.size() < need && attempts < maxAttempts) {
            List<String> batch = feedManager.lpop(userId, need - rawCards.size());
            if (batch.isEmpty()) {
                // 3. LIST empty → trigger real-time rebuild
                log.info("Feed list empty for user {}, triggering cold-start rebuild", userId);
                coldStartService.buildAndPush(userId);
                attempts++;
                continue;
            }
            // 4. Secondary filter: exclude already-swiped
            List<Long> targetIds = batch.stream().map(FeedManager::decodeUserId).toList();
            List<Boolean> swipedFlags = feedManager.areSwiped(userId, targetIds);
            for (int i = 0; i < batch.size(); i++) {
                if (!swipedFlags.get(i)) {
                    rawCards.add(batch.get(i));
                }
            }
            if (batch.size() < need - rawCards.size()) {
                attempts++; // Might need another batch
            }
        }

        // 5. Batch get profiles and assemble Card VOs
        List<CardVO> cards = assembleCards(userId, rawCards);

        return new FeedResult(cards, cards.size() < need || remaining <= cards.size());
    }

    /**
     * Assemble Card VOs from raw card strings with user profiles.
     */
    private List<CardVO> assembleCards(long userId, List<String> rawCards) {
        if (rawCards.isEmpty()) return List.of();

        List<Long> targetIds = rawCards.stream().map(FeedManager::decodeUserId).toList();
        Map<Long, ProfileData> profiles = userClient.batchGetProfile(targetIds);

        List<CardVO> cards = new ArrayList<>();
        for (String raw : rawCards) {
            long targetUserId = FeedManager.decodeUserId(raw);
            int targetUserType = FeedManager.decodeUserType(raw);
            ProfileData profile = profiles.get(targetUserId);
            if (profile == null) {
                log.warn("Profile not found for target_user_id={}, skipping card", targetUserId);
                continue;
            }
            double distanceKm = targetUserType == 1 ? 5.0 : -1; // BH placeholder, DH = -1
            cards.add(new CardVO(
                    targetUserId, targetUserType,
                    profile.nickname(), profile.age(), profile.photoKeys(),
                    profile.bio(), distanceKm
            ));
        }
        return cards;
    }

    private int remainingCards(long userId) {
        var info = quotaService.getQuotaInfo(userId);
        return info.dailyCardLimit() - info.dailyCardUsed();
    }

    // ── Inner data classes ──

    public record FeedResult(List<CardVO> cards, boolean exhausted) {}

    public record CardVO(long targetUserId, int targetUserType, String nickname, int age,
                          List<String> photoKeys, String bio, double distanceKm) {}
}
