# 后端说明

面向要读代码、改代码或排障的人。前端部分见 [`frontend.md`](frontend.md)，部署与验收见
[`../README.md`](../README.md) 与 [`acceptance.md`](acceptance.md)。

- Java 21 + Spring Boot 3.4.13（Maven，artifact `advisory-backend`）
- PostgreSQL 16 + `pgvector` + `pg_trgm` + `ltree`，表结构由 Flyway 管理
- MinIO 存原文件与解析产物；Spring AI 只用于调用模型，不接管向量存储
- **单副本**：文档任务队列与 SSE 编排都在进程内，不要水平扩容

---

## 1. 分层与目录

每个业务模块都是 `api → service → repo` 三段式，加上 `domain`（实体/枚举）与 `dto`（进出参）。
跨模块只允许「上层调下层服务」，不跨模块直接读别人的 repository。

```
com.advisory
├── AdvisoryApplication        启动类，不含业务
├── common/                    跨模块基础设施
│   ├── api/                   ApiResponse、PageResponse（统一信封）
│   ├── config/                MinioConfig（双客户端 + 建桶）
│   └── error/                 ErrorCode、AppException、GlobalExceptionHandler
├── auth/                      登录、刷新轮换、强制改密、用户管理
│   ├── security/              SecurityConfig、JwtAuthFilter、CurrentUser 解析器、JSON 401/403
│   └── service/               AuthService、JwtService、RefreshTokenService、BootstrapAdminInitializer
├── provider/                  提供商与模型（api_key 加密存储）
│   └── service/               CryptoService(AES-GCM)、ProviderProbe、ProviderService
├── knowledge/                 知识库树、上传、解析、切分、向量化、重建、混合检索
│   ├── ingest/                TikaDocumentParser、StructureAwareChunker、ChunkWriter、
│   │                          EmbeddingClientFactory/SpringAiEmbeddingGateway
│   ├── retrieval/             RetrievalService、RetrievalJdbcRepository、RrfFusion
│   └── service/               KnowledgeBaseService、NodeService、DocumentService、
│                              DocumentIngestWorker、StorageService
├── agent/                     Agent 配置、自动版本、跨库一致性、试运行
├── chat/                      会话、消息、四阶段编排、SSE
│   ├── orchestration/         ChatOrchestrator（纯 Java，无 Spring 注解）、PromptTemplates
│   ├── model/                 ChatModelGateway 的 Spring AI 实现
│   └── service/               ChatService、ConversationLock、SseStepSink
└── audit/                     管理员只读审计
```

几条贯穿全局的取舍，读代码时先建立预期：

| 取舍 | 原因 |
|---|---|
| 会话 / 消息 / 步骤 / 检索走 `JdbcTemplate` 手写 SQL | JPA 一级缓存在同一事务里会返回旧值，CAS 状态推进和「刚写完立刻回读」都依赖真实数据库态 |
| 配置类实体（User、Agent、Model…）用 JPA | 结构稳定、以 CRUD 为主，`ddl-auto=validate` 保证实体与迁移一致 |
| 模型客户端不用 Spring AI 自动配置，按 `models` 行手工构造 | 一个进程要同时对接多个提供商；自动配置只能装一个 |
| 没有 vector-store bean | 向量检索是 `RetrievalJdbcRepository` 里的一条 SQL，Spring AI 只负责算向量 |

---

## 2. 本地启动

```bash
# 依赖服务（PG / MinIO / 建桶任务）
docker compose up -d postgres minio minio-init

# 后端（Windows 下本机 JAVA_HOME 可能指向 JDK 8，必须显式覆盖）
export JAVA_HOME='/d/Program Files/Java/jdk-21.0.9+10'
mvn -f backend/pom.xml spring-boot:run

# 前端另开一个终端
npm --prefix frontend run dev        # 5173，/api 代理到 8080
```

首次启动时的行为顺序值得知道：

1. Flyway 执行 `db/migration/`，V1 建全量表与索引；
2. Hibernate 用 `validate` 校验实体与表结构，不一致直接启动失败（这是有意的，别改成 `update`）；
3. `BootstrapAdminInitializer` 在无管理员时建 `ADMIN_USERNAME`/`ADMIN_PASSWORD`，并置 `must_change_password=true`；
4. `MinioConfig` 尝试建桶，**失败只记 WARN 不阻断启动**（本地没起 MinIO 也能把后端跑起来看别的）；
5. `DocumentIngestWorker` 的 `ApplicationRunner` 把上次进程遗留的中间态文档重置为 `PENDING` 并重新入队。

