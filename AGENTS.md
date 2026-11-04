# AGENTS.md

企业知识库智能顾问平台。后端 Java 21 + Spring Boot 3.4 + PostgreSQL 16(pgvector) + MinIO，前端 Vue 3 + Vite + TypeScript。**单副本部署**：文档任务与 SSE 编排跑在进程内，不要水平扩容（扩容前需先把文档任务移出进程）。

## 目录职责

```
backend/            Spring Boot（Maven，artifact advisory-backend）
  src/main/java/com/advisory/
    auth/           登录、刷新轮换、强制改密、用户管理
    common/         统一响应 {code,message,data}、错误码、分页、全局异常
    provider/       提供商与模型（api_key 加密存储，不落日志/响应）
    knowledge/      知识库树、上传、解析、切分、向量化、重建索引、混合检索
    agent/          Agent 配置、自动版本、跨库约束、试运行
    chat/           会话、消息、四阶段编排、SSE 推送
      model/        ChatModelGateway 的 Spring AI 实现（不用自动配置，按 models 行构造）
    audit/          管理员只读审计
  src/main/resources/db/migration/   Flyway；新增迁移从 V4 起，禁止改已发布的 V1–V3
frontend/           Vue 3 SPA（Vite；/api 代理到 8080，生产由 nginx 反代）
  src/api/          唯一 HTTP 出口（client.ts + session.ts + sse.ts）
  src/stores/       Pinia：auth、chat
  src/pages/        路由页面；components/ 复用组件
deploy/             nginx.conf、init-bucket.sh
scripts/smoke.ps1   端到端冒烟（需要真实模型端点）
docs/               spec 与实施计划（docs/superpowers/），验收记录 acceptance.md
```

## 命令（已验证）

```powershell
# 后端：需要 JDK 21。本机默认 JAVA_HOME 指向 JDK 8，必须先覆盖。
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml test                 # 196 用例，需要 Docker（Testcontainers 起 PG/MinIO）

# 前端
npm --prefix frontend run test              # 37 用例（vitest + jsdom）
npm --prefix frontend run build             # vue-tsc --noEmit + vite build

# 全栈（postgres / minio / minio-init / backend / frontend）
docker compose up -d --build
docker compose ps                            # 四个长驻服务均 healthy
curl http://localhost/                       # SPA
curl http://localhost/actuator/health        # {"status":"UP"}

# 冒烟（需真实模型；未配置时以退出码 2 明确跳过，不会假报成功）
powershell -File scripts/smoke.ps1
```

本地开发：`docker compose up -d postgres minio`（+ `minio-init` 建桶），再 `mvn -f backend/pom.xml spring-boot:run` 与 `npm --prefix frontend run dev`（5173 代理到 8080）。

## 不可违反的约定

- **响应体**一律 `{code,message,data}`；错误码见 `common/error/ErrorCode`。SSE 端点例外：直接返回 `text/event-stream`，建立前的错误仍是 JSON 信封。
- **SSE 帧序**：`meta`（首帧，带 `messageId`，断线重连句柄）→ `step` × N → `delta` → `citations` → `done`；`ping` 每 15s；整体 180s 超时。改这里必须同步 `frontend/src/api/sse.ts` 与 `ChatService`。
- **PLAN 只执行一次**：`round` 从 1 开始，RETRIEVE→REFLECT 每轮重复，`maxRounds` 封顶；证据不足或超时以 `TRUNCATED` 结束，不抛 500。
- **推理步骤是主数据**，SSE 只是实时通道：写入会话/步骤的代码路径不得依赖客户端在线。
- **会话越权 404，权限不足 403**。ADMIN 可读全部会话（审计），但**不能**替用户提问（`ChatService.start` 显式 403）。
- **改密/禁用即吊销**该用户全部 refresh token；初始化管理员与新建/重置密码的用户 `mustChangePassword=true`，改密前只允许 `me` / `change-password` / `logout`。
- **上传必须走 presign → PUT → 登记**，PUT 不能带 `Authorization`（MinIO 校验签名）。桶由应用启动时自动创建，compose 里另有 `deploy/init-bucket.sh` 提前建桶（幂等，两者不冲突）。
- **预签名必须用公网地址**：SigV4 签名覆盖 host，`app.minio.endpoint`（`minio:9000`）签出的 URL 浏览器用不了，故有 `MINIO_PUBLIC_ENDPOINT` / `app.minio.public-endpoint`；签名客户端固定 region 以避免向公网地址发起 location 查询。
- **embedding 维度固定 1024**，一个 Agent 引用的多个知识库必须同一 embedding 模型。
- 后端会话/消息/步骤用 `JdbcTemplate` 手写 SQL（避免 JPA 一级缓存返回旧值）；配置类实体才用 JPA。
- 前端只允许 `src/api/client.ts` 发 HTTP；token 存 `localStorage`，store 派生自 `tokenRevision` 而非拷贝。
- nginx 必须 `proxy_buffering off` + `proxy_read_timeout 300s`，否则 SSE 变批量返回。
