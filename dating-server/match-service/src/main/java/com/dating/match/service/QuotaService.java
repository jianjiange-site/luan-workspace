package com.dating.match.service;

import com.dating.match.client.PaymentServiceClient;
import com.dating.match.client.PaymentServiceClient.SubscriptionInfo;
import com.dating.match.constant.ErrorCode;
import com.dating.match.exception.BizException;
import com.dating.match.manager.QuotaManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Quota service: checks and deducts daily swipe/card/super-hi quotas.
 *
 * Quota limits come from subscription tier (via payment-service) and are enforced
 * via Redis HASH atomic increments.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private final QuotaManager quotaManager;
    private final PaymentServiceClient paymentClient;

    /**
     * Check and deduct card quota (counts for LEFT, RIGHT, SUPER_HI).
     *
     * @throws BizException QUOTA_CARDS_EXCEEDED if daily card limit reached
     */
    public void deductCard(long userId) {
        SubscriptionInfo sub = paymentClient.getSubscription(userId);
        if (!quotaManager.incrementAndCheck(userId, QuotaManager.FIELD_CARDS, sub.dailyCardLimit())) {
            throw new BizException(ErrorCode.QUOTA_CARDS_EXCEEDED, "Daily card limit reached");
        }
    }

    /**
     * Check and deduct right-swipe quota.
     *
     * @throws BizException QUOTA_RIGHT_SWIPE_EXCEEDED if daily right-swipe limit reached
     */
    public void deductRightSwipe(long userId) {
        SubscriptionInfo sub = paymentClient.getSubscription(userId);
        if (!quotaManager.incrementAndCheck(userId, QuotaManager.FIELD_RIGHT_SWIPE, sub.dailyRightSwipeLimit())) {
            throw new BizException(ErrorCode.QUOTA_RIGHT_SWIPE_EXCEEDED, "Daily right-swipe limit reached");
        }
    }

    /**
     * Check and deduct Super Hi quota (subscription gift first, then coins).
     *
     * @return number of coins used (0 = subscription gift, >0 = coin purchase)
     * @throws BizException QUOTA_SUPER_HI_EXCEEDED or INSUFFICIENT_COINS
     */
    public int deductSuperHi(long userId) {
        SubscriptionInfo sub = paymentClient.getSubscription(userId);
        // Try subscription gift first
        boolean withinGift = quotaManager.incrementAndCheck(userId, QuotaManager.FIELD_SUPER_HI, sub.dailySuperHiLimit());
        if (withinGift) {
            return 0; // Used free subscription gift
        }
        // Fallback to coin purchase
        int coinPrice = paymentClient.getSuperHiCoinPrice();
        boolean ok = paymentClient.consumeCoins(userId, coinPrice, "SUPER_HI");
        if (!ok) {
            throw new BizException(ErrorCode.INSUFFICIENT_COINS, "Insufficient coins for Super Hi");
        }
        return coinPrice;
    }

    /**
     * Build full quota response for GetQuota RPC.
     */
    public QuotaInfo getQuotaInfo(long userId) {
        SubscriptionInfo sub = paymentClient.getSubscription(userId);
        return new QuotaInfo(
                sub.dailyRightSwipeLimit(),
                quotaManager.getField(userId, QuotaManager.FIELD_RIGHT_SWIPE),
                sub.dailyCardLimit(),
                quotaManager.getField(userId, QuotaManager.FIELD_CARDS),
                sub.dailySuperHiLimit(),
                quotaManager.getField(userId, QuotaManager.FIELD_SUPER_HI),
                paymentClient.getSuperHiCoinPrice(),
                sub.tier()
        );
    }

    /**
     * Check if cards are exhausted for today.
     */
    public boolean isCardsExhausted(long userId) {
        SubscriptionInfo sub = paymentClient.getSubscription(userId);
        return quotaManager.getField(userId, QuotaManager.FIELD_CARDS) >= sub.dailyCardLimit();
    }

    /**
     * Rollback a card quota deduction.
     */
    public void rollbackCard(long userId) {
        quotaManager.decrement(userId, QuotaManager.FIELD_CARDS);
    }

    /**
     * Rollback a right-swipe quota deduction.
     */
    public void rollbackRightSwipe(long userId) {
        quotaManager.decrement(userId, QuotaManager.FIELD_RIGHT_SWIPE);
    }

    // ── Inner data class ──

    public record QuotaInfo(int dailyRightSwipeLimit, int dailyRightSwipeUsed,
                            int dailyCardLimit, int dailyCardUsed,
                            int dailySuperHiLimit, int dailySuperHiUsed,
                            int superHiCoinPrice, String subscriptionTier) {}
}
