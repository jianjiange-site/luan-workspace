package com.dating.post.job;

import com.dating.post.service.FeedService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the recommend feed pools every 5 minutes.
 * <p>
 * Full rebuild process (in FeedService.rebuildRecommendPool):
 *   1. SELECT all normal posts from last 3 days
 *   2. Batch get post_stats (single-table, no JOIN)
 *   3. Redis delta compensation for real-time counts
 *   4. Hacker News variant scoring: (10 + 1.0*likes + 3.0*comments) / (hours+2)^1.5
 *   5. Gender bucketing via UserClient.getGenders (Caffeine 30s cached)
 *   6. Shadow write to tmp ZSet → trim top 3000 → atomic RENAME
 * <p>
 * ShedLock ensures only one instance rebuilds across the cluster.
 * 5-minute interval is sufficient: scores decay passively, precision is not critical.
 */
@Slf4j
@Component
public class FeedScoreJob {

    private final FeedService feedService;

    public FeedScoreJob(FeedService feedService) {
        this.feedService = feedService;
    }

    @Scheduled(fixedRate = 300_000) // 5 minutes
    @SchedulerLock(name = "post.feedScore",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT30S")
    public void rebuildPools() {
        log.info("FeedScoreJob triggered");
        try {
            feedService.rebuildRecommendPool();
        } catch (Exception e) {
            log.error("FeedScoreJob failed", e);
        }
    }
}
