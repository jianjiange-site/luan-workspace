# post-service 代码导航

> 每个文件的职责、上下游依赖、关联设计文档章节。阅读顺序自上而下，先骨架后血肉。

---

## 1. 项目根（构建 & 部署）

### `pom.xml`
Maven 项目文件。定义所有依赖（Spring Boot 3.3.5 / MyBatis-Plus 3.5.9 / gRPC / Redisson / RocketMQ / ShedLock / Caffeine / Flyway / protobuf-java-util），从 Nexus `maven-public` 拉 `com.dating.luan.proto:post-proto:0.1.0` 生成 gRPC stub。

### `Dockerfile`
多阶段 Docker 构建：`maven:3.9-temurin-21-alpine` 编译 → `temurin:21-jre-alpine` 运行时。设置 `TZ=UTC`，暴露 8080（HTTP）/ 9090（gRPC）。

### `.dockerignore`
排除 `target/`、`.env*`、IDE 配置等不进镜像的文件。

---

## 2. 配置

### `src/main/resources/application.yml`
Spring Boot 默认配置（所有 profile 共用）：服务名 `dating-post-service`、端口、gRPC server/client、MyBatis-Plus 逻辑删除、Flyway `flyway_history_post` 表、RocketMQ 默认连接、`app.*` 业务参数（feed 池大小 / 布隆容量 / timeline 上限）、`dating.object-storage` MinIO 默认值、日志级别。

### `src/main/resources/application-dev.yml`
`dev` profile 覆盖：连远端 `38.76.188.242` 的 PG / Redis / Nacos / RocketMQ / MinIO。Nacos namespace `dev-luan`，所有密码用 `${ENV}` 占位（真值在 Nacos 或 IDE Run Configuration）。上一份 `application.yml` 会 `spring.config.import: nacos:dating-post-service-dev.yaml` 拉 Nacos 远程配置合并。

---

## 3. 启动入口

### `PostApplication.java`
`@SpringBootApplication` 入口。`main()` 一行 `SpringApplication.run(...)`。定时任务由 `SchedulerConfig` 开启（`@EnableScheduling` 在配置类上，不在主类上）。

---

## 4. 数据层（`entity/` + `mapper/` + Flyway）

| 文件 | 对应表 | 说明 |
|---|---|---|
| `entity/Post.java` | `posts` | 帖子主表：`post_id`（雪花，业务主键）、`user_id`、`content`、`status`、`deleted`（`@TableLogic`）、`created_at/updated_at` |
| `entity/PostImage.java` | `post_images` | 图片记录：联合主键 `(post_id, sort_order)`，`image_key` 只存对象存储 key |
| `entity/PostStat.java` | `post_stats` | 计数底座：`like_count`、`comment_count`（只存已刷盘部分，实时值 = DB + Redis 增量） |
| `entity/PostLike.java` | `post_likes` | 点赞幂等记录：联合主键 `(user_id, post_id)`，`status` 1=赞/0=取消，无自增 id |
| `entity/PostComment.java` | `post_comments` | 评论：`comment_id`（雪花）、`root_id`/`parent_id`/`reply_to_user_id`（预留楼中楼） |
| `mapper/PostMapper.java` | — | `BaseMapper<Post>`，单表 CRUD |
| `mapper/PostImageMapper.java` | — | `BaseMapper<PostImage>`，单表 CRUD |
| `mapper/PostStatMapper.java` | — | `BaseMapper<PostStat>` + `incrementLikeCount(postId, delta)` / `incrementCommentCount(postId, delta)` 增量更新 SQL |
| `mapper/PostLikeMapper.java` | — | `BaseMapper<PostLike>` + `upsert(userId, postId, status)` 方法声明 |
| `mapper/PostCommentMapper.java` | — | `BaseMapper<PostComment>`，单表 CRUD |
| `resources/mapper/PostLikeMapper.xml` | — | `upsert` 的 XML SQL：`INSERT ... ON CONFLICT (user_id, post_id) DO UPDATE SET status = EXCLUDED.status WHERE post_likes.status <> EXCLUDED.status` |
| `resources/db/migration/V20260615_01__init_post_tables.sql` | — | Flyway 初始迁移：创建 5 张业务表 + 索引 + `shedlock` 表。全部 `TIMESTAMPTZ`、业务主键带唯一索引 |