健康检查：`GET /actuator/health`（`health,info,metrics` 已暴露）。这个路径在安全链里放行。

---

## 3. 配置项

全部通过环境变量注入，默认值见 `application.yml` 与 `../.env.example`。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/advisory` / `advisory` / `advisory` | 数据源 |
| `JWT_SECRET` | `dev-only-…` | **生产必须换**，至少 32 字节 |
| `APP_ENCRYPTION_KEY` | `dev-only-…` | **生产必须换**，固定 32 字节，用于 AES-GCM 加密提供商 API Key；换掉后已存密文无法解密 |
| `MINIO_ENDPOINT` | `http://localhost:9000` | 服务端访问 MinIO 的地址（容器内是 `http://minio:9000`） |
| `MINIO_PUBLIC_ENDPOINT` | 空 | **签名用的地址**，必须浏览器可达；留空则回落到 `MINIO_ENDPOINT` |
| `MINIO_REGION` | `us-east-1` | 写死，避免签名前先向 endpoint 发一次 `?location=` 探测 |
| `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` / `MINIO_BUCKET` | `minioadmin` / `minioadmin` / `advisory` | 凭证与桶 |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | `admin` / `admin` | 仅首次启动、库中无管理员时生效 |
| `app.embedding.dimension` | `1024` | 固定维度，改它等于换掉整个向量契约 |

`app.jwt.access-ttl=PT30M`、`refresh-ttl=P7D` 写在 `application.yml` 里，没有做成环境变量。

---

## 4. 请求链路

```
HTTP
 → JwtAuthFilter              解析 Bearer，注入 CurrentUser；不抛异常，交给链尾决定
 → SecurityFilterChain        anyRequest().authenticated()；ASYNC/ERROR 派发放行
 → @PreAuthorize              方法级角色校验（ADMIN 专属接口）
 → Controller                 只做参数绑定与 HTTP 语义（202/404/信封）
 → Service                   业务规则、事务边界、异常都用 AppException 抛
 → Repository                JPA 或 JdbcTemplate
 → GlobalExceptionHandler    AppException → ErrorCode 对应的信封与状态码
```

### 统一信封

成功与失败都是 `{code, message, data}`，`code=0` 表示成功。分页把 `PageResponse` 放进 `data`：

```json
{ "code": 0, "message": "ok",
  "data": { "content": [], "page": 0, "size": 20, "total": 0, "totalPages": 0 } }
```

`page` 从 0 开始；`size` 默认 20，所有接受该参数的接口统一把上限钳到 100。

**SSE 端点例外**：`POST /api/conversations/{id}/messages` 直接返回 `text/event-stream`，
但「建立流之前」的错误仍是 JSON 信封（例如越权 404、会话忙 409）。

### 错误码

数字是与前端的契约，不可随意改。新增时按「HTTP 状态 × 100 + 序号」扩展。

| code | HTTP | 含义 |
|---|---|---|
| 40000 | 400 | `VALIDATION_FAILED` 参数/业务校验失败 |
| 40010 | 400 | `EMBEDDING_DIMENSION_MISMATCH` 向量维度与 1024 不符 |
| 40100 | 401 | `UNAUTHORIZED` 未认证、令牌失效、登录失败（三者同一文案） |
| 40300 | 403 | `FORBIDDEN` 角色不足 |
| 40301 | 403 | `PASSWORD_CHANGE_REQUIRED` 必须先改密 |
| 40400 | 404 | `RESOURCE_NOT_FOUND` 资源不存在**或无权访问** |
| 40900 | 409 | `CONFLICT` 通用冲突 |
| 40901 | 409 | `KB_NODE_NAME_DUPLICATED` 同级重名 |
| 40902 | 409 | `NODE_NOT_EMPTY` 非空节点/库不可删 |
| 40903 | 409 | `KB_IN_USE` 知识库被 Agent 引用 |
| 40904 | 409 | `MODEL_IN_USE` 模型被引用 |
| 40905 | 409 | `KB_REINDEXING` 知识库正在重建 |
| 40906 | 409 | `CONVERSATION_BUSY` 同一会话已有提问在跑 |
| 50000 | 500 | `INTERNAL_ERROR` 兜底，不泄露堆栈 |

