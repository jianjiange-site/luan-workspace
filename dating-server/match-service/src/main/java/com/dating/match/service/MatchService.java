package com.dating.match.service;

import com.dating.match.client.ImServiceClient;
import com.dating.match.config.SnowflakeIdGenerator;
import com.dating.match.constant.MatchSourceEnum;
import com.dating.match.entity.Match;
import com.dating.match.entity.MatchOutbox;
import com.dating.match.manager.LikeRecordManager;
import com.dating.match.manager.MatchManager;
import com.dating.match.manager.MatchOutboxManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Match creation service: creates match records and orchestrates side effects.
 *
 * Side effects within local PG transaction:
 * 1. INSERT match (ON CONFLICT DO NOTHING for idempotency)
 * 2. DELETE like_record in both directions (relationship upgraded from "crush" to "match")
 *
 * Remote side effects (via outbox for async retry):
 * 1. im-service.ensureConversation
 * 2. im-service.sendSystemMessage (to both users)
 * 3. im-service.triggerDhOpening (if DH is involved)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchManager matchManager;
    private final MatchOutboxManager outboxManager;
    private final LikeRecordManager likeRecordManager;
    private final ImServiceClient imClient;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * Create a match between two users.
     * Idempotent: if the pair already has a match, logs ERROR (indicating recall bug) and returns existing.
     *
     * @param userId1 first user
     * @param userId2 second user
     * @param source  SWIPE_MATCH or SWIPE_SUPER_HI
     * @return the match entity (new or existing)
     */
    @Transactional
    public Match createMatch(long userId1, long userId2, MatchSourceEnum source) {
        long low = Math.min(userId1, userId2);
        long high = Math.max(userId1, userId2);

        // 1. Insert match with idempotency guard
        int rows = matchManager.insertIgnoreConflict(low, high, source.getValue());
        if (rows == 0) {
            // Duplicate — upstream recall filter bug
            Match existing = matchManager.findByPair(low, high);
            log.error("Duplicate match attempt: pair=({}, {}) existing_id={} existing_source={} new_source={} — "
                            + "upstream recall filter may have a bug, investigate user_swipe_history and exclude_user_ids",
                    low, high, existing != null ? existing.getId() : "?",
                    existing != null ? existing.getSource() : "?", source.getValue());
            return existing;
        }

        Match match = matchManager.findByPair(low, high);

        // 2. Clean up like_records in both directions (relationship upgraded to match)
        likeRecordManager.deleteBothDirections(userId1, userId2);

        // 3. Write outbox entries for remote side effects (outside transaction via async retry)
        writeOutboxEntries(match.getId(), userId1, userId2, source);

        log.info("Match created: id={}, pair=({}, {}), source={}", match.getId(), low, high, source.getValue());
        return match;
    }

    /**
     * Write outbox entries for async side effects.
     * These are retried by MatchOutboxRetry scheduler.
     */
    private void writeOutboxEntries(long matchId, long userId1, long userId2, MatchSourceEnum source) {
        OffsetDateTime now = OffsetDateTime.now();

        // Ensure conversation
        MatchOutbox conv = new MatchOutbox();
        conv.setId(idGenerator.nextId());
        conv.setMatchId(matchId);
        conv.setAction("ENSURE_CONVERSATION");
        conv.setPayloadJson("{\"user_id_1\":" + userId1 + ",\"user_id_2\":" + userId2 + "}");
        conv.setAttempts(0);
        conv.setNextRetryAt(now);
        conv.setStatus("PENDING");
        outboxManager.insert(conv);

        // System message to user 1
        MatchOutbox sys1 = new MatchOutbox();
        sys1.setId(idGenerator.nextId());
        sys1.setMatchId(matchId);
        sys1.setAction("SYSTEM_MSG");
        sys1.setPayloadJson("{\"to_user_id\":" + userId1 + ",\"payload\":\"You have a new match!\"}");
        sys1.setAttempts(0);
        sys1.setNextRetryAt(now);
        sys1.setStatus("PENDING");
        outboxManager.insert(sys1);

        // System message to user 2
        MatchOutbox sys2 = new MatchOutbox();
        sys2.setId(idGenerator.nextId());
        sys2.setMatchId(matchId);
        sys2.setAction("SYSTEM_MSG");
        sys2.setPayloadJson("{\"to_user_id\":" + userId2 + ",\"payload\":\"You have a new match!\"}");
        sys2.setAttempts(0);
        sys2.setNextRetryAt(now);
        sys2.setStatus("PENDING");
        outboxManager.insert(sys2);
    }

    /**
     * Process a single outbox action. Called by MatchOutboxRetry scheduler.
     *
     * @return true if successful, false if retry needed
     */
    public boolean processOutboxAction(MatchOutbox outbox) {
        try {
            switch (outbox.getAction()) {
                case "ENSURE_CONVERSATION" -> {
                    // Parse payload to get user IDs
                    imClient.ensureConversation(0, 0); // STUB
                }
                case "SYSTEM_MSG" -> {
                    imClient.sendSystemMessage(0, ""); // STUB
                }
                case "DH_OPENING" -> {
                    imClient.triggerDhOpening(0, 0); // STUB
                }
                default -> log.warn("Unknown outbox action: {}", outbox.getAction());
            }
            return true;
        } catch (Exception e) {
            log.error("Outbox action failed: id={}, action={}, attempt={}",
                    outbox.getId(), outbox.getAction(), outbox.getAttempts(), e);
            return false;
        }
    }
}
