package com.dating.match.scheduler;

import com.dating.match.entity.MatchOutbox;
import com.dating.match.manager.MatchOutboxManager;
import com.dating.match.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Outbox retry scheduler: processes failed side effects from match creation.
 * Runs every 30 seconds with exponential backoff for individual entries.
 *
 * Max attempts before marking DEAD: 10
 * Backoff: 30s, 60s, 120s, 240s, 480s, 960s, 1920s, 3840s, 7680s, 15360s
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchOutboxRetry {

    private final MatchOutboxManager outboxManager;
    private final MatchService matchService;

    private static final int MAX_ATTEMPTS = 10;
    private static final int BATCH_SIZE = 100;

    /**
     * Process pending outbox entries every 30 seconds.
     */
    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "match.outboxRetry", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void run() {
        List<MatchOutbox> pending = outboxManager.findPendingAndDue(BATCH_SIZE);
        if (pending.isEmpty()) return;

        log.debug("Processing {} pending outbox entries", pending.size());

        for (MatchOutbox outbox : pending) {
            try {
                boolean success = matchService.processOutboxAction(outbox);
                if (success) {
                    outboxManager.markDone(outbox.getId());
                } else {
                    int newAttempts = outbox.getAttempts() + 1;
                    if (newAttempts >= MAX_ATTEMPTS) {
                        log.error("Outbox entry {} reached max attempts, marking DEAD", outbox.getId());
                        outboxManager.markDead(outbox.getId());
                    } else {
                        // Exponential backoff: 30s * 2^attempts
                        long backoffSec = 30L * (1L << (newAttempts - 1));
                        OffsetDateTime nextRetry = OffsetDateTime.now().plusSeconds(backoffSec);
                        outboxManager.updateStatus(outbox.getId(), "PENDING", newAttempts, nextRetry);
                    }
                }
            } catch (Exception e) {
                log.error("Error processing outbox entry {}", outbox.getId(), e);
                int newAttempts = outbox.getAttempts() + 1;
                if (newAttempts >= MAX_ATTEMPTS) {
                    outboxManager.markDead(outbox.getId());
                } else {
                    OffsetDateTime nextRetry = OffsetDateTime.now().plusSeconds(60L * newAttempts);
                    outboxManager.updateStatus(outbox.getId(), "PENDING", newAttempts, nextRetry);
                }
            }
        }
    }
}
