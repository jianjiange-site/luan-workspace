package com.dating.match.grpc;

import com.dating.luan.proto.match.*;
import com.dating.match.constant.ErrorCode;
import com.dating.match.exception.BizException;
import com.dating.match.exception.GlobalExceptionHandler;
import com.dating.match.service.*;
import com.dating.match.service.FeedService.CardVO;
import com.dating.match.service.FeedService.FeedResult;
import com.dating.match.service.LikeVisitService.*;
import com.dating.match.service.QuotaService.QuotaInfo;
import com.dating.match.service.SwipeService.SuperHiResult;
import com.dating.match.service.SwipeService.SwipeResult;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

/**
 * gRPC service implementation for MatchService.
 * All 8 RPCs from match.proto are implemented here.
 *
 * Each method:
 * 1. Validates input parameters
 * 2. Delegates to the appropriate service layer
 * 3. Builds proto response (wrapping errors in BaseResponse)
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class MatchGrpcService extends MatchServiceGrpc.MatchServiceImplBase {

    private final FeedService feedService;
    private final SwipeService swipeService;
    private final QuotaService quotaService;
    private final LikeVisitService likeVisitService;

    // ──────────────────────────────────────────────
    // 1. GetTodayFeed
    // ──────────────────────────────────────────────

    /**
     * Pull the next batch of cards from today's feed.
     * Mobile side typically requests 5 cards at a time.
     */
    @Override
    public void getTodayFeed(GetTodayFeedReq request, StreamObserver<GetTodayFeedResp> responseObserver) {
        try {
            long userId = request.getUserId();
            int count = Math.min(request.getCount() > 0 ? request.getCount() : 5, 20);

            if (userId <= 0) {
                throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");
            }

            FeedResult result = feedService.getTodayFeed(userId, count);

            GetTodayFeedResp.Builder resp = GetTodayFeedResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setExhausted(result.exhausted());

            for (CardVO vo : result.cards()) {
                resp.addCards(Card.newBuilder()
                        .setTargetUserId(vo.targetUserId())
                        .setTargetUserType(vo.targetUserType())
                        .setNickname(vo.nickname())
                        .setAge(vo.age())
                        .addAllPhotoKeys(vo.photoKeys())
                        .setBio(vo.bio())
                        .setDistanceKm(vo.distanceKm())
                        .build());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(GetTodayFeedResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(GetTodayFeedResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 2. Swipe
    // ──────────────────────────────────────────────

    /**
     * Submit a LEFT or RIGHT swipe on a target user.
     * Idempotent: second swipe on same (user, target) returns the previous result.
     */
    @Override
    public void swipe(SwipeReq request, StreamObserver<SwipeResp> responseObserver) {
        try {
            long userId = request.getUserId();
            long targetUserId = request.getTargetUserId();
            int direction = request.getDirectionValue();

            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");
            if (targetUserId <= 0) throw new BizException(ErrorCode.TARGET_USER_ID_REQUIRED, "target_user_id is required");
            if (direction != 1 && direction != 2) throw new BizException(ErrorCode.INVALID_SWIPE_DIRECTION, "direction must be LEFT(1) or RIGHT(2)");

            // Target user type is not in proto request; inferred from feed LIST encoding
            // For now, default to BH(1) and let service handle
            SwipeResult result = swipeService.swipe(userId, targetUserId, direction, 1);

            responseObserver.onNext(SwipeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setMatchId(result.matchId())
                    .build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(SwipeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(SwipeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 3. SuperHi
    // ──────────────────────────────────────────────

    /**
     * Super Hi: premium instant match that bypasses normal matching rules.
     * Costs 1 subscription gift or 100 coins.
     */
    @Override
    public void superHi(SuperHiReq request, StreamObserver<SuperHiResp> responseObserver) {
        try {
            long userId = request.getUserId();
            long targetUserId = request.getTargetUserId();

            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");
            if (targetUserId <= 0) throw new BizException(ErrorCode.TARGET_USER_ID_REQUIRED, "target_user_id is required");

            SuperHiResult result = swipeService.superHi(userId, targetUserId, 1);

            responseObserver.onNext(SuperHiResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setMatchId(result.matchId())
                    .setCoinsUsed(result.coinsUsed())
                    .build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(SuperHiResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(SuperHiResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 4. ListMatches
    // ──────────────────────────────────────────────

    /**
     * List a user's match history, paginated.
     */
    @Override
    public void listMatches(ListMatchesReq request, StreamObserver<ListMatchesResp> responseObserver) {
        try {
            long userId = request.getUserId();
            int pageSize = Math.min(request.getPageSize() > 0 ? request.getPageSize() : 20, 50);

            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");

            // STUB: matches listing not yet fully implemented (requires user-service profile integration)
            // Returns empty list for now
            responseObserver.onNext(ListMatchesResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setNextPageToken("")
                    .build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ListMatchesResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(ListMatchesResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 5. GetQuota
    // ──────────────────────────────────────────────

    /**
     * Get the current daily quota status for a user.
     */
    @Override
    public void getQuota(GetQuotaReq request, StreamObserver<GetQuotaResp> responseObserver) {
        try {
            long userId = request.getUserId();
            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");

            QuotaInfo info = quotaService.getQuotaInfo(userId);

            responseObserver.onNext(GetQuotaResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setDailyRightSwipeLimit(info.dailyRightSwipeLimit())
                    .setDailyRightSwipeUsed(info.dailyRightSwipeUsed())
                    .setDailyCardLimit(info.dailyCardLimit())
                    .setDailyCardUsed(info.dailyCardUsed())
                    .setDailySuperHiLimit(info.dailySuperHiLimit())
                    .setDailySuperHiUsed(info.dailySuperHiUsed())
                    .setSuperHiCoinPrice(info.superHiCoinPrice())
                    .setSubscriptionTier(info.subscriptionTier())
                    .build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(GetQuotaResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(GetQuotaResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 6. ListLikesOfMe
    // ──────────────────────────────────────────────

    /**
     * List who liked me (paginated by liked_at DESC).
     * BH/DH distinction is hidden from the caller.
     */
    @Override
    public void listLikesOfMe(ListLikesOfMeReq request, StreamObserver<ListLikesOfMeResp> responseObserver) {
        try {
            long userId = request.getUserId();
            int pageSize = Math.min(request.getPageSize() > 0 ? request.getPageSize() : 20, 50);
            int offset = parsePageToken(request.getPageToken());

            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");

            LikesResult result = likeVisitService.listLikesOfMe(userId, pageSize, offset);

            ListLikesOfMeResp.Builder resp = ListLikesOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setNextPageToken(result.hasMore() ? String.valueOf(offset + pageSize) : "");

            for (LikeVO vo : result.likes()) {
                resp.addLikes(LikeVO.newBuilder()
                        .setFromUserId(vo.fromUserId())
                        .setNickname(vo.nickname())
                        .setAge(vo.age())
                        .addAllPhotoKeys(vo.photoKeys())
                        .setLikedAtUnixMs(vo.likedAtUnixMs())
                        .setLikeContent(vo.likeContent())
                        .build());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ListLikesOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(ListLikesOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 7. ListVisitsOfMe
    // ──────────────────────────────────────────────

    /**
     * List who visited me (paginated by visited_at DESC).
     */
    @Override
    public void listVisitsOfMe(ListVisitsOfMeReq request, StreamObserver<ListVisitsOfMeResp> responseObserver) {
        try {
            long userId = request.getUserId();
            int pageSize = Math.min(request.getPageSize() > 0 ? request.getPageSize() : 20, 50);
            int offset = parsePageToken(request.getPageToken());

            if (userId <= 0) throw new BizException(ErrorCode.USER_ID_REQUIRED, "user_id is required");

            VisitsResult result = likeVisitService.listVisitsOfMe(userId, pageSize, offset);

            ListVisitsOfMeResp.Builder resp = ListVisitsOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setNextPageToken(result.hasMore() ? String.valueOf(offset + pageSize) : "");

            for (VisitVO vo : result.visits()) {
                resp.addVisits(VisitVO.newBuilder()
                        .setFromUserId(vo.fromUserId())
                        .setNickname(vo.nickname())
                        .setAge(vo.age())
                        .addAllPhotoKeys(vo.photoKeys())
                        .setVisitedAtUnixMs(vo.visitedAtUnixMs())
                        .setVisitCount(vo.visitCount())
                        .build());
            }

            responseObserver.onNext(resp.build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ListVisitsOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(ListVisitsOfMeResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 8. RecordVisit
    // ──────────────────────────────────────────────

    /**
     * Record a profile view. Self-visit is short-circuited.
     * Write is async (fire-and-forget); failure only logs WARN.
     */
    @Override
    public void recordVisit(RecordVisitReq request, StreamObserver<RecordVisitResp> responseObserver) {
        try {
            long viewerUserId = request.getViewerUserId();
            long targetUserId = request.getTargetUserId();

            if (viewerUserId <= 0 || targetUserId <= 0) {
                throw new BizException(ErrorCode.USER_ID_REQUIRED, "viewer_user_id and target_user_id are required");
            }

            likeVisitService.recordVisit(viewerUserId, targetUserId);

            responseObserver.onNext(RecordVisitResp.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setOk(true)
                    .build());
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(RecordVisitResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setOk(false)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onNext(RecordVisitResp.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setOk(false)
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ── Helper ──

    /** Parse page_token as integer offset. "0" or empty = first page. */
    private int parsePageToken(String token) {
        if (token == null || token.isEmpty()) return 0;
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
