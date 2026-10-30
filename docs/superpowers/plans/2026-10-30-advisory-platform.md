# 智能咨询平台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付一个可部署的智能咨询平台：树状知识库、多模型提供商、可版本化的 Agent 配置，以及带实时推理过程与引用的多步问答。

**Architecture:** 单体 Spring Boot 后端（按领域分包）+ Vue SPA。文档解析/向量化在进程内线程池执行，向量与业务数据同存 PostgreSQL（pgvector + pg_trgm + ltree），原始文件存 MinIO，全部服务由 Docker Compose 编排。问答为固定四阶段状态机（PLAN → RETRIEVE → REFLECT → ANSWER），步骤落库并同时通过 SSE 推送。

**Tech Stack:** Java 21、Spring Boot 3.4.x、Spring AI 1.0.x、Spring Data JPA + JdbcTemplate、Flyway、PostgreSQL 16 + pgvector + pg_trgm + ltree、MinIO、Apache Tika、Vue 3 + Vite + TypeScript + Pinia + Element Plus、Vitest、Testcontainers、Docker Compose。

**Spec:** `docs/superpowers/specs/2026-10-30-advisory-platform-design.md`

## Global Constraints

- 包名 `com.advisory`；后端目录 `backend/`，Maven artifact `advisory-backend`；前端目录 `frontend/`。
- 所有 HTTP API 前缀 `/api`，响应体 `{ code, message, data }`；分页参数 `page`（0 基）、`size`（默认 20，上限 100）。
- 时间统一 UTC，序列化为 ISO-8601；数据库列 `timestamptz`。
- 向量维度固定 **1024**，`chunks.embedding vector(1024)`；不匹配一律拒绝。
- 编排参数：`max_rounds` 默认 3、上限 6；单步超时 60s；整体超时 180s；`top_k` 默认 20（每库），融合后取 8。
- 切分：目标 400–800 字，重叠 10–15%；表格与代码块不截断。
- 认证：BCrypt；access token 30 分钟；refresh token 7 天、哈希入库、可吊销；MinIO 预签名 10 分钟。
- 初始管理员默认 `admin / admin`（可用 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 覆盖）；README 与首次启动日志必须提示尽快修改默认密码。
- `must_change_password`：初始 admin 与管理员新建/重置密码的用户为 true；未改密只能访问 `me` / `change-password` / `logout`，其余返回 403 `PASSWORD_CHANGE_REQUIRED`。
- 权限：ADMIN 全量写入 + 只读审计全部会话；USER 只读知识库/Agent、只能读写自己的会话；会话越权一律 404，权限不足一律 403。
- 后端单副本；文档中间态在启动时恢复。
- 一期不做 rerank、用户反馈、自动审计 Agent。
- 每个任务结束前必须：后端 `mvn -f backend/pom.xml test` 全绿；前端任务额外 `npm --prefix frontend run build` 通过。

## Review Focus

1. **越权读取会话**：用户 A 用用户 B 的 `conversationId` 直接请求消息或 SSE，应返回 404 而不是 403 或内容泄露。→ Task 12
2. **进程重启**：文档在 `PARSING/CHUNKING/EMBEDDING` 时后端被杀，重启后应自动重新入队，且不产生重复 chunks。→ Task 5、Task 7
3. **embedding 维度不符**：提供商返回 768/1536 维向量时，必须在写入前失败并保留原有数据完整。→ Task 7
4. **模型输出不可解析**：反思阶段连续返回非 JSON 或达 `max_rounds=6`，必须降级结束并标记 `TRUNCATED`，不能死循环或 500。→ Task 11
5. **SSE 提前断线**：在 `done` 之前断开，前端凭首帧 `messageId` 调 `GET /api/messages/{id}` 能拿到已完成步骤与部分答案。→ Task 12
6. **悬空节点范围**：`node_scope` 含已删除节点 id 时，检索必须忽略该条而不是报错或返回越权片段。→ Task 8、Task 10

---

## Phase A — 骨架、公共层、认证

### Task 1: 仓库骨架、Compose 与数据库基线

**Files:**
- Create: `.gitignore`, `README.md`, `.env.example`, `docker-compose.yml`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/advisory/AdvisoryApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/db/migration/V1__init.sql`
- Create: `backend/src/test/java/com/advisory/ApplicationContextTest.java`

**Interfaces:**
- Produces: 可启动的 Spring Boot 应用、`V1` 全量表结构、`docker compose up -d postgres minio` 可用。

- [ ] **Step 1: 初始化仓库与忽略文件**

```bash
git init
```

`.gitignore` 包含：`target/`、`node_modules/`、`dist/`、`.env`、`*.log`、`.idea/`、`.vscode/`、`backend/data/`。

- [ ] **Step 2: 写 `pom.xml`**

依赖：`spring-boot-starter-web`、`spring-boot-starter-data-jpa`、`spring-boot-starter-security`、`spring-boot-starter-validation`、`spring-boot-starter-actuator`、`flyway-core` + `flyway-database-postgresql`、`postgresql`、`io.minio:minio:8.5.17`、`io.jsonwebtoken:jjwt-api/impl/jackson:0.12.6`、`spring-boot-starter-test`、`spring-security-test`、`org.testcontainers:postgresql/minio:1.20.4`、`org.testcontainers:junit-jupiter`。Java 21，`spring-boot-maven-plugin`。

- [ ] **Step 3: 写 `application.yml`**

```yaml
server:
  port: 8080
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/advisory}
    username: ${DB_USER:advisory}
    password: ${DB_PASSWORD:advisory}
  jpa:
    hibernate.ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
  servlet.multipart:
    max-file-size: 50MB
    max-request-size: 50MB
management.endpoints.web.exposure.include: health,info,metrics
app:
  jwt:
    secret: ${JWT_SECRET:dev-only-secret-change-me-32bytes-min}
    access-ttl: PT30M
    refresh-ttl: P7D
  encryption-key: ${APP_ENCRYPTION_KEY:dev-only-32-byte-key-change-me!!}
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket: ${MINIO_BUCKET:advisory}
  bootstrap-admin:
    username: ${ADMIN_USERNAME:admin}
    password: ${ADMIN_PASSWORD:admin}
  embedding:
    dimension: 1024
```

- [ ] **Step 4: 写 `V1__init.sql`**

按 spec §9 建全部 13 张表（`users` 含 `must_change_password boolean not null default true`）。必须包含：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS ltree;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- 表定义见 spec §9；以下约束/索引不可省略
CREATE UNIQUE INDEX ux_kb_nodes_name ON kb_nodes (kb_id, parent_id, name);
CREATE UNIQUE INDEX ux_agents_name ON agents (name);
CREATE UNIQUE INDEX ux_users_username ON users (username);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX ix_kb_nodes_path ON kb_nodes USING gist (path);
CREATE INDEX ix_chunks_embedding ON chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ix_chunks_content_trgm ON chunks USING gin (content gin_trgm_ops);
CREATE INDEX ix_chunks_document ON chunks (document_id);
CREATE INDEX ix_reasoning_steps_message ON reasoning_steps (message_id, round, phase);
CREATE INDEX ix_conversations_user ON conversations (user_id, created_at DESC);
CREATE INDEX ix_conversations_agent ON conversations (agent_id, created_at DESC);
```

