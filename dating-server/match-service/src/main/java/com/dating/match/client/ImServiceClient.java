package com.dating.match.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * gRPC client wrapper for im-service.
 * Currently uses stub implementations until im-service protos are published.
 *
 * Calls: ensureConversation, sendSystemMessage, triggerDhOpening,
 *        listOnlineUserIds, listRecentOfflineUsers.
 */
@Slf4j
@Component
public class ImServiceClient {

    /**
     * Idempotently create an IM conversation between two users.
     */
    public void ensureConversation(long userIdA, long userIdB) {
        log.debug("ensureConversation stub: {} <-> {}", userIdA, userIdB);
    }

    /**
     * Send a system message to a user.
     */
    public void sendSystemMessage(long toUserId, String payload) {
        log.debug("sendSystemMessage stub: to={}, payload={}", toUserId, payload);
    }

    /**
     * Trigger DH opening message via ai-chat.
     */
    public void triggerDhOpening(long dhUserId, long targetUserId) {
        log.debug("triggerDhOpening stub: dh={}, target={}", dhUserId, targetUserId);
    }

    /**
     * List online BH user IDs in a time window.
     * Only returns users who established a new OpenIM session in the window.
     *
     * @param sinceMs epoch ms lower bound (inclusive)
     * @param untilMs epoch ms upper bound (exclusive)
     * @param limit max results
     * @return list of online BH user IDs
     */
    public List<Long> listOnlineUserIds(long sinceMs, long untilMs, int limit) {
        log.debug("listOnlineUserIds stub: since={}, until={}, limit={}", sinceMs, untilMs, limit);
        return Collections.emptyList();
    }

    /**
     * List recently offline BH user IDs.
     * Reads from user_online_session table in im-service.
     *
     * @param sinceMs epoch ms lower bound (inclusive)
     * @param untilMs epoch ms upper bound (exclusive)
     * @param limit max results
     * @return list of recently offline BH user IDs
     */
    public List<Long> listRecentOfflineUsers(long sinceMs, long untilMs, int limit) {
        log.debug("listRecentOfflineUsers stub: since={}, until={}, limit={}", sinceMs, untilMs, limit);
        return Collections.emptyList();
    }
}