---

## 5. 基础设施配置（`config/`）

### `SnowflakeIdGenerator.java`
雪花 ID 生成器。`workerId` 从 Nacos `app.snowflake.worker-id` 注入。`nextId()` 返回全局唯一 `post_id` / `comment_id`，`synchronized` 保证单实例内不重复。参考设计文档 §9 步骤 2。

### `RedisConfig.java`
`StringRedisTemplate` Bean，UTF-8 序列化。持有 `keyPrefix`（`luan`），供 `CacheKeyBuilder` 拼 Redis key。

### `RedissonConfig.java`
`RedissonClient` Bean，连同一台 Redis（host/port/password/database 从 `application*.yml` 读取）。用于分布式锁（`luan:lock:post:*`）和布隆过滤器（`luan:user:read:bloom:*`）。

### `GrpcClientConfig.java`
gRPC 客户端 stub 注入的占位类。`@GrpcClient("user-service")` 注入逻辑在 `UserClient` 中直接完成，本类为将来扩展留空。

### `SchedulerConfig.java`
`@EnableScheduling` + `@EnableSchedulerLock`。ShedLock `LockProvider` 走 JDBC（`shedlock` 表），多实例部署时保证同名 Job 只有一个实例跑。

---

## 6. 常量 & 异常（`constant/` + `exception/`）

### `constant/PostStatus.java`
帖子状态常量：`DELETED=0`、`NORMAL=1`、`REVIEWING=2`。

### `constant/LikeStatus.java`
点赞状态常量：`UNLIKED=0`、`LIKED=1`。

### `constant/ErrorCode.java`
业务错误码（放在 `BaseResponse.code` 里，gRPC status 一律 OK）：`0` OK / `4001-4008` 参数校验 / `4030` 权限 / `5000` 内部错误。与 `post.proto` 末尾注释保持一致。

### `exception/BizException.java`
`RuntimeException` 子类，携 `int code` + `String message`。service 层遇到业务错误直接抛，由 `GlobalExceptionHandler` 转 `BaseResponse`。

### `exception/GlobalExceptionHandler.java`
静态工具类，不依赖 Spring 容器：
- `toBaseResponse(BizException)` → `BaseResponse{code, message}`
- `toBaseResponse(Exception)` → `BaseResponse{code=5000, message="Internal server error"}`，并 `log.error`（带堆栈）
- `success()` → `BaseResponse{code=0, message="ok"}`

---

## 7. 公共工具（`common/`）

### `CacheKeyBuilder.java`
Redis key 工厂。全部 key 以 `luan:` 开头（从 `app.cache.key-prefix` 注入），对外暴露 15 个 key 模式：

| 方法 | Key 模式 | 类型 | 用途 |
|---|---|---|---|
| `postDetail(postId)` | `luan:post:detail:{post_id}` | Hash | 帖子详情缓存（TTL 7d） |
| `statIncrLikes(postId)` | `luan:post:stat:incr:{post_id}:likes` | String(Int) | 未刷盘点增量 |
| `statIncrComments(postId)` | `luan:post:stat:incr:{post_id}:comments` | String(Int) | 评论未刷盘增量 |
| `postUpdatedSet()` | `luan:post:updated_set` | Set | 待刷盘的 post_id 集合 |
| `postComments(postId)` | `luan:post:comments:{post_id}` | ZSet | 最新 200 条评论 |
| `userTimeline(userId)` | `luan:user:timeline:{user_id}` | ZSet | 好友时间线（≤100 条） |
| `feedPoolRecommendMale()` | `luan:feed:pool:recommend:male` | ZSet | 男性发的帖（女性看） |
| `feedPoolRecommendFemale()` | `luan:feed:pool:recommend:female` | ZSet | 女性发的帖（男性看） |
| `feedColdStartMale()` | `luan:feed:cold_start:pool:male` | ZSet | 男性发的帖冷启动 |
| `feedColdStartFemale()` | `luan:feed:cold_start:pool:female` | ZSet | 女性发的帖冷启动 |
| `userReadBloom(userId)` | `luan:user:read:bloom:{user_id}` | BloomFilter | 已读去重 |
| `lockPost(resource)` | `luan:lock:post:{resource}` | — | 分布式锁前缀 |