- [ ] **Step 5: 写 Compose 与 `.env.example`**

服务：`postgres`（`pgvector/pgvector:pg16`，healthcheck `pg_isready`）、`minio`（`minio/minio`，`server /data --console-address :9001`）、`backend`（`build: ./backend`，依赖两者 healthy，端口 8080）。卷：`pgdata`、`miniodata`。`.env.example` 列出 `DB_*`、`MINIO_*`、`JWT_SECRET`、`APP_ENCRYPTION_KEY`、`ADMIN_*`。

- [ ] **Step 6: 写冒烟测试并验证启动**

```java
@SpringBootTest
class ApplicationContextTest {
    @Test void contextLoads() {}
}
```

Run: `docker compose up -d postgres minio && mvn -f backend/pom.xml -Dtest=ApplicationContextTest test`
Expected: PASS，Flyway 应用 V1 成功。再 `curl http://localhost:8080/actuator/health`（本地 `mvn -f backend/pom.xml spring-boot:run`）返回 `{"status":"UP"}`。

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: bootstrap backend, compose and schema"
```

---

### Task 2: 公共 API 层与集成测试基座

**Files:**
- Create: `backend/src/main/java/com/advisory/common/api/ApiResponse.java`
- Create: `backend/src/main/java/com/advisory/common/api/PageResponse.java`
- Create: `backend/src/main/java/com/advisory/common/error/ErrorCode.java`
- Create: `backend/src/main/java/com/advisory/common/error/AppException.java`
- Create: `backend/src/main/java/com/advisory/common/error/GlobalExceptionHandler.java`
- Create: `backend/src/test/java/com/advisory/support/IntegrationTestBase.java`
- Create: `backend/src/test/java/com/advisory/common/TestController.java`
- Test: `backend/src/test/java/com/advisory/common/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Produces: `ApiResponse.ok(T)` / `ApiResponse.error(ErrorCode, String)`；`AppException(ErrorCode code, String message)`；`IntegrationTestBase`（Testcontainers PG + MinIO，注入 `@Autowired MockMvc mockMvc`）。

- [ ] **Step 1: 写失败测试**

```java
@WebMvcTest(controllers = TestController.class)
class GlobalExceptionHandlerTest {
    @Autowired MockMvc mockMvc;
    @Test void mapsAppExceptionToCodeAndStatus() throws Exception {
        mockMvc.perform(get("/api/_test/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }
}
```