---

## 5. 认证与授权

- **无状态**：不建会话、关 CSRF，令牌放 `Authorization: Bearer`。
- **access token 30 分钟**，过期由前端用 refresh token 换新；**refresh token 7 天且一次性**——
  每次刷新都把旧令牌标记吊销后发新的，同一枚令牌二次出现按重放处理（401）。
- **改密 / 禁用即吊销**该用户全部未过期 refresh token。
- **强制改密**：`must_change_password=true` 的用户只能访问 `me` / `change-password` / `logout`，
  其余接口一律 403 `PASSWORD_CHANGE_REQUIRED`。
- **401 / 403 也走 JSON 信封**，由 `JsonAuthenticationEntryPoint` / `JsonAccessDeniedHandler` 负责，
  不会返回 HTML 错误页。

### 权限矩阵

`ADMIN` 全量读写；`USER` 只能读业务数据与自己的会话。

| 资源 | USER | ADMIN |
|---|---|---|
| 登录 / 刷新 / 登出 | ✅ | ✅ |
| 提供商列表、本地模型列表 | ✅ 只读 | ✅ |
| 提供商/模型增删改、连通性探测、远端导入 | ❌ 403 | ✅ |
| 知识库、节点树、文档**读取** | ✅ | ✅ |
| 知识库/节点/文档增删改、上传、重试、重建 | ❌ 403 | ✅ |
| Agent 列表与详情 | ✅ | ✅ |
| Agent 增删改、版本列表、试运行 | ❌ 403 | ✅ |
| 会话与消息（自己的） | ✅ | ✅ |
| 会话与消息（他人的） | ❌ **404**（不是 403） | ✅ 只读 |
| 以他人身份提问 | ❌ 403 | ❌ 403（`ChatService.start` 显式判断） |
| 审计接口 `/api/admin/**` | ❌ 403 | ✅ |

> 「无权访问」返回 404 而不是 403，是为了不让用户靠状态码枚举出他人的会话 id。

---

## 6. 数据模型

13 张表，全部在 `db/migration/V1__init.sql`。约定：`BIGSERIAL` 主键、`timestamptz` 默认 `now()`、
枚举用 `varchar + check`（这样 JPA 能按字符串映射并通过 `ddl-auto=validate`）。

| 表 | 作用 | 关键点 |
|---|---|---|
| `users` | 用户 | `must_change_password` 默认 true |
| `model_providers` | 提供商 | `api_key_cipher` 存 AES-GCM 密文，永不回显 |
| `models` | 模型 | `capability` 为 `CHAT`/`EMBEDDING`；`dimension` 仅 embedding 有值 |
| `refresh_tokens` | 刷新令牌 | 只存哈希；`revoked_at` 非空即失效 |
| `knowledge_bases` | 知识库 | `embedding_model_id` 为 `ON DELETE RESTRICT`；`reindex_status` |
| `kb_nodes` | 节点树 | `path` 为 `ltree`，形如 `n1.n5.n9`；同级名唯一靠两条唯一索引 |
| `documents` | 文档 | 状态机见 §7；软删即 `status='DELETED'` |
| `chunks` | 分块与向量 | `embedding VECTOR(1024)`，HNSW 余弦索引 + `pg_trgm` GIN |
| `agents` | Agent 当前配置 | `current_version`；`kb_ids`/`node_scope` 为 JSONB；`deleted_at` 非空表示已软删 |
| `agent_versions` | 历史快照 | 只追加，`snapshot` 是 `AgentSnapshot` 的 JSON |
| `conversations` | 会话 | 保存 `agent_id + agent_version`，**锁定版本** |
| `messages` | 消息 | `status` 五态；`citations`/`token_usage` 为 JSONB |
| `reasoning_steps` | 推理步骤 | **主数据**，SSE 只是实时通道 |

两个容易踩的索引细节：

- **根节点重名**：`UNIQUE(kb_id, parent_id, name)` 对 `parent_id IS NULL` 无效（PostgreSQL 认为
  NULL 互不相等），所以另建了部分唯一索引 `UNIQUE(kb_id, name) WHERE parent_id IS NULL`。