### `RedisScripts.java`
预编译 Lua 脚本：
- `getAndSetZero()` — 原子 `GET + SET 0`，LikeFlushJob / CommentFlushJob 用于取走增量并归零，保证并发安全

---

## 8. 跨服务调用（`client/`）

### `UserClient.java`
`user-service` 的 gRPC 客户端封装。**当前为桩实现**（设计文档 §11）：
- `getFriendUserIds(userId)` → 返回空列表（写扩散 no-op）
- `isMale(userId)` → `userId % 2 == 0`（测试用）
- `getGenders(List<Long>)` → 同上，Caffeine 30s 本地缓存削峰

等 `user-service` 实现好友列表 + 性别字段后，替换 stub 方法体为真实 gRPC 调用即可。

---

## 9. 消息队列（`mq/`）

### `mq/producer/FanoutMessage.java`
RocketMQ 消息体 record：`postId`、`authorUserId`、`createdAtEpoch`。只放索引，Consumer 按需拉 followers（避免 Producer 提前拉又过期）。

### `mq/producer/PostFanoutProducer.java`
发帖事务 COMMIT 后同步调 `rocketMQTemplate.syncSend()` 到 `youjianxin-dating-dev-post-fanout-v1`。本地 retry 3 次（timeout 2s/次），全部失败只 `log.error` 不抛异常、不阻塞发帖返回。失败后 5 分钟内全网热门池 ① 重建可兜底。

### `mq/consumer/PostFanoutConsumer.java`
`@RocketMQMessageListener`，CONCURRENTLY 并发消费，`maxReconsumeTimes=16`。消费逻辑：
1. `UserClient.getFriendUserIds(authorUserId)` 拉关注者
2. 每个 follower 的 `luan:user:timeline:{follower}` ZADD `(createdAtEpoch, postId)`
3. 裁剪到 100 条（`ZREMRANGEBYRANK` 去旧留新）
4. 设 TTL 7d

失败自动重投，16 次后进 DLQ。ZADD 幂等（同 score+member 覆盖无副作用）。

---

## 10. 数据访问编排（`manager/`）

调用方向：`grpc → service → manager → mapper`（严格单向，manager 不调 service）

### `PostManager.java`
`posts` + `post_images` 的单表读写：
- `insert(Post)` / `findByPostId(postId)` / `softDelete(postId)`
- `insertImages(postId, imageKeys)` — `@Transactional` 批量插入
- `findImagesByPostId(postId)` — 按 `sort_order` 升序
- `listByUserId(userId, pageSize, cursor)` — 游标分页，`post_id DESC`
- `findRecentNormalPosts(days)` — FeedScoreJob 用，捞出近 N 天所有正常帖

### `PostStatManager.java`
`post_stats` 的单表读写：
- `insert(PostStat)` / `findByPostId(postId)` / `findByPostIds(List)`（batch）
- `incrementLikeCount(postId, delta)` / `incrementCommentCount(postId, delta)` — `UPDATE ... SET xx = xx + delta`

### `PostLikeManager.java`
点赞 upsert 的薄封装，委托 `PostLikeMapper.upsert()`，返回受影响行数（0=幂等，1=状态改变）。

