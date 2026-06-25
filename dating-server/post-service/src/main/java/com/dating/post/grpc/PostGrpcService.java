package com.dating.post.grpc;

import com.dating.luan.proto.post.*;
import com.dating.post.constant.ErrorCode;
import com.dating.post.exception.BizException;
import com.dating.post.exception.GlobalExceptionHandler;
import com.dating.post.service.*;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.Collections;
import java.util.List;

/**
 * gRPC service implementation for all 9 post-service RPCs.
 * <p>
 * Each method:
 *   1. Extracts user_id from RequestContext (injected by mobile-gateway via GrpcServerInterceptor)
 *   2. Validates input
 *   3. Delegates to the appropriate service
 *   4. Builds the proto response with BaseResponse
 * <p>
 * All RPCs return gRPC status OK. Business errors are communicated via BaseResponse.code.
 */
@Slf4j
@GrpcService
public class PostGrpcService extends PostServiceGrpc.PostServiceImplBase {

    private final PostWriteService postWriteService;
    private final PostReadService postReadService;
    private final LikeService likeService;
    private final CommentService commentService;
    private final FeedService feedService;

    public PostGrpcService(PostWriteService postWriteService,
                           PostReadService postReadService,
                           LikeService likeService,
                           CommentService commentService,
                           FeedService feedService) {
        this.postWriteService = postWriteService;
        this.postReadService = postReadService;
        this.likeService = likeService;
        this.commentService = commentService;
        this.feedService = feedService;
    }