- **ltree 路径**：移动节点要重写整棵子树的 `path`，用 `subpath` + `||` 拼接；`ltree` 的标签不能含
  `.`、`*`、`@` 等字符，节点 id 因此编码为 `n{id}`。

### 迁移规则

> **V1–V3 已发布并应用，禁止修改**。改已应用的文件会改变 Flyway checksum，既有环境启动即 `validate` 失败。
> 一切变更从 **V4** 起新增文件。

---

## 7. 文档入库

### 上传三步

```
1. POST /api/documents/presign   → 拿 presigned PUT URL 与 objectKey
2. PUT  <presigned URL>          → 直传 MinIO（不能带 Authorization，会破坏签名）
3. POST /api/documents           → 登记元数据，触发入库
```

应用从不自己创建桶：桶缺失属于配置错误，应该在部署时暴露（`deploy/init-bucket.sh` 与启动时的
`MinioConfig#ensureBucket` 双保险，都是幂等的）。

### 状态机

```
PENDING → PARSING → CHUNKING → EMBEDDING → READY
   ↓         ↓          ↓           ↓
                    FAILED ←────────┘
任意状态 → DELETED（软删）
```

所有状态写入都是 **CAS**：`UPDATE documents SET status=? WHERE id=? AND status=?`。
影响行数为 0 说明别的流程已经推进过这行，任务只记日志并放弃，**绝不覆盖更新的状态**。

- 解析：Tika 抽文本，产物（Markdown）写回 MinIO 的 `parsed_key`；
- 切分：`StructureAwareChunker` 按标题层级切，每块带 `heading_path`（如 `指南 > 第一章 > 1.1 背景`）；
- 向量化：按知识库的 `embedding_model_id` 批量 `embed()`，32 条一批；
- 落库：`ChunkWriter#replaceAll` 在一个事务里**整体替换**该文档的片段，先校验后删除——
  片段数与向量数不一致属内部错误（`INTERNAL_ERROR`），向量长度不对则仍是 `EMBEDDING_DIMENSION_MISMATCH`。

**零片段不算失败**：空文档、纯表格解析不出正文时会以 `READY` 结束，`chunks` 为空。

### 重启恢复

后端单副本，所以「留在 PARSING/CHUNKING/EMBEDDING 的行」必然属于一个已经死掉的进程。
启动时 `recoverInterrupted()` 把这些行批量重置为 `PENDING` 并重新入队，终态行不动。
每个阶段都幂等（解析产物覆盖写、片段用 `replaceAll`），从头重放永远安全。

### 重建索引

`POST /api/knowledge-bases/{id}/reindex` 把 `reindex_status` 置 `REINDEXING` 后立即返回 **202**，
后台重算全部向量。期间：

- 该库的**上传与检索都被拒**（检索 409 `KB_REINDEXING`）；
- 重复触发返回 409；
- 失败置 `FAILED`；`FAILED` 状态下库仍可检索（只有重建中才拦）。

---

## 8. 混合检索

一次 RETRIEVE 会跑多条独立的有序列表，每个 `(kbId, 路径, 查询文本)` 三元组一条：

```
向量路径：  ORDER BY embedding <=> :queryVector   LIMIT perKbTopK   （HNSW 余弦）
关键词路径：ORDER BY similarity(content, :q) DESC LIMIT perKbTopK   （pg_trgm）
```

再把它们统一送进 `RrfFusion`（k=60）融合：

```
score(chunk) = Σ over lists  1 / (60 + rank_in_list)
```

**保持列表彼此独立**是关键：同一片段被多条查询、多条路径命中就会累加多次，这正是想要的。
融合时按 `chunkId` 去重求和，输出截断到 `finalTopK`。

参数：`perKbTopK` 默认 20、`finalTopK` 默认 8，都收敛到 `[1, 100]`；非正数表示「用默认值」。
`kbIds` 为空直接 400。没配向量模型的知识库只贡献关键词路径。

---

## 9. 对话与编排

### 四阶段

```
PLAN（一次，产出 1–3 条查询）
  └→ RETRIEVE → REFLECT ─┐
       ↑                 │ 证据不足且未达 maxRounds
       └─────────────────┘
                         └→ ANSWER
```

