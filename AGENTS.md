# AGENTS.md

企业知识库智能顾问平台。后端 Java 21 + Spring Boot 3.4 + PostgreSQL 16(pgvector) + MinIO，前端 Vue 3 + Vite + TypeScript。**单副本部署**：文档任务与 SSE 编排跑在进程内，不要水平扩容（扩容前需先把文档任务移出进程）。

## 先读哪份文档

| 要做什么 | 读 |
|---|---|
| 改后端代码、加接口/迁移、查错误码 | [`docs/backend.md`](docs/backend.md) |
| 改前端代码、加页面、动 SSE 协议 | [`docs/frontend.md`](docs/frontend.md) |
| 本地跑起来 / 生产部署 / 环境变量 | [`README.md`](README.md) |
| 逐条验收结果与已修缺陷 | [`docs/acceptance.md`](docs/acceptance.md) |
| 设计依据（裁决 R1–R9、spec 条款） | [`docs/superpowers/specs/`](docs/superpowers/specs/) |

本文件只放**跨前后端、且违反代价很高**的约定与坑；细节都在上面两份说明里。

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
  tests/            vitest（只收集这一层，不测快照）
deploy/             nginx.conf、init-bucket.sh
scripts/            smoke.ps1（端到端冒烟）、fake-model-server.mjs（本地模型桩）
docs/               backend.md、frontend.md、acceptance.md、spec 与实施计划
```

## 命令（已验证）

```powershell
# 后端：需要 JDK 21。本机默认 JAVA_HOME 指向 JDK 8，必须先覆盖。
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml test                 # 197 用例，需要 Docker（Testcontainers 起 PG/MinIO）

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

本地开发：`docker compose up -d postgres minio minio-init`，再 `mvn -f backend/pom.xml spring-boot:run` 与 `npm --prefix frontend run dev`（5173 代理到 8080）。

## 不可违反的约定

- **响应体**一律 `{code,message,data}`；错误码见 `common/error/ErrorCode`（完整表在 `docs/backend.md` §4）。SSE 端点例外：直接返回 `text/event-stream`，建立前的错误仍是 JSON 信封。
- **SSE 帧序**：`meta`（首帧，带 `messageId`，断线重连句柄）→ `step` × N → `delta` → `citations` → `done`；`ping` 每 15s；整体 180s 超时。改这里必须同步 `frontend/src/api/sse.ts`、`SseStepSink` 与 `docs/backend.md` §9。
- **PLAN 只执行一次**：`round` 从 1 开始，RETRIEVE→REFLECT 每轮重复，`maxRounds` 封顶；证据不足或超时以 `TRUNCATED` 结束，不抛 500。
- **推理步骤是主数据**，SSE 只是实时通道：写入会话/步骤的代码路径不得依赖客户端在线。
- **会话越权 404，权限不足 403**。ADMIN 可读全部会话（审计），但**不能**替用户提问（`ChatService.start` 显式 403）。
- **改密/禁用即吊销**该用户全部 refresh token；初始化管理员与新建/重置密码的用户 `mustChangePassword=true`，改密前只允许 `me` / `change-password` / `logout`。
- **上传必须走 presign → PUT → 登记**，PUT 不能带 `Authorization`（MinIO 校验签名）。桶由应用启动时自动创建，compose 里另有 `deploy/init-bucket.sh` 提前建桶（幂等，两者不冲突）。
- **预签名必须用公网地址**：SigV4 签名覆盖 host，`app.minio.endpoint`（`minio:9000`）签出的 URL 浏览器用不了，故有 `MINIO_PUBLIC_ENDPOINT` / `app.minio.public-endpoint`；签名客户端固定 region 以避免向公网地址发起 location 查询。
- **embedding 维度固定 1024**，一个 Agent 引用的多个知识库必须同一 embedding 模型。
- **Flyway V1–V3 已发布并应用，一个字节都不要改**（包括注释）：改了会变 checksum，既有环境启动即 `validate` 失败。变更一律新建 V4+。
- 后端会话/消息/步骤用 `JdbcTemplate` 手写 SQL（避免 JPA 一级缓存返回旧值）；配置类实体才用 JPA。
- 前端只允许 `src/api/client.ts` 发 HTTP；token 存 `localStorage`，store 派生自 `tokenRevision` 而非拷贝。
- nginx 必须 `proxy_buffering off` + `proxy_read_timeout 300s`，否则 SSE 变批量返回。