`TestController` 抛 `new AppException(ErrorCode.RESOURCE_NOT_FOUND, "x")`。

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=GlobalExceptionHandlerTest test`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现公共层**

- `ApiResponse<T>(int code, String message, T data)`，静态工厂 `ok(data)`（code 0）、`error(code, msg)`。
- `ErrorCode` 枚举至少含：`VALIDATION_FAILED(40000,400)`、`UNAUTHORIZED(40100,401)`、`FORBIDDEN(40300,403)`、`RESOURCE_NOT_FOUND(40400,404)`、`CONFLICT(40900,409)`、`INTERNAL_ERROR(50000,500)`、`KB_NODE_NAME_DUPLICATED(40901,409)`、`NODE_NOT_EMPTY(40902,409)`、`KB_IN_USE(40903,409)`、`MODEL_IN_USE(40904,409)`、`KB_REINDEXING(40905,409)`、`CONVERSATION_BUSY(40906,409)`、`EMBEDDING_DIMENSION_MISMATCH(40010,400)`、`PASSWORD_CHANGE_REQUIRED(40301,403)`。
- `GlobalExceptionHandler` 处理 `AppException`、`MethodArgumentNotValidException`（40000）、`AccessDeniedException`（40300）、兜底 `Exception`（50000，日志带 stacktrace，响应不泄露堆栈）。

- [ ] **Step 4: 写 `IntegrationTestBase`**

```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class IntegrationTestBase {
    @Container static PostgreSQLContainer<?> PG =
        new PostgreSQLContainer<>("pgvector/pgvector:pg16");
    @Container static GenericContainer<?> MINIO =
        new GenericContainer<>("minio/minio").withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server /data");
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) { /* 映射 url/凭证 */ }
}
```

- [ ] **Step 5: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=GlobalExceptionHandlerTest test`
Expected: PASS。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: common api envelope and error handling"
```

---

### Task 3: 认证、用户与权限矩阵

**Files:**
- Create: `backend/src/main/java/com/advisory/auth/domain/User.java`, `UserRole.java`, `RefreshToken.java`
- Create: `backend/src/main/java/com/advisory/auth/repo/UserRepository.java`, `RefreshTokenRepository.java`
- Create: `backend/src/main/java/com/advisory/auth/service/JwtService.java`, `RefreshTokenService.java`, `AuthService.java`, `BootstrapAdminInitializer.java`
- Create: `backend/src/main/java/com/advisory/auth/security/SecurityConfig.java`, `JwtAuthFilter.java`, `CurrentUser.java`, `CurrentUserArgumentResolver.java`
- Create: `backend/src/main/java/com/advisory/auth/api/AuthController.java`, `dto/LoginRequest.java`, `dto/TokenPair.java`, `dto/ChangePasswordRequest.java`
- Create: `backend/src/main/java/com/advisory/auth/api/UserAdminController.java`
- Test: `backend/src/test/java/com/advisory/auth/AuthFlowTest.java`, `PermissionMatrixTest.java`

**Interfaces:**
- Produces: `CurrentUser(Long id, UserRole role)`；`SecurityConfig` 暴露 `SecurityFilterChain`；`AuthController` 端点 `POST /api/auth/login|refresh|logout|change-password`、`GET /api/auth/me`（返回 `mustChangePassword`）；`UserAdminController` `GET/POST/PATCH /api/users`。
- Consumes: Task 2 的 `ApiResponse`、`ErrorCode`、`IntegrationTestBase`。

- [ ] **Step 1: 写失败测试**

```java
class AuthFlowTest extends IntegrationTestBase {
    @Test void loginThenRefreshRotatesToken() throws Exception {
        String body = """{"username":"admin","password":"admin"}""";
        var login = mockMvc.perform(post("/api/auth/login").contentType(JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
            .andReturn();
        String refresh = JsonPath.read(login.getResponse().getContentAsString(), "$.data.refreshToken");
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isOk());
        mockMvc.perform(post("/api/auth/refresh").contentType(JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}"))
            .andExpect(status().isUnauthorized());
    }
}
```

`PermissionMatrixTest`：USER 调 `POST /api/users` 期望 403；无 token 调 `GET /api/knowledge-bases` 期望 401。

`MustChangePasswordTest`：admin 默认密码登录后访问 `GET /api/knowledge-bases` 期望 403 `PASSWORD_CHANGE_REQUIRED`；调 `change-password` 后重新登录可访问，旧 refresh token 失效。

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=AuthFlowTest test`
Expected: FAIL（404/类不存在）。

- [ ] **Step 3: 实现认证**

- `BootstrapAdminInitializer implements ApplicationRunner`：若 `userRepository.countByRole(ADMIN) == 0`，用 `PasswordEncoder` 创建 `app.bootstrap-admin.*` 用户（默认 `admin/admin`）；若使用默认密码，启动日志打 WARN 提示修改。
- `JwtService`：HS256，claims 含 `sub`(userId)、`role`、`exp`；`parse` 返回 `CurrentUser`。
- refresh token：生成 32 字节随机串 → SHA-256 hex 存 `token_hash`；刷新时校验未过期未吊销，**删除旧行并签发新行**（轮换）。
- `JwtAuthFilter`：`Authorization: Bearer` → 校验 → 塞 `SecurityContext`。
- `SecurityConfig`：`/api/auth/**` permitAll，`/actuator/health` permitAll，其余 authenticated；`@EnableMethodSecurity`。
- `UserAdminController` 用 `@PreAuthorize("hasRole('ADMIN')")`。
- JWT claim 含 `mustChangePassword`；`JwtAuthFilter` 在未改密时只放行 `/api/auth/me`、`/api/auth/change-password`、`/api/auth/logout`，其余返回 403 `PASSWORD_CHANGE_REQUIRED`。
- `change-password`：校验旧密码 → 更新 hash → `must_change_password=false` → 吊销该用户全部 refresh token → 返回新的 token 对。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=AuthFlowTest,PermissionMatrixTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: jwt auth, refresh rotation and role matrix"
```

---

## Phase B — 知识库与检索

### Task 4: 知识库与节点树

**Files:**
- Create: `backend/src/main/java/com/advisory/knowledge/domain/KnowledgeBase.java`, `KbNode.java`
- Create: `backend/src/main/java/com/advisory/knowledge/repo/KnowledgeBaseRepository.java`, `KbNodeRepository.java`, `KbNodeJdbcRepository.java`
- Create: `backend/src/main/java/com/advisory/knowledge/service/KnowledgeBaseService.java`, `NodeService.java`
- Create: `backend/src/main/java/com/advisory/knowledge/api/KnowledgeBaseController.java`, `NodeController.java`, `dto/*.java`
- Test: `backend/src/test/java/com/advisory/knowledge/NodeTreeTest.java`

**Interfaces:**
- `NodeService.createNode(Long kbId, Long parentId, String name) -> KbNode`
- `NodeService.moveNode(Long nodeId, Long newParentId)`, `deleteNode(Long nodeId)`
- `KnowledgeBaseService.create/update/delete(Long id)`；删除被 Agent 引用时抛 `KB_IN_USE`（Agent 表 Task 10 建；此处用 `JdbcTemplate` 查 `agents` 是否引用，表不存在时跳过）。
- **路径规则**：`path` 为 ltree，label 形如 `n{id}`；根节点 `n1`，子节点 `n1.n2`。

- [ ] **Step 1: 写失败测试**

```java
class NodeTreeTest extends IntegrationTestBase {
    @Test void moveRewritesSubtreePaths() { /* 建 A>B>C，把 B 移到根，断言 C.path 以 B 新 path 开头 */ }
    @Test void duplicateSiblingNameRejected() { /* 期望 409 + KB_NODE_NAME_DUPLICATED */ }
    @Test void deleteNonEmptyNodeRejected() { /* 期望 409 + NODE_NOT_EMPTY */ }
    @Test void userCannotCreateKnowledgeBase() { /* USER token → 403 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=NodeTreeTest test`
Expected: FAIL。

- [ ] **Step 3: 实现树操作**

- 插入：先插行拿 id，再 `UPDATE kb_nodes SET path = (parent.path || '.n' || id)::ltree WHERE id = ?`。
- 移动：`UPDATE kb_nodes SET path = ? || subpath(path, nlevel(?)) WHERE path <@ ?` 一条 SQL 完成整棵子树重写（旧 path 前缀替换为新前缀）。
- 删除：先 `SELECT count(*) FROM kb_nodes WHERE parent_id=? AND id<>?` 与 `documents` 计数，任一非 0 抛 `NODE_NOT_EMPTY`。
- 列表返回扁平数组 + `parentId`，由前端组树；同事务校验兄弟重名（唯一索引兜底，捕获 `DataIntegrityViolationException` 转 `KB_NODE_NAME_DUPLICATED`）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=NodeTreeTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: knowledge base tree with ltree paths"
```

---

### Task 5: 文档上传、状态机与重启恢复

**Files:**
- Create: `backend/src/main/java/com/advisory/knowledge/domain/Document.java`, `DocumentStatus.java`
- Create: `backend/src/main/java/com/advisory/knowledge/repo/DocumentRepository.java`
- Create: `backend/src/main/java/com/advisory/knowledge/service/StorageService.java`, `DocumentService.java`, `DocumentIngestWorker.java`
- Create: `backend/src/main/java/com/advisory/knowledge/api/DocumentController.java`, `dto/PresignRequest.java`, `dto/CreateDocumentRequest.java`
- Create: `backend/src/main/java/com/advisory/common/config/MinioConfig.java`
- Test: `backend/src/test/java/com/advisory/knowledge/DocumentUploadTest.java`, `IngestRecoveryTest.java`

**Interfaces:**
- `StorageService.presignPut(String objectKey, Duration ttl) -> String`；`getObject(String key) -> InputStream`；`putText(String key, String text)`。
- `DocumentService.create(Long kbId, Long nodeId, CreateDocumentRequest req, Long userId) -> Document`；`retry(Long docId)`。
- `DocumentIngestWorker.enqueue(Long documentId)`；启动时 `ApplicationRunner` 调用 `recoverInterrupted()`，把 `PARSING/CHUNKING/EMBEDDING` 置 `PENDING` 并 enqueue。

- [ ] **Step 1: 写失败测试**

```java
class DocumentUploadTest extends IntegrationTestBase {
    @Test void presignThenCreateIsIdempotent() { /* 同一 checksum+objectKey 回调两次，只产生一条 documents */ }
}
class IngestRecoveryTest extends IntegrationTestBase {
    @Test void restartRequeuesInterruptedDocuments() { /* 直接 UPDATE status='PARSING' 后调用 recoverInterrupted，断言变 PENDING */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=DocumentUploadTest,IngestRecoveryTest test`
Expected: FAIL。

- [ ] **Step 3: 实现上传与任务队列**

- `presign`：objectKey 固定为 `kb/{kbId}/{uuid}/{filename}`，返回 URL 与 objectKey；TTL 10 分钟。
- 回调：按 `object_key` 唯一约束幂等；状态 `PENDING`，写入 `created_by`。
- worker：`ThreadPoolTaskExecutor`（core 2 / max 4 / queue 100），按 documentId 投递；单文档异常捕获后置 `FAILED` + `error_msg`，不抛出线程。
- 状态流转只允许 `PENDING→PARSING→CHUNKING→EMBEDDING→READY` 或 `→FAILED`（用 `UPDATE ... WHERE status = :expected` 做 CAS）。
- 删除：软删 `DELETED`，异步删 MinIO 对象与 chunks。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=DocumentUploadTest,IngestRecoveryTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: document upload, status machine and recovery"
```

---

### Task 6: 解析与结构感知切分

**Files:**
- Create: `backend/src/main/java/com/advisory/knowledge/ingest/ParsedDocument.java`, `ParsedSection.java`, `TextChunk.java`, `ChunkConfig.java`
- Create: `backend/src/main/java/com/advisory/knowledge/ingest/DocumentParser.java`, `StructureAwareChunker.java`
- Modify: `backend/pom.xml`（加 `spring-ai-tika-document-reader`）
- Test: `backend/src/test/java/com/advisory/knowledge/ChunkerTest.java`
- Create: `backend/src/test/resources/fixtures/guide.md`, `guide.docx`

**Interfaces:**
- `DocumentParser.parse(InputStream in, String filename) -> ParsedDocument`（`ParsedDocument(String markdown, List<ParsedSection> sections)`）。
- `StructureAwareChunker.split(ParsedDocument doc, ChunkConfig cfg) -> List<TextChunk>`；`TextChunk(int seq, String headingPath, String content)`。
- `ChunkConfig(int minChars, int maxChars, double overlapRatio)`，默认 `(400, 800, 0.12)`。

- [ ] **Step 1: 写失败测试**

```java
class ChunkerTest {
    @Test void splitsByMarkdownHeadings() { /* 两个二级标题 → 至少 2 个 chunk，headingPath 正确 */ }
    @Test void neverTruncatesTable() { /* 含 markdown 表格的段落整体落在同一 chunk */ }
    @Test void longSectionFallsBackToOverlap() { /* 5000 字无标题 → 每块 <= 800，且相邻块有重叠 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=ChunkerTest test`
Expected: FAIL。

- [ ] **Step 3: 实现解析与切分**

- `DocumentParser` 用 `TikaDocumentReader`（`spring-ai-tika-document-reader`）把文件转 Markdown；解析失败抛 `AppException(VALIDATION_FAILED, "无法解析该文件")`。
- 切分：先按标题层级递归；叶子段落若在 400–800 字则整段成块，超长按句子边界切并保留 12% 重叠；表格（连续 `|` 行）与代码块（``` 包围）作为原子块不切。
- `headingPath` 用 `A > B > C` 记录，供引用展示。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=ChunkerTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: tika parsing and structure-aware chunking"
```

---

### Task 7: 向量化、写入与重建索引

**Files:**
- Create: `backend/src/main/java/com/advisory/knowledge/ingest/EmbeddingGateway.java`, `ChunkWriter.java`
- Modify: `backend/src/main/java/com/advisory/knowledge/service/DocumentIngestWorker.java`
- Modify: `backend/src/main/java/com/advisory/knowledge/service/KnowledgeBaseService.java`（`reindex`）
- Modify: `backend/src/main/java/com/advisory/knowledge/api/KnowledgeBaseController.java`（新增 `POST /api/knowledge-bases/{id}/reindex`）
- Modify: `backend/pom.xml`（Spring AI BOM、ollama/openai starter、pgvector store 依赖）
- Test: `backend/src/test/java/com/advisory/knowledge/EmbeddingWriteTest.java`, `ReindexTest.java`

**Interfaces:**
- `EmbeddingGateway.embed(Long embeddingModelId, List<String> texts) -> List<float[]>`；**必须校验每个向量长度 == `app.embedding.dimension`，否则抛 `EMBEDDING_DIMENSION_MISMATCH` 且不写库**。
- `ChunkWriter.replaceAll(Long documentId, Long kbId, Long nodeId, List<TextChunk> chunks, List<float[]> vectors)`：同一事务内 `DELETE FROM chunks WHERE document_id=?` 再批量 INSERT（幂等）。
- `KnowledgeBaseService.reindex(Long kbId)`：置 `REINDEXING` → 逐文档重跑 → `READY`/`FAILED`。
- 端点 `POST /api/knowledge-bases/{id}/reindex`：ADMIN 触发，返回 202；已在进行中返回 409 `KB_REINDEXING`。

- [ ] **Step 1: 写失败测试**

```java
class EmbeddingWriteTest extends IntegrationTestBase {
    @Test void dimensionMismatchAbortsWithoutDeletingExistingChunks() { /* 旧 chunks 数量不变 */ }
    @Test void rerunDoesNotDuplicateChunks() { /* 同一 document 写两次，count 不变 */ }
}
class ReindexTest extends IntegrationTestBase {
    @Test void reindexingBlocksRetrievalAndUpload() { /* 409 KB_REINDEXING */ }
}
```

测试用 `@MockBean EmbeddingGateway` 或测试配置提供固定向量实现。

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=EmbeddingWriteTest,ReindexTest test`
Expected: FAIL。

- [ ] **Step 3: 实现向量化**

- 按 `embedding_model_id` 从 `models` 解析提供商与模型名，构造 Spring AI `EmbeddingModel`（Ollama 或 OpenAI 兼容），带 60s 超时。
- 批量 32 条；失败重试 2 次（指数退避 1s/2s），仍失败则文档 `FAILED`。
- 向量以 pgvector 文本格式 `[0.1,0.2,...]` 写入；SQL 用 `JdbcTemplate`：`INSERT INTO chunks (..., embedding) VALUES (..., ?::vector)`。
- 维度校验在 `DELETE` 之前完成。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=EmbeddingWriteTest,ReindexTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: embedding pipeline with dimension guard and reindex"
```

---

### Task 8: 混合检索与跨库范围过滤

**Files:**
- Create: `backend/src/main/java/com/advisory/knowledge/retrieval/RetrievalQuery.java`, `RetrievedChunk.java`, `RrfFusion.java`, `RetrievalService.java`, `RetrievalJdbcRepository.java`
- Test: `backend/src/test/java/com/advisory/knowledge/RetrievalTest.java`

**Interfaces:**
- `RetrievalQuery(List<Long> kbIds, Map<Long, List<Long>> nodeScope, List<String> queries, int perKbTopK, int finalTopK)`
- `RetrievedChunk(Long chunkId, Long documentId, Long kbId, Long nodeId, String docName, String nodeName, String content, double score)`
- `RetrievalService.retrieve(RetrievalQuery q)`：每库各自向量 topK + trgm topK，RRF（k=60）融合，跨库合并后取 `finalTopK`。

- [ ] **Step 1: 写失败测试**

```java
class RetrievalTest extends IntegrationTestBase {
    @Test void respectsPerKbNodeScope() { /* 只选中 A 库 nodeX，B 库同名内容不得返回 */ }
    @Test void excludesDeletedDocuments() { /* status=DELETED 的 chunks 不出现 */ }
    @Test void ignoresDanglingScopeNodeIds() { /* nodeScope 含不存在的 id 不报错 */ }
    @Test void rrfOrdersByRankNotRawScore() { /* 构造两路名次相反，断言融合结果 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=RetrievalTest test`
Expected: FAIL。

- [ ] **Step 3: 实现检索 SQL**

向量路：

```sql
SELECT c.id, c.document_id, c.kb_id, c.node_id, c.content,
       1 - (c.embedding <=> ?::vector) AS score
FROM chunks c JOIN documents d ON d.id = c.document_id
WHERE d.status = 'READY' AND c.kb_id = ?
  AND (<scope 为空> OR c.node_id IN (
      SELECT id FROM kb_nodes WHERE path <@ (SELECT path FROM kb_nodes WHERE id = ?)))
ORDER BY c.embedding <=> ?::vector
LIMIT ?
```

关键词路：`similarity(c.content, ?) > 0.05`，`ORDER BY similarity DESC LIMIT ?`。`nodeScope` 为每库节点集合时，用 `path <@ ANY(...)` 的等价子查询；不存在的节点 id 自然过滤掉。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=RetrievalTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: hybrid retrieval with rrf and scoped filtering"
```

---

## Phase C — 提供商、Agent 与编排

### Task 9: 提供商、模型与连通性测试

**Files:**
- Create: `backend/src/main/java/com/advisory/provider/domain/ModelProvider.java`, `ModelConfig.java`, `ProviderType.java`, `ModelCapability.java`
- Create: `backend/src/main/java/com/advisory/provider/repo/ModelProviderRepository.java`, `ModelConfigRepository.java`
- Create: `backend/src/main/java/com/advisory/provider/service/CryptoService.java`, `ProviderService.java`, `ProviderProbe.java`, `ProbeResult.java`
- Create: `backend/src/main/java/com/advisory/provider/api/ProviderController.java`, `dto/*.java`
- Test: `backend/src/test/java/com/advisory/provider/ProviderTest.java`

**Interfaces:**
- `CryptoService.encrypt(String) -> String` / `decrypt(String) -> String`（AES-GCM，随机 IV，Base64 输出）。
- `ProviderProbe.test(ModelProvider p, String modelName) -> ProbeResult(boolean ok, String errorCode, List<String> remoteModels)`；错误码 `NETWORK_UNREACHABLE|AUTH_FAILED|MODEL_NOT_FOUND|TIMEOUT`。
- 端点：`POST /api/providers/{id}/test`、`GET /api/providers/{id}/models?remote=true`、`POST /api/providers/{id}/models/import`。
- 模型保存校验：`capability=EMBEDDING` 时 `dimension` 必须等于 1024，否则 `EMBEDDING_DIMENSION_MISMATCH`。

- [ ] **Step 1: 写失败测试**

```java
class ProviderTest extends IntegrationTestBase {
    @Test void apiKeyIsEncryptedAndMasked() { /* DB 列 != 明文；响应不含明文 */ }
    @Test void probeDistinguishesFailureClasses() { /* 用 WireMock/内置 HttpServer 返回 401/超时/正常 */ }
    @Test void rejectsEmbeddingDimensionOtherThan1024() {}
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=ProviderTest test`
Expected: FAIL。

- [ ] **Step 3: 实现提供商模块**

- Ollama：`GET {base}/api/tags`；OpenAI 兼容：`GET {base}/v1/models` + `Authorization: Bearer`。
- 超时 10s；401/403 → `AUTH_FAILED`，`UnknownHost`/`ConnectException` → `NETWORK_UNREACHABLE`，模型不匹配 → `MODEL_NOT_FOUND`。
- 响应只返回 `apiKeyMasked`（前 4 + `****`）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=ProviderTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: model providers, encrypted keys and probes"
```

---

### Task 10: Agent 配置、自动版本与跨库校验

**Files:**
- Create: `backend/src/main/java/com/advisory/agent/domain/Agent.java`, `AgentVersion.java`, `AgentSnapshot.java`
- Create: `backend/src/main/java/com/advisory/agent/repo/AgentRepository.java`, `AgentVersionRepository.java`
- Create: `backend/src/main/java/com/advisory/agent/service/AgentService.java`
- Create: `backend/src/main/java/com/advisory/agent/api/AgentController.java`, `dto/*.java`
- Test: `backend/src/test/java/com/advisory/agent/AgentConfigTest.java`

**Interfaces:**
- `AgentSnapshot`（record，也是 JSON 快照结构）：`name, systemPrompt, chatModelId, embeddingModelId, kbIds, nodeScope, maxRounds, topK, temperature`。
- `AgentService.create(CreateAgentRequest) -> Agent`；`update(Long id, UpdateAgentRequest) -> Agent`：**同一事务内**更新 `agents`、写 `agent_versions(version=current+1, snapshot)`、更新 `current_version`。
- `AgentService.resolveSnapshot(Long agentId, int version) -> AgentSnapshot`。
- 校验：`kbIds` 非空；所有 KB 的 `embedding_model_id` 相同；`nodeScope` 的每个 node 必须属于对应 `kbId`；`maxRounds` ∈ [1,6]。
- 端点：`GET/POST /api/agents`、`PATCH/DELETE /api/agents/{id}`、`GET /api/agents/{id}/versions`。

- [ ] **Step 1: 写失败测试**

```java
class AgentConfigTest extends IntegrationTestBase {
    @Test void everyUpdateCreatesNewVersion() { /* 两次 PATCH → versions 2 条，current_version=2 */ }
    @Test void rejectsMixedEmbeddingModelsAcrossKbs() { /* 400 */ }
    @Test void rejectsNodeFromAnotherKb() { /* 400 */ }
    @Test void versionSnapshotIsImmutable() { /* 改配置后旧版本 snapshot 内容不变 */ }
    @Test void userCannotUpdateAgent() { /* USER PATCH /api/agents/{id} → 403 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=AgentConfigTest test`
Expected: FAIL。

- [ ] **Step 3: 实现 Agent 模块**

- 快照序列化为 `jsonb`（Jackson `ObjectMapper`）。
- `current_version` 用 `SELECT ... FOR UPDATE` 防并发丢版本。
- 删除 Agent：若存在会话则拒绝 `CONFLICT`（提示先归档）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=AgentConfigTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: agent config with auto versioning and cross-kb checks"
```

---

### Task 11: 四阶段编排状态机

**Files:**
- Create: `backend/src/main/java/com/advisory/chat/orchestration/Phase.java`, `StepSink.java`, `ChatModelGateway.java`, `PlanResult.java`, `ReflectResult.java`, `OrchestrationResult.java`, `ChatOrchestrator.java`, `PromptTemplates.java`
- Test: `backend/src/test/java/com/advisory/chat/orchestration/ChatOrchestratorTest.java`

**Interfaces:**
- `ChatModelGateway.chat(Long modelId, String systemPrompt, String userPrompt) -> String`；`chatStructured(Long modelId, String systemPrompt, String userPrompt, Class<T> type) -> T`（解析失败抛 `AppException`）。
- `StepSink`：`onStepStart(int round, Phase phase)`、`onStepEnd(int round, Phase phase, String summary)`、`onDelta(Phase phase, String text)`、`onCitations(List<RetrievedChunk>)`。
- `ChatOrchestrator.run(AgentSnapshot agent, String question, StepSink sink) -> OrchestrationResult(String answer, List<ReasoningStepData> steps, List<RetrievedChunk> citations, boolean truncated, TokenUsage usage)`。
- `PlanResult(List<String> queries)`；`ReflectResult(boolean sufficient, List<String> gaps, List<String> nextQueries, String draftAnswer)`。
- `ReasoningStepData(int round, Phase phase, String summary, String payload, long latencyMs)`；`TokenUsage(int promptTokens, int completionTokens)`。
- 终止：`sufficient || round >= maxRounds`。**任何解析失败按"不充分"处理并继续下一轮；连续 2 次解析失败直接进入 ANSWER 并置 `truncated=true`。** 整体超 180s 同样进入 ANSWER。

- [ ] **Step 1: 写失败测试**

```java
class ChatOrchestratorTest {
    @Test void stopsWhenReflectSaysSufficient() { /* 1 轮，ANSWER 被调用 */ }
    @Test void runsToMaxRoundsWhenNeverSufficient() { /* maxRounds=3 → 3 次 RETRIEVE/REFLECT */ }
    @Test void illegalJsonDegradesInsteadOfThrowing() { /* 连续 2 次非法 → truncated=true，仍返回 answer */ }
    @Test void answerPromptContainsNumberedCitations() { /* 断言 system prompt 含 [1] 引用要求 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=ChatOrchestratorTest test`
Expected: FAIL。

- [ ] **Step 3: 实现编排**

- PLAN system prompt 固定：要求输出 `{"queries":["..."]}`，1–3 条，覆盖不同角度。
- REFLECT system prompt 固定：输入问题、已召回片段（编号）、上一轮草稿；输出 `{"sufficient":bool,"gaps":[],"nextQueries":[],"draftAnswer":"..."}`。
- ANSWER system prompt 固定：只允许使用编号片段；每个论断后标 `[n]`；证据不足必须明说；输出纯 Markdown。
- 每步记录耗时与 token（若提供商返回 usage，否则记 0）。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=ChatOrchestratorTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: four-phase orchestration state machine"
```

---

### Task 12: 会话、SSE 推送与断线恢复

**Files:**
- Create: `backend/src/main/java/com/advisory/chat/domain/Conversation.java`, `Message.java`, `ReasoningStep.java`, `MessageStatus.java`
- Create: `backend/src/main/java/com/advisory/chat/repo/ConversationRepository.java`, `MessageRepository.java`, `ReasoningStepRepository.java`
- Create: `backend/src/main/java/com/advisory/chat/service/ChatService.java`, `SseStepSink.java`, `ConversationLock.java`
- Create: `backend/src/main/java/com/advisory/chat/api/ChatController.java`, `dto/*.java`
- Test: `backend/src/test/java/com/advisory/chat/ChatStreamTest.java`, `ChatAccessTest.java`

**Interfaces:**
- `ChatService.createConversation(Long agentId, Long userId) -> Conversation`（写入锁定的 `agent_version`）。
- `ChatService.start(Long conversationId, Long userId, String content) -> SseEmitter`：先持久化 USER 消息与 `PENDING` ASSISTANT 消息，**首帧 `meta {messageId, conversationId}`**，再异步跑编排。
- `ChatService.getMessage(Long messageId, Long userId) -> MessageDetail(Long id, Long conversationId, MessageStatus status, String content, List<RetrievedChunk> citations, List<ReasoningStepData> steps, TokenUsage usage, long latencyMs)`。
- `ConversationLock.tryAcquire(Long conversationId) -> boolean`（`ConcurrentHashMap`，finally 释放）。
- SSE 事件名与载荷严格按 spec §11。
- 端点：`GET/POST /api/conversations`、`GET /api/conversations/{id}/messages`、`GET /api/messages/{id}`、`POST /api/conversations/{id}/messages`。

- [ ] **Step 1: 写失败测试**

```java
class ChatStreamTest extends IntegrationTestBase {
    @Test void emitsMetaFirstThenStepsThenDone() { /* 用 MockMvc 异步请求，断言事件顺序 */ }
    @Test void concurrentQuestionOnSameConversationReturns409() {}
}
class ChatAccessTest extends IntegrationTestBase {
    @Test void otherUsersConversationReturns404() { /* USER B 访问 A 的会话 → 404 */ }
    @Test void adminCanReadAnyConversation() { /* ADMIN GET /api/messages/{id} → 200 */ }
    @Test void completedStepsSurviveDisconnect() { /* 模拟只读到 meta 就断开，GET message 仍有 steps */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=ChatStreamTest,ChatAccessTest test`
Expected: FAIL。

- [ ] **Step 3: 实现会话与流**

- SSE 用 `SseEmitter(180_000L)`；每 15s 发 `ping` 注释；完成/异常后 `complete()`。
- 每个 `onStepStart/End`、`onDelta`、`onCitations` 先落库（`reasoning_steps` 或更新 `messages`），再 `emitter.send(...)`；发送失败不中断编排。
- 消息状态：创建 `PENDING` → 首个 token `STREAMING` → `COMPLETED`；异常 `FAILED`；超时/降级 `TRUNCATED`。
- 会话查询：`USER` 强制 `user_id`；`ADMIN` 不过滤。不存在或无权一律 404。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=ChatStreamTest,ChatAccessTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: chat sessions, sse streaming and resumable steps"
```

---

### Task 13: Agent 试运行与管理员审计接口

**Files:**
- Create: `backend/src/main/java/com/advisory/agent/api/AgentTestController.java`
- Create: `backend/src/main/java/com/advisory/audit/AuditController.java`, `AuditQueryService.java`, `dto/AuditConversationRow.java`
- Test: `backend/src/test/java/com/advisory/agent/AgentTestRunTest.java`, `backend/src/test/java/com/advisory/audit/AuditAccessTest.java`

**Interfaces:**
- `POST /api/agents/{id}/test`：body `{question, version?}`；返回 `{answer, steps[], citations[], truncated}`；**不写 conversations/messages/reasoning_steps**。
- `GET /api/admin/conversations?userId=&agentId=&from=&to=&page=&size=`；`GET /api/admin/conversations/{id}`（返回消息 + steps + citations）。
- 审计仅 ADMIN；USER 访问 → 403。

- [ ] **Step 1: 写失败测试**

```java
class AgentTestRunTest extends IntegrationTestBase {
    @Test void testRunDoesNotPersistConversation() { /* 调用前后 conversations/messages count 不变 */ }
}
class AuditAccessTest extends IntegrationTestBase {
    @Test void adminFiltersByUser() {}
    @Test void userForbidden() { /* 403 */ }
}
```

- [ ] **Step 2: 运行验证失败**

Run: `mvn -f backend/pom.xml -Dtest=AgentTestRunTest,AuditAccessTest test`
Expected: FAIL。

- [ ] **Step 3: 实现两个端点**

- 试运行把 `StepSink` 换为内存收集器，复用 `ChatOrchestrator`。
- 管理员每次访问 `GET /api/admin/conversations/{id}` 写一条结构化日志：`adminAudit actor=... conversationId=...`。
- 审计列表返回 `id, userName, agentName, agentVersion, messageCount, createdAt, lastMessageAt`；排序 `created_at DESC`。

- [ ] **Step 4: 运行验证通过**

Run: `mvn -f backend/pom.xml -Dtest=AgentTestRunTest,AuditAccessTest test`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: agent test runs and admin audit endpoints"
```

---

## Phase D — 前端

### Task 14: 前端骨架、登录与 SSE 客户端

**Files:**
- Create: `frontend/package.json`, `vite.config.ts`, `tsconfig.json`, `index.html`, `vitest.config.ts`
- Create: `frontend/src/main.ts`, `App.vue`, `router/index.ts`, `stores/auth.ts`, `api/client.ts`, `api/sse.ts`, `pages/Login.vue`, `pages/ChangePassword.vue`, `layouts/AppLayout.vue`
- Test: `frontend/tests/sse.spec.ts`, `frontend/tests/auth.spec.ts`

**Interfaces:**
- `apiFetch<T>(path, init) -> Promise<T>`：自动带 `Authorization`，401 时尝试 refresh 一次，失败跳 `/login`。
- `postSse(path, body, handlers: { onMeta, onStep, onDelta, onCitations, onDone, onError }) -> AbortController`，用 `@microsoft/fetch-event-source`。
- `consumeSse(stream: ReadableStream, handlers) -> Promise<void>`：纯解析分发函数，单测直接调用。
- Pinia `useAuthStore`：`token, refreshToken, user, login(), logout()`。
- 路由守卫：未登录跳 `/login`；`meta.roles` 控制 ADMIN 页面；`mustChangePassword=true` 时强制跳 `/change-password`，改密成功前不放行任何业务路由。

- [ ] **Step 1: 写失败测试**

```ts
// tests/sse.spec.ts
it('dispatches meta, step, delta and done in order', async () => {
  const seen: string[] = []
  await consumeSse(mockStream([['meta', '{"messageId":1}'], ['step', '{"phase":"PLAN"}'], ['done', '{}']]),
    { onMeta: () => seen.push('meta'), onStep: () => seen.push('step'), onDone: () => seen.push('done') })
  expect(seen).toEqual(['meta', 'step', 'done'])
})
```

- [ ] **Step 2: 运行验证失败**

Run: `npm --prefix frontend install && npm --prefix frontend run test -- tests/sse.spec.ts`
Expected: FAIL。

- [ ] **Step 3: 实现骨架**

Vite 代理 `/api` → `http://localhost:8080`；Element Plus 全量引入；`consumeSse` 独立于网络层，便于测试。`ChangePassword.vue` 提交后替换 store 中的 token 并跳转首页。

- [ ] **Step 4: 运行验证通过**

Run: `npm --prefix frontend run test && npm --prefix frontend run build`
Expected: PASS + 构建成功。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(web): app shell, auth and sse client"
```

---

### Task 15: 知识库页面

**Files:**
- Create: `frontend/src/api/knowledge.ts`
- Create: `frontend/src/pages/knowledge/KnowledgeBaseList.vue`, `KnowledgeBaseDetail.vue`
- Create: `frontend/src/components/NodeTree.vue`, `DocumentTable.vue`, `ChunkDrawer.vue`
- Test: `frontend/tests/node-tree.spec.ts`

**Interfaces:**
- 右树左文档：`NodeTree` 支持新增/重命名/移动/删除，非空节点删除时展示后端错误码文案。
- `DocumentTable` 上传走 presign → PUT → 回调，轮询 `GET /api/documents?kbId=` 直到 `READY/FAILED`。
- USER 角色隐藏所有写按钮（`auth.isAdmin`）。

- [ ] **Step 1: 写失败测试**

```ts
it('renders nested nodes and hides write actions for USER', () => { /* mount + 断言 */ })
```

- [ ] **Step 2: 运行验证失败**

Run: `npm --prefix frontend run test -- tests/node-tree.spec.ts`
Expected: FAIL。

- [ ] **Step 3: 实现页面**

- `KnowledgeBaseList`：列 = 名称、文档数、状态、embedding 模型、创建时间；ADMIN 才显示"新建"。
- `KnowledgeBaseDetail`：左 `NodeTree`（`el-tree`，draggable），右 `DocumentTable`；删除非空节点时把 `NODE_NOT_EMPTY` 映射成"请先清空子节点或文档"。
- `DocumentTable`：先选节点再上传；上传流程 presign → PUT → 回调；每 3s 轮询状态直到 `READY/FAILED`；行操作含重试、删除、查看片段。
- `ChunkDrawer`：`GET /api/documents/{id}/chunks` 分页展示，显示 `headingPath`。
- USER 角色隐藏所有写按钮（`auth.isAdmin` 为 false）。

- [ ] **Step 4: 运行验证通过**

Run: `npm --prefix frontend run test && npm --prefix frontend run build`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(web): knowledge base tree and documents"
```

---

### Task 16: 提供商与 Agent 配置页面

**Files:**
- Create: `frontend/src/api/providers.ts`, `frontend/src/api/agents.ts`
- Create: `frontend/src/pages/providers/ProviderList.vue`, `frontend/src/pages/agents/AgentList.vue`, `AgentEdit.vue`
- Create: `frontend/src/components/AgentTestDrawer.vue`, `KbScopePicker.vue`
- Test: `frontend/tests/agent-form.spec.ts`

**Interfaces:**
- `KbScopePicker`：先多选知识库，再按库展示节点树多选；选中库的 embedding 模型不一致时前端直接提示并禁用保存。
- `AgentEdit`：展示 `current_version` 与历史版本列表（只读对比）。
- `AgentTestDrawer`：调 `POST /api/agents/{id}/test`，按轮次渲染四阶段轨迹。

- [ ] **Step 1: 写失败测试**

```ts
it('disables save when selected kbs use different embedding models', () => { /* mount */ })
```

- [ ] **Step 2: 运行验证失败**

Run: `npm --prefix frontend run test -- tests/agent-form.spec.ts`
Expected: FAIL。

- [ ] **Step 3: 实现页面**

- `KbScopePicker`：知识库多选 → 每库一个节点树；任两库 `embeddingModelId` 不同时展示 `el-alert` 并禁用保存。
- `AgentEdit`：表单字段与 `AgentSnapshot` 一一对应；`maxRounds` 用 `el-slider` 限制 1–6；保存成功后刷新 `currentVersion` 与版本列表。
- `AgentList`：列 = 名称、当前版本、关联库数、更新时间；行操作含编辑、试运行、删除。
- `AgentTestDrawer`：输入问题 → 调 `POST /api/agents/{id}/test` → 按轮次渲染 PLAN/RETRIEVE/REFLECT/ANSWER 卡片与引用。

- [ ] **Step 4: 运行验证通过**

Run: `npm --prefix frontend run test && npm --prefix frontend run build`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(web): providers and agent configuration"
```

---

### Task 17: 对话页与推理过程面板

**Files:**
- Create: `frontend/src/api/chat.ts`, `frontend/src/stores/chat.ts`
- Create: `frontend/src/pages/chat/ChatView.vue`
- Create: `frontend/src/components/MessageBubble.vue`, `ReasoningPanel.vue`, `CitationDrawer.vue`
- Test: `frontend/tests/chat-view.spec.ts`

**Interfaces:**
- `ChatView`：左会话列表（仅本人）、中消息流、右折叠面板。
- 发送消息用 `postSse`；`meta` 到达即记录 `messageId`，用于中断后 `GET /api/messages/{id}` 恢复。
- `ReasoningPanel` 按 `round` 分组，展示 PLAN/RETRIEVE/REFLECT/ANSWER 卡片、耗时、token。
- 引用角标 `[n]` 点击打开 `CitationDrawer`（片段全文 + 文档名 + 节点路径）。
- `TRUNCATED` 消息显示"已达最大轮数/超时"提示。

- [ ] **Step 1: 写失败测试**

```ts
it('groups reasoning steps by round and shows truncated banner', () => { /* mount */ })
```

- [ ] **Step 2: 运行验证失败**

Run: `npm --prefix frontend run test -- tests/chat-view.spec.ts`
Expected: FAIL。

- [ ] **Step 3: 实现页面**

- `ChatView`：加载本人会话列表；发送后立即插入用户消息与助手空消息占位。
- `onMeta` 保存 `messageId`；`onDelta` 追加到当前助手消息；`onStep` 更新 `ReasoningPanel`；`onCitations` 存引用。
- 刷新或断线后按 `messageId` 调 `GET /api/messages/{id}` 覆盖本地状态。
- `MessageBubble` 把 `[n]` 渲染为可点击上标，点击打开 `CitationDrawer`（片段全文 + 文档名 + 节点路径）。
- `TRUNCATED` 消息顶部显示"已达最大轮数或超时"提示条。

- [ ] **Step 4: 运行验证通过**

Run: `npm --prefix frontend run test && npm --prefix frontend run build`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(web): chat view with reasoning panel and citations"
```

---

### Task 18: 审计页与用户管理

**Files:**
- Create: `frontend/src/api/audit.ts`, `frontend/src/api/users.ts`
- Create: `frontend/src/pages/audit/AuditList.vue`, `frontend/src/pages/users/UserList.vue`
- Test: `frontend/tests/audit-list.spec.ts`

**Interfaces:**
- `AuditList`：筛选 `userId/agentId/dateRange`，打开只读详情复用 `ReasoningPanel` + `MessageBubble`。
- `UserList`：创建用户、启用/禁用、重置密码；禁用后该用户 refresh token 失效。

- [ ] **Step 1: 写失败测试**

```ts
it('sends userId and agentId filters to the audit endpoint', () => { /* mock fetch 断言 query */ })
```

- [ ] **Step 2: 运行验证失败**

Run: `npm --prefix frontend run test -- tests/audit-list.spec.ts`
Expected: FAIL。

- [ ] **Step 3: 实现页面**

- `AuditList`：日期范围默认最近 7 天；列 = 用户、Agent、版本、消息数、创建时间；点击行打开只读详情抽屉（复用 `ReasoningPanel` + `MessageBubble`）。
- `UserList`：创建用户（用户名/初始密码/角色）、启用/禁用、重置密码；新建与重置后该用户 `mustChangePassword=true`，下次登录被强制改密；禁用后提示"该用户已下线，refresh token 已失效"。

- [ ] **Step 4: 运行验证通过**

Run: `npm --prefix frontend run test && npm --prefix frontend run build`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat(web): admin audit and user management"
```

---

## Phase E — 部署与验收

### Task 19: 生产化 Compose、nginx 与端到端验收

**Files:**
- Create: `backend/Dockerfile`, `frontend/Dockerfile`, `deploy/nginx.conf`, `deploy/init-bucket.sh`
- Modify: `docker-compose.yml`
- Modify: `AGENTS.md`（替换占位内容为已验证命令与目录职责）
- Create: `scripts/smoke.ps1`
- Create: `docs/acceptance.md`

**Interfaces:**
- nginx：`proxy_buffering off; proxy_read_timeout 300s; proxy_http_version 1.1;` 用于 `/api/`；SPA fallback `try_files $uri /index.html`。
- compose 增加 `frontend`（80 端口）与 `minio-init`（一次性创建 bucket，`mc mb --ignore-existing`）。
- `scripts/smoke.ps1`：登录 → 建库建节点 → 上传样例 → 等 READY → 建 Agent → 提问（SSE）→ 断言含引用 → 管理员审计能看到该会话。

- [ ] **Step 1: 写 Dockerfile 与 nginx 配置**

后端多阶段：`maven:3.9-eclipse-temurin-21` 构建，`eclipse-temurin:21-jre` 运行。前端：`node:22-alpine` 构建 + `nginx:alpine`。

- [ ] **Step 2: 启动全栈**

Run: `docker compose up -d --build`
Expected: `docker compose ps` 四个服务 healthy，`curl http://localhost/` 返回 SPA。

- [ ] **Step 3: 跑冒烟脚本**

Run: `powershell -File scripts/smoke.ps1`
Expected: 脚本输出 `SMOKE OK`，并打印 messageId 与引用数量。

- [ ] **Step 4: 逐条验证 spec §17 的 13 条验收标准并记录到 `docs/acceptance.md`**

每条写：命令/操作、期望、实际结果、通过与否。任何一条不通过则修复后重跑。

- [ ] **Step 5: 更新 `AGENTS.md`**

把已验证的命令（`mvn -f backend/pom.xml test`、`npm --prefix frontend run test`、`docker compose up -d --build`、`scripts/smoke.ps1`）、目录职责、单副本约束写入 `AGENTS.md`，替换当前占位内容。

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "chore: production compose, nginx sse and acceptance record"
```

---

## 计划自检记录

- **Spec 覆盖**：§2 目标 → Task 4/9/10/11/12/13；§8 权限 → Task 3/12/13；§9 数据模型 → Task 1/4–13；§10 文档流程 → Task 5/6/7；§11 编排与 SSE → Task 11/12；§12 API → 各 Task；§13 页面 → Task 14–18；§14 安全 → Task 3/9；§15 部署 → Task 1/19；§16 测试 → 各 Task；§17 验收 → Task 19。
- **类型一致性**：`AgentSnapshot` 在 Task 10 定义，Task 11/13 消费；`RetrievedChunk` 在 Task 8 定义，Task 11/12 消费；`Phase` 在 Task 11 定义，Task 12 落库使用；`MessageStatus` 在 Task 12 定义，Task 17 渲染。
- **Review Focus 归属**：1 → Task 12；2 → Task 5/7；3 → Task 7；4 → Task 11；5 → Task 12；6 → Task 8/10。