`maxRounds=3` 意味着 **1 次 PLAN、3 次 RETRIEVE、3 次 REFLECT、1 次 ANSWER**。
`REFLECT` 报 `sufficient=true` 或 `round >= maxRounds` 时退出循环。

失败一律降级，不抛 500：

| 情况 | 处理 |
|---|---|
| PLAN 结构化输出解析失败 | 退回用户原始问题，流程继续 |
| REFLECT 连续两次解析失败 | 直接跳 ANSWER，标记 `truncated=true` |
| 单步超过 60s / 整体超过 180s | 带着已有证据进 ANSWER，标记 `truncated=true`；模型来不及答就用上轮草稿 |
| 一条证据都没有 | **不调模型**，返回固定话术（不得凭参数化知识作答） |

证据跨轮累积，按 `chunkId` 以首次出现顺序去重，因此正文里的 `[n]` 与 `citations[n-1]` 严格对应。

### SSE 帧序

```
meta      {messageId, conversationId}    首帧，断线重连的句柄
step      {round, phase, status, summary} × N
delta     {phase, text}                  答案增量
citations {items: [...]}
done      {messageId, status, tokenUsage, latencyMs}   终结
error     {code, message, recoverable}   终结
ping      {}                             每 15s
```

`SseEmitter` 超时 180s 与编排的整体预算对齐。改帧结构必须同时改
`chat/service/SseStepSink` 与 `frontend/src/api/sse.ts`。

### 三条不变式

1. **推理步骤是主数据**：写库的代码路径不得依赖客户端在线。用户中途关页面，答案与步骤照样落库，
   前端重进会话用 `GET /api/messages/{id}` 补齐。`ChatAccessTest#completedAnswerAndStepsSurviveDisconnect`
   就是钉这一点的。
2. **`ChatService.start` 刻意不加 `@Transactional`**：它以自动提交方式写入用户消息与 `PENDING` 的助手行，
   再把编排交给工作线程。包在事务里的话，工作线程可能在这些行可见之前启动，一个已被接受的提问会变成
   莫名其妙的失败。
3. **同一会话串行**：`ConversationLock` 是进程内锁（单副本前提），第二个并发提问直接 409
   `CONVERSATION_BUSY`，不会排队。

---

## 10. Agent 配置与版本化

- 新建即 **版本 1**：第一次编辑之前就已经打开的会话指向版本 1，这个快照必须存在才能重放。
- 每次成功 PATCH 在**同一事务**里更新当前行并追加 `current_version + 1`；版本只追加，不更新不删除。
- 会话保存 `agent_id + agent_version`，**锁定**当时配置；Agent 后来怎么改都不影响已有会话。
- 试运行（`POST /api/agents/{id}/test`）复用同一套编排，但**什么都不落库**——不建会话、不写消息、
  不存步骤。它直接调 `ChatOrchestrator` 而非 `ChatService`（后者总会建会话）。

写入前集中校验，全部以 400 拒绝：

- `kbIds` 非空，且每个 id 真实存在；
- 被引用的知识库都必须配了向量模型，**且是同一个**——这个共同模型写入 `agents.embedding_model_id`，
  运行时用来发现配置漂移；
- `nodeScope` 的键必须是 `kbIds` 子集，节点必须属于它所挂的那个库；
- `chatModelId` 存在且能力为 `CHAT`；`maxRounds` 1–6、`topK` 1–100、`temperature` 0–2。

名称重复 409；删除仍有关联会话的 Agent 也是 409（先归档会话）。

**上下线**只改 `agents.enabled`，不产生新版本。`enabled=false` 阻止**新建**会话，已经存在的会话仍按自己
锁定的版本继续可用——下线影响的是「还能不能开始新对话」，不是「旧对话是否作废」。因此：

- 对话页的可选列表用 `GET /api/agents?enabled=true`，过滤与分页在 SQL 里一起做；
- 管理页不传该参数，仍能看到已下线的条目，否则无法重新上线；
- 过滤不能放到客户端做：只取一页再 `.filter()`，一旦 Agent 总数超过一页就会漏掉在线项。

---

## 11. 安全细节