### `PostCommentManager.java`
`post_comments` 的单表读写 + Redis ZSet 操作：
- `insert(PostComment)` / `findByCommentId(commentId)` / `softDelete(commentId)`
- `addToZSet(zsetKey, commentId)` — ZADD + 裁剪到 200 + TTL
- `removeFromZSet(zsetKey, commentId)` — ZREM
- `getCommentIdsFromZSet(zsetKey, cursor, pageSize)` — ZREVRANGEBYSCORE 游标翻页
- `listFromDb(postId, cursor, pageSize)` — DB 冷路回源（翻到 ZSet 窗口外时）

---

## 11. 业务编排（`service/`）

调用方向：`grpc → service → manager/mq/client`。`@Transactional` 只管 DB 写，不嵌套远程调用。

### `PostWriteService.java`
发帖 + 删帖：
- `createPost(content, imageKeys, userId)` — 校验 → 雪花 ID → `@Transactional` 写 `posts + post_images + post_stats` → Redis 缓存详情 → ZADD 冷启动池（按作者性别）→ RocketMQ fanout → 返回 post_id。事务外步骤（Redis/MQ/池）失败不回滚 DB
- `deletePost(postId, userId)` — 权限校验 → 软删 → DEL 缓存 → ZREM 冷启动池 → SREM updated_set

### `PostReadService.java`
读帖子：
- `getPostDetail(postId)` — Redis Hash 优先 → DB 回源 → 回填缓存。返回 `PostDetail` DTO 含实时计数（DB 基准 + Redis 增量）
- `listUserPosts(targetUserId, pageSize, cursor)` — 游标分页，返回 `UserPostsResult(items, nextCursor, hasMore)`
- `getPostDetailSafe(postId)` — Feed 用，try-catch 跳过已删帖

### `LikeService.java`
点赞/取消：
- `actionLike(userId, postId, liked)` — DB upsert（幂等，受影响行=0 直接返回）→ Redis INCR/DECR 增量 → SADD updated_set。返回 true/false 表示状态是否变化

### `CommentService.java`
评论 CUD + 列表：
- `createComment(...)` — 校验 → 雪花 ID → INSERT → ZADD+裁剪 → INCR Redis 增量 → SADD updated_set
- `listComments(postId, pageSize, cursor)` — ZSet 优先（最新 200 窗口）→ DB 冷路回源。返回 `CommentListResult`
- `deleteComment(commentId, userId)` — 权限校验 → 软删 → ZREM → DECR 增量

### `FeedService.java`
推荐 Feed（本服务最复杂的业务逻辑）：
- `getRecommendFeed(userId, pageSize, cursor)` — 解析 cursor `"recOffset:csOffset"` → 得用户性别 → 取异性池 → 并行三路拉取（① 推荐池 ZREVRANGE ② 好友时间线 ZREVRANGEBYSCORE ③ 冷启动池 ZREVRANGE）→ 布隆去重 → `mergeThreeWay` 按位插入（1/2/4/5/7/8/9/10=推荐，3=好友强插，6=冷启动）→ 同好友频控 ≤1 条/页
- `rebuildRecommendPool()` — FeedScoreJob 调用：捞近 3 天帖 → batch 取 stat（单表无 JOIN）→ Redis 增量补偿 → Hacker News 公式打分 → 性别分桶（UserClient.getGenders）→ 影子写 tmp ZSet → 裁剪 Top 3000 → `RENAME` 原子切换

---

## 12. 定时任务（`job/`）

三个 `@Scheduled` Job，全部带 `@SchedulerLock` 互斥。设计文档 §6.2 / §6.4 / §10.2.1。

### `LikeFlushJob.java`
每分钟：`SRANDMEMBER updated_set 100` → 对每个 post_id 执行 Lua `GET+SET 0`（原子取走增量）→ `UPDATE post_stats SET like_count = like_count + delta` → `SREM updated_set`

### `CommentFlushJob.java`
每分钟：同 LikeFlushJob 模式，处理 `luan:post:stat:incr:{pid}:comments`。