## Agent 模块要点

这个模块的坑集中在「配置改了但历史会话不能变」，改动前先确认这四条：

1. **新建即版本 1**，不是「首次编辑才产生版本」。理由：第一次编辑前就已打开的会话指向版本 1，`resolveSnapshot(agentId, 1)` 必须能取到。
2. **版本只追加**：每次成功 PATCH 在同一事务里更新 `agents` 当前行并追加 `current_version+1`；`agent_versions` 不更新、不删除。
3. **会话锁定版本**：`conversations` 存 `agent_id + agent_version`，之后 Agent 怎么改都不影响已有会话。不要用「读当前配置」替换 `resolveSnapshot`。
4. **试运行不落库**：`AgentTestRunService` 直接调 `ChatOrchestrator` 而非 `ChatService`（后者总会建会话）。它复用同一套编排、同一个 `StepSink.NOOP`，并允许对已停用的 Agent 试跑（`enabled` 管的是用户能否用它提问）。

写入前集中校验（全部 400 `VALIDATION_FAILED`）：`kbIds` 非空且存在；被引用知识库都配了向量模型**且是同一个**（写入 `agents.embedding_model_id`，运行时用来发现漂移）；`nodeScope` 的键是 `kbIds` 子集且节点属于对应库；`chatModelId` 能力为 `CHAT`。
名称重复 409；删除仍有关联会话的 Agent 也是 409。

**容易写错的地方**：`AgentSnapshot` 是 `agent_versions.snapshot` 里那份 JSON 的结构本身，字段改名等于让旧版本反序列化失败——要改就加字段 + 给默认值，不要改名。

## 踩过的坑（都已在代码里修好，别再改回去）

| 现象 | 根因 | 正确做法 |
|---|---|---|
| SSE 完成时连接被 500 掐断 | `SseEmitter` 完成触发 ASYNC 派发，JWT 过滤器不再执行 ⟹ 二次鉴权判为匿名 ⟹ 响应已提交写不出错误 | `SecurityConfig` 放行 `DispatcherType.ASYNC` / `ERROR`。**MockMvc 覆盖不到，只在真容器暴露** |
| 上传成功但浏览器打不开预签名 URL | 用内网名 `minio:9000` 签名，SigV4 把 host 签进去了 | 签名客户端建在 `MINIO_PUBLIC_ENDPOINT` 上 |
| 签名时多一次 `?location=` 请求、甚至超时 | `MinioClient` 默认先查 region | 固定 `MINIO_REGION` |
| 容器 healthy 却 `connection refused` | 健康检查用 `localhost`，容器内先解析 `::1`，JVM 只监听 IPv4 | 一律写 `127.0.0.1` |
| 挂载的 `.sh` 报 `set: not found` | CRLF 让 `set -eu\r` 失效 | `.gitattributes` 固定 `*.sh`/`Dockerfile`/`*.conf`/`*.yml` 为 LF |
| 清夹具数据时外键报错 | `agents`/`knowledge_bases` 对 `models` 是 `ON DELETE RESTRICT` | 先删 agent 与知识库，再删提供商 |
| 集成用例改动 `admin` 后别的类挂了 | 整套件共用一个容器数据库 | 夹具自建用户，永不改引导管理员 |
| 前端改了 token 但界面仍是旧的 | 把 token 拷进了 `ref` | 从 storage 派生 + 读 `tokenRevision` |

## 验证清单

改完代码按影响面选，**不要只看编译通过**：

- 后端逻辑：`mvn -f backend/pom.xml test`（197 用例，必须全绿）
- 前端逻辑：`npm --prefix frontend run test` 与 `npm --prefix frontend run build`
- 动了安全链 / SSE / 预签名 / 健康检查：必须 `docker compose up -d --build` 后在真容器里验一遍
  （这几处正是 MockMvc 与 jsdom 覆盖不到的地方）
- 端到端：`powershell -File scripts/smoke.ps1`，成功输出形如
  `SMOKE OK messageId=… status=COMPLETED steps=… citations=…`