    // ──────────────────────────────────────────────
    // 1. CreatePost
    // ──────────────────────────────────────────────
    @Override
    public void createPost(CreatePostRequest request, StreamObserver<CreatePostResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            List<String> imageKeys = request.getImageKeysList();
            long postId = postWriteService.createPost(request.getContent(), imageKeys, userId);

            CreatePostResponse response = CreatePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setPostId(postId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(CreatePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreatePost failed", e);
            responseObserver.onNext(CreatePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 2. GetPostDetail
    // ──────────────────────────────────────────────
    @Override
    public void getPostDetail(GetPostDetailRequest request, StreamObserver<GetPostDetailResponse> responseObserver) {
        try {
            PostReadService.PostDetail detail = postReadService.getPostDetail(request.getPostId());

            PostInfo postInfo = toProtoPostInfo(detail);
            GetPostDetailResponse response = GetPostDetailResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setPost(postInfo)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(GetPostDetailResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetPostDetail failed", e);
            responseObserver.onNext(GetPostDetailResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 3. ListUserPosts
    // ──────────────────────────────────────────────
    @Override
    public void listUserPosts(ListUserPostsRequest request, StreamObserver<ListUserPostsResponse> responseObserver) {
        try {
            PostReadService.UserPostsResult result = postReadService.listUserPosts(
                    request.getUserId(), request.getPageSize(), request.getCursor());

            List<PostInfo> postInfos = result.items().stream()
                    .map(this::toProtoPostInfo)
                    .toList();

            ListUserPostsResponse response = ListUserPostsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .addAllItems(postInfos)
                    .setNextCursor(result.nextCursor())
                    .setHasMore(result.hasMore())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ListUserPostsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListUserPosts failed", e);
            responseObserver.onNext(ListUserPostsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 4. ActionLike
    // ──────────────────────────────────────────────
    @Override
    public void actionLike(ActionLikeRequest request, StreamObserver<ActionLikeResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            boolean liked = request.getAction() == LikeAction.LIKE;
            boolean changed = likeService.actionLike(userId, request.getPostId(), liked);

            ActionLikeResponse response = ActionLikeResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setSuccess(changed)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ActionLikeResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ActionLike failed", e);
            responseObserver.onNext(ActionLikeResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 5. CreateComment
    // ──────────────────────────────────────────────
    @Override
    public void createComment(CreateCommentRequest request, StreamObserver<CreateCommentResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            long commentId = commentService.createComment(
                    userId, request.getPostId(), request.getContent(),
                    request.getRootId(), request.getParentId(), request.getReplyToUserId());

            CreateCommentResponse response = CreateCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setCommentId(commentId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(CreateCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("CreateComment failed", e);
            responseObserver.onNext(CreateCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 6. ListComments
    // ──────────────────────────────────────────────
    @Override
    public void listComments(ListCommentsRequest request, StreamObserver<ListCommentsResponse> responseObserver) {
        try {
            CommentService.CommentListResult result = commentService.listComments(
                    request.getPostId(), request.getPageSize(), request.getCursor());

            List<CommentInfo> commentInfos = result.items().stream()
                    .map(c -> CommentInfo.newBuilder()
                            .setCommentId(c.commentId())
                            .setPostId(c.postId())
                            .setUserId(c.userId())
                            .setRootId(c.rootId())
                            .setParentId(c.parentId())
                            .setReplyToUserId(c.replyToUserId())
                            .setContent(c.content())
                            .setStatus(c.status())
                            .setCreatedAt(c.createdAt())
                            .build())
                    .toList();

            ListCommentsResponse response = ListCommentsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .addAllItems(commentInfos)
                    .setNextCursor(result.nextCursor())
                    .setHasMore(result.hasMore())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(ListCommentsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("ListComments failed", e);
            responseObserver.onNext(ListCommentsResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 7. DeleteComment
    // ──────────────────────────────────────────────
    @Override
    public void deleteComment(DeleteCommentRequest request, StreamObserver<DeleteCommentResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            commentService.deleteComment(request.getCommentId(), userId);

            DeleteCommentResponse response = DeleteCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(DeleteCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setSuccess(false)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("DeleteComment failed", e);
            responseObserver.onNext(DeleteCommentResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setSuccess(false)
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 8. DeletePost
    // ──────────────────────────────────────────────
    @Override
    public void deletePost(DeletePostRequest request, StreamObserver<DeletePostResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            postWriteService.deletePost(request.getPostId(), userId);

            DeletePostResponse response = DeletePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .setSuccess(true)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (BizException e) {
            responseObserver.onNext(DeletePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setSuccess(false)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("DeletePost failed", e);
            responseObserver.onNext(DeletePostResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .setSuccess(false)
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // 9. GetRecommendFeed
    // ──────────────────────────────────────────────
    @Override
    public void getRecommendFeed(GetRecommendFeedRequest request, StreamObserver<GetRecommendFeedResponse> responseObserver) {
        try {
            long userId = RequestContext.requireUserId();
            FeedService.FeedResult result = feedService.getRecommendFeed(
                    userId, request.getPageSize(), request.getCursor());

            List<PostInfo> postInfos = result.items().stream()
                    .map(this::toProtoPostInfo)
                    .toList();

            GetRecommendFeedResponse response = GetRecommendFeedResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.success())
                    .addAllItems(postInfos)
                    .setNextCursor(result.nextCursor())
                    .setHasMore(result.hasMore())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetRecommendFeed failed", e);
            responseObserver.onNext(GetRecommendFeedResponse.newBuilder()
                    .setBase(GlobalExceptionHandler.toBaseResponse(e))
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ──────────────────────────────────────────────
    // Proto mapping helpers
    // ──────────────────────────────────────────────

    private PostInfo toProtoPostInfo(PostReadService.PostDetail detail) {
        return PostInfo.newBuilder()
                .setPostId(detail.postId())
                .setUserId(detail.userId())
                .setContent(detail.content())
                .addAllImageKeys(detail.imageKeys() != null ? detail.imageKeys() : Collections.emptyList())
                .setLikeCount(detail.likeCount())
                .setCommentCount(detail.commentCount())
                .setCreatedAt(detail.createdAt())
                .setStatus(detail.status())
                .build();
    }
}