### `FeedScoreJob.java`
每 5 分钟：调 `FeedService.rebuildRecommendPool()` 全量重建推荐池。lockAtMostFor 10 分钟（留足时间）。

---

## 13. gRPC 层（`grpc/`）

### `RequestContext.java`
`ThreadLocal<Long>` 持有当前请求的 `user_id`。由 `GrpcServerInterceptor` 设入，`PostGrpcService` 的 RPC 方法通过 `requireUserId()` 取（无值时抛异常）。请求结束后 interceptor 自动清理。

### `GrpcServerInterceptor.java`
`@GrpcGlobalServerInterceptor`：每个 gRPC 请求进来时从 Metadata 读 `x-user-id`（mobile-gateway JWT 解后注入），解析为 `Long` 设入 `RequestContext`。响应 close 时清理 ThreadLocal。

### `PostGrpcService.java`
继承 `PostServiceGrpc.PostServiceImplBase`，实现全部 9 个 RPC。每个方法结构相同：
1. 从 `RequestContext` 取 `user_id`
2. 调对应 service 方法
3. 将 service 返回的 DTO 转为 proto message
4. 组装 `BaseResponse` + 业务数据

RPC 与 service 映射：

| RPC | 对应 Service 方法 |
|---|---|
| `CreatePost` | `PostWriteService.createPost()` |
| `GetPostDetail` | `PostReadService.getPostDetail()` |
| `ListUserPosts` | `PostReadService.listUserPosts()` |
| `ActionLike` | `LikeService.actionLike()` |
| `CreateComment` | `CommentService.createComment()` |
| `ListComments` | `CommentService.listComments()` |
| `DeleteComment` | `CommentService.deleteComment()` |
| `DeletePost` | `PostWriteService.deletePost()` |
| `GetRecommendFeed` | `FeedService.getRecommendFeed()` |

所有异常（`BizException` / `Exception`）在 RPC 方法内 try-catch，转 `BaseResponse{code != 0}` 后以 gRPC OK 状态返回。

---

## 14. 调用链全景

```
gRPC Metadata (x-user-id)
        │
        ▼
GrpcServerInterceptor → RequestContext.setUserId()
        │
        ▼
PostGrpcService (9 RPCs)
        │
        ├── PostWriteService ──┬── PostManager ──── PostMapper / PostImageMapper
        │                      ├── PostStatManager ── PostStatMapper
        │                      ├── StringRedisTemplate (cache / ZADD cold-start)
        │                      └── PostFanoutProducer ── RocketMQ ── PostFanoutConsumer
        │                                                              │
        ├── PostReadService ────┬── PostManager                       ├── UserClient.getFriendUserIds()
        │                       ├── PostStatManager                   └── StringRedisTemplate (ZADD timeline)
        │                       └── StringRedisTemplate (cache + delta)
        │
        ├── LikeService ────────┬── PostManager (verify exists)
        │                       ├── PostLikeManager ── PostLikeMapper (upsert)
        │                       └── StringRedisTemplate (INCR delta + SADD updated_set)
        │
        ├── CommentService ─────┬── PostManager (verify exists)
        │                       ├── PostCommentManager ──┬── PostCommentMapper (DB)
        │                       │                        └── StringRedisTemplate (ZSet)
        │                       └── StringRedisTemplate (INCR delta + SADD updated_set)
        │
        └── FeedService ────────┬── PostManager (findRecentNormalPosts)
                                ├── PostStatManager (selectBatchIds)
                                ├── PostReadService (getPostDetailSafe)
                                ├── UserClient (getGenders / isMale)
                                ├── RedissonClient (BloomFilter)
                                └── StringRedisTemplate (ZREVRANGE / ZADD / RENAME)
```

---

> 关联文档：
> - 业务设计：[`post-service-design.md`](post-service-design.md)
> - 开发总规范：[`student-dev-guide.md`](student-dev-guide.md)
> - 环境接入：[`dev-onboarding.md`](dev-onboarding.md)
> - 项目约束：[`CLAUDE.md`](../CLAUDE.md)
