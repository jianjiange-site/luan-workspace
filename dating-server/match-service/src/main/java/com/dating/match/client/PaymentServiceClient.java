package com.dating.match.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client wrapper for payment-service.
 * Currently uses stub implementations until payment-service protos are published.
 *
 * Calls: getSubscription, consumeCoins.
 */
@Slf4j
@Component
public class PaymentServiceClient {

    @Value("${app.match.super-hi-coin-price:100}")
    private int superHiCoinPrice;

    /** Subscription cache, TTL 5 min */
    private final Cache<Long, SubscriptionInfo> subscriptionCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(50000)
            .build();

    /**
     * Get subscription tier and expiry for a user.
     * Cached for 5 minutes.
     *
     * @return subscription info; defaults to FREE tier
     */
    public SubscriptionInfo getSubscription(long userId) {
        SubscriptionInfo cached = subscriptionCache.getIfPresent(userId);
        if (cached != null) return cached;

        // STUB: all users are FREE until payment-service implements this RPC
        SubscriptionInfo info = new SubscriptionInfo("FREE", 0);
        subscriptionCache.put(userId, info);
        return info;
    }

    /**
     * Invalidate subscription cache for a user (called on payment change events).
     */
    public void invalidateSubscription(long userId) {
        subscriptionCache.invalidate(userId);
    }

    /**
     * Consume coins for Super Hi purchase.
     *
     * @return true if coins consumed successfully, false if insufficient
     */
    public boolean consumeCoins(long userId, int amount, String reason) {
        // STUB: always return true until payment-service implements this RPC
        log.debug("consumeCoins stub: userId={}, amount={}, reason={}", userId, amount, reason);
        return true;
    }

    /**
     * Get the coin price for one Super Hi.
     */
    public int getSuperHiCoinPrice() {
        return superHiCoinPrice;
    }

    // ── Inner data class ──

    /**
     * Subscription tier info.
     *
     * @param tier FREE | WEEKLY | MONTHLY | YEARLY
     * @param dailySuperHiLimit number of free Super Hi per day for this tier
     */
    public record SubscriptionInfo(String tier, int dailySuperHiLimit) {
        public int dailyRightSwipeLimit() {
            return switch (tier) {
                case "YEARLY", "MONTHLY" -> 15;
                case "WEEKLY" -> 10;
                default -> 5;
            };
        }

        public int dailyCardLimit() {
            return switch (tier) {
                case "YEARLY", "MONTHLY" -> 120;
                case "WEEKLY" -> 80;
                default -> 50;
            };
        }
    }
}
