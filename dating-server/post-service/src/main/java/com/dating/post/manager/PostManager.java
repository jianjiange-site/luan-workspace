package com.dating.post.manager;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dating.post.entity.Post;
import com.dating.post.entity.PostImage;
import com.dating.post.mapper.PostImageMapper;
import com.dating.post.mapper.PostMapper;
import com.dating.post.constant.PostStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Single-table CRUD for posts and post_images. No cross-table JOINs.
 * PostStat insertion is done separately in PostWriteService @Transactional.
 */
@Slf4j
@Component
public class PostManager {

    private final PostMapper postMapper;
    private final PostImageMapper postImageMapper;

    public PostManager(PostMapper postMapper, PostImageMapper postImageMapper) {
        this.postMapper = postMapper;
        this.postImageMapper = postImageMapper;
    }

    /**
     * Insert a post row.
     */
    public int insert(Post post) {
        return postMapper.insert(post);
    }

    /**
     * Batch insert image records for a post.
     */
    @Transactional
    public void insertImages(Long postId, List<String> imageKeys) {
        for (int i = 0; i < imageKeys.size(); i++) {
            PostImage img = new PostImage();
            img.setPostId(postId);
            img.setSortOrder(i);
            img.setImageKey(imageKeys.get(i));
            postImageMapper.insert(img);
        }
    }

    /**
     * Find post by business primary key (post_id), excluding soft-deleted.
     */
    public Post findByPostId(Long postId) {
        LambdaQueryWrapper<Post> q = new LambdaQueryWrapper<>();
        q.eq(Post::getPostId, postId)
         .eq(Post::getDeleted, 0);
        return postMapper.selectOne(q);
    }

    /**
     * Find post images ordered by sort_order for a given post_id.
     */
    public List<PostImage> findImagesByPostId(Long postId) {
        LambdaQueryWrapper<PostImage> q = new LambdaQueryWrapper<>();
        q.eq(PostImage::getPostId, postId)
         .orderByAsc(PostImage::getSortOrder);
        return postImageMapper.selectList(q);
    }

    /**
     * Soft-delete a post by setting deleted=1 and status=DELETED.
     * Permission check (owner == userId) must be done by the caller.
     */
    public int softDelete(Long postId) {
        Post post = new Post();
        post.setDeleted(1);
        post.setStatus(PostStatus.DELETED);
        LambdaQueryWrapper<Post> q = new LambdaQueryWrapper<>();
        q.eq(Post::getPostId, postId);
        return postMapper.update(post, q);
    }

    /**
     * Cursor-based pagination for a user's posts.
     * Ordered by post_id DESC (snowflake IDs are roughly time-ordered).
     * cursor=0 means first page.
     */
    public List<Post> listByUserId(Long userId, int pageSize, long cursor) {
        LambdaQueryWrapper<Post> q = new LambdaQueryWrapper<>();
        q.eq(Post::getUserId, userId)
         .eq(Post::getDeleted, 0)
         .eq(Post::getStatus, PostStatus.NORMAL);
        if (cursor > 0) {
            q.lt(Post::getPostId, cursor);
        }
        q.orderByDesc(Post::getPostId);
        q.last("LIMIT " + pageSize);
        return postMapper.selectList(q);
    }

    /**
     * Fetch all normal posts within the last N days for FeedScoreJob pool rebuild.
     * Single-table query on posts only — stats are fetched separately via selectBatchIds.
     */
    public List<Post> findRecentNormalPosts(int days) {
        LambdaQueryWrapper<Post> q = new LambdaQueryWrapper<>();
        q.eq(Post::getDeleted, 0)
         .eq(Post::getStatus, PostStatus.NORMAL)
         .ge(Post::getCreatedAt, "NOW() - INTERVAL '" + days + " days'");
        return postMapper.selectList(q);
    }
}