| 位置 | 措施 |
|---|---|
| 密码 | BCrypt（全平台统一） |
| 令牌 | JWT HS256，密钥来自 `JWT_SECRET`；refresh token 只存哈希 |
| 提供商密钥 | AES-GCM，随机 IV 每次加密，密文存 `api_key_cipher`；**响应只回掩码**（前四位），日志不落 |
| 登录失败 | 用户不存在 / 被禁用 / 密码错**同一文案**，避免枚举用户名 |
| 越权 | 他人资源一律 404 |
| 上传 | presign 直传，服务端不过手中转；PUT 不带 `Authorization` |
| 审计 | 打开会话详情写一行 `adminAudit` 日志 |

---

## 12. 测试

`mvn -f backend/pom.xml test` → **197 用例**，需要 Docker（Testcontainers 起 PG + MinIO）。

| 类型 | 位置 | 做法 |
|---|---|---|
| 纯单元 | `*-Test`（ChunkerTest、ChatOrchestratorTest、ProviderProbeClassifyTest…） | 不联网、不起 Spring，用假实现驱动 |
| Web 切片 | `GlobalExceptionHandlerTest`、`ApiResponseTest` | MockMvc + 测试专用 `TestController` |
| 集成 | `*Test` 继承 `IntegrationTestBase` | 真实 PG/MinIO，容器**每 JVM 只启一次** |

集成的两个关键设计：

- **容器不按类销毁**：Spring 上下文（及其解析出的数据源 URL）跨测试类缓存，销毁容器会让缓存的数据源
  指向死端口。回收交给 Testcontainers 的 Ryuk。
- **模型全部打桩**：`TestEmbeddingGatewayConfig` / `TestChatModelGatewayConfig` 是
  `com.advisory` 包下的普通 `@Configuration`（不是 `@TestConfiguration`，后者会被组件扫描排除），
  配合 `app.embedding.gateway=stub` / `app.chat.gateway=stub`，保证**任何用例都不会真的调提供商**。

夹具：`AdvisoryFixture`（两个用户 + 一个提供商 + 一个知识库 + 一个 Agent）、`EmbeddingModelFixture`。
数据行前缀 `fx-`，`@AfterEach` 按依赖顺序清（先 agent/知识库，再提供商）。

写新集成用例时踩过的坑：外键是 `ON DELETE RESTRICT`，**清理顺序错了会直接失败**；
整套件共用一个数据库，不要改动引导管理员 `admin`。

---

## 13. 常见改动指引

**加一个接口**

1. `api/XxxController` 加方法，返回 `ApiResponse`/`PageResponse`；
2. 需要管理员就加 `@PreAuthorize("hasRole('ADMIN')")`；
3. 业务异常抛 `new AppException(ErrorCode.XXX, "中文提示")`，不要自己拼 `ResponseEntity`；
4. 补集成用例（权限矩阵那几条尤其别漏）。

**加错误码**

1. `ErrorCode` 里按「HTTP 状态 × 100 + 序号」加；
2. 前端 `ApiError.code` 会拿到它，需要专门提示就在前端加映射；
3. 更新本文 §4 与 `AGENTS.md` 的错误码说明。

**加迁移**

新建 `V4__xxx.sql`。**不要动 V1–V3**。加完跑一次 `mvn test`，`ddl-auto=validate` 会替你校验实体。

**接新提供商类型**

1. `ProviderType` 加枚举；
2. `ProviderProbe` 里补 endpoint 拼装与请求头规则（OLLAMA 走 `/api/tags` 且不带 Authorization）；
3. `EmbeddingClientFactory` / `ChatClientFactory` 里补客户端构造；
4. `V1` 的 check 约束不能改，需要放开就得靠新迁移 `ALTER TABLE ... DROP CONSTRAINT`。

---

## 14. 已知限制

- **单副本**：进程内锁、进程内队列、进程内 SSE。扩容前必须先把文档任务与编排挪出进程。
- **access token 无法即时失效**：登出只吊销 refresh token，access token 靠 30 分钟有效期自然收敛。
- **检索质量未调优**：RRF 的 k=60 与 topK 默认值都是常用起点，没有针对中文语料做过评测。
- **解析只覆盖 Tika 支持的格式**，且按字符数（不是 token 数）切分，`token_count` 是估算值。
- **审计只读**：没有导出、没有保留期策略。
