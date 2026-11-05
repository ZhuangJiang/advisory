# Advisory Platform

面向企业知识库的智能顾问平台：文档入库（解析 / 切分 / 向量化）、混合检索、四阶段推理编排（PLAN → RETRIEVE → REFLECT → ANSWER）、SSE 实时推送与管理员审计。

- 后端：Java 21 + Spring Boot 3.4（单副本）
- 数据库：PostgreSQL 16 + pgvector + pg_trgm + ltree（Flyway 管理表结构）
- 对象存储：MinIO（文档原文件与解析中间产物）
- 前端：Vue 3 + Vite + TypeScript（Element Plus），生产由 nginx 托管并反代 `/api`

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/backend.md`](docs/backend.md) | 后端分层、请求链路、权限矩阵、数据模型、入库状态机、检索与编排、错误码、测试与改动指引 |
| [`docs/frontend.md`](docs/frontend.md) | 前端目录、HTTP/SSE 封装、状态管理、路由与权限、页面组件、测试与改动指引 |
| [`docs/acceptance.md`](docs/acceptance.md) | 逐条验收结果与验收中修掉的缺陷 |
| [`AGENTS.md`](AGENTS.md) | 跨前后端的硬约定、Agent 模块要点、踩过的坑、验证清单 |
| [`docs/superpowers/specs/`](docs/superpowers/specs/) | 设计 spec 与实施计划（裁决 R1–R9 的出处） |

## 本地启动

```powershell
# 1. 启动依赖服务（PostgreSQL / MinIO / 建桶）
docker compose up -d postgres minio minio-init

# 2. 运行后端（Windows 下需显式指定 JDK 21；本机默认 JAVA_HOME 可能是 JDK 8）
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/advisory-server/pom.xml spring-boot:run

# 3. 运行前端（5173，/api 代理到 20001）
npm --prefix frontend install
npm --prefix frontend run dev

# 4. 健康检查
curl http://localhost:20001/actuator/health
```

首次启动由 Flyway 自动执行 `backend/advisory-server/src/main/resources/db/migration/` 下的迁移建表。

- PostgreSQL：`localhost:5432`，库 / 用户 / 密码默认 `advisory` / `advisory` / `advisory`
- MinIO S3 API：`localhost:9000`，控制台 `localhost:9001`
- 后端：`localhost:20001`；天气 MCP 服务：`localhost:21001`

## 生产部署（全栈容器）

```powershell
docker compose up -d --build
docker compose ps          # postgres / minio / backend / frontend / weather-mcp 全部 healthy
```

- 入口：`http://localhost/`（nginx，80 端口）。SPA 走 `try_files` 回退，`/api/` 反代到后端。
- SSE 参数已固定在 `deploy/nginx.conf`：`proxy_buffering off`、`proxy_read_timeout 300s`；改成默认值会让推理过程变成"最后一次性返回"。
- **必须设置 `MINIO_PUBLIC_ENDPOINT`**：预签名 URL 按该主机签名，浏览器要能直接访问（本地默认 `http://localhost:9000`，生产填对外域名）。留空则回落到 `MINIO_ENDPOINT`，此时容器内的 `minio:9000` 浏览器不可达，上传会失败。
- 镜像构建默认使用阿里云 Maven 镜像；如需官方源，`docker compose build --build-arg MAVEN_MIRROR=`。
- 后端单副本：文档解析任务与 SSE 编排在进程内，不支持横向扩容。
- **MCP 服务独立于主链路**：`weather-mcp` 是单独容器（`21001`），backend 停了它照常服务，反之亦然。

## 配置

所有可变配置通过环境变量注入，默认值见 `.env.example`（复制为 `.env` 使用，`.env` 不入库）。

## 安全提示

> **初始管理员账号默认为 `admin` / `admin`，且首次登录被标记为「必须修改密码」。请在首次启动后立刻修改默认密码，生产部署前务必设置 `ADMIN_PASSWORD`、`JWT_SECRET`、`APP_ENCRYPTION_KEY` 与 `MINIO_PUBLIC_ENDPOINT`。**

## 测试与验收

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml test                 # 聚合构建：advisory-server 271 用例（需 Docker）+ weather-mcp 12 用例
mvn -f backend/advisory-server/pom.xml test # 只跑后端主服务
mvn -f backend/mcp-server/weather-mcp/pom.xml test   # 只跑天气 MCP，无需 Docker
npm --prefix frontend run test       # 37 用例
npm --prefix frontend run build      # vue-tsc + vite build

# 端到端冒烟：需要 chat 与 1024 维 embedding 端点；
# 未配置时以退出码 2 明确跳过，不会假报成功。
$env:SMOKE_CHAT_BASE_URL='http://host:11434'
$env:SMOKE_CHAT_MODEL='qwen2.5:7b'
$env:SMOKE_EMBED_MODEL='bge-m3'
$env:SMOKE_PROVIDER_TYPE='OLLAMA'
powershell -File scripts/smoke.ps1
```

无模型环境可用 `scripts/fake-model-server.mjs`（OpenAI 兼容桩，仅验证链路）：

```powershell
docker run -d --rm --name advisory-fake-model --network advisory_default `
  -v "$PWD/scripts:/scripts:ro" node:20-alpine node /scripts/fake-model-server.mjs
$env:SMOKE_CHAT_BASE_URL='http://advisory-fake-model:8099'
$env:SMOKE_EMBED_BASE_URL='http://advisory-fake-model:8099'
$env:SMOKE_CHAT_MODEL='fake-chat'; $env:SMOKE_EMBED_MODEL='fake-embed'
$env:SMOKE_PROVIDER_TYPE='OPENAI_COMPATIBLE'
powershell -File scripts/smoke.ps1
```

逐条验收结果见 [`docs/acceptance.md`](docs/acceptance.md)。

## 目录

```
backend/                       Maven 聚合工程（packaging=pom，锁版本与 JDK 21）
  advisory-server/             主后端（原 backend，artifact: advisory-server）
  mcp-server/                  MCP 服务族聚合（packaging=pom）
    weather-mcp/               天气 MCP 服务（Spring AI MCP Server，SSE，:21001）
frontend/         Vue 3 SPA（Vite；生产镜像由 nginx 托管）
deploy/           nginx.conf、init-bucket.sh
scripts/          smoke.ps1（端到端冒烟）、fake-model-server.mjs（本地模型桩）
docs/             backend.md、frontend.md（说明文档）；acceptance.md（验收）；spec 与实施计划
```

## MCP 服务

`backend/mcp-server` 是一个可扩展的 MCP 服务族：每个子模块 = 一个独立部署的 Spring Boot 进程，
与 `advisory-server` 无代码与数据依赖，新增服务只需在 `backend/mcp-server/pom.xml` 里加一个 `<module>`。

当前提供 `weather-mcp`（天气查询，数据源 Open-Meteo，无需 API Key）：

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/mcp-server/weather-mcp/pom.xml spring-boot:run
```

- MCP 端点：`http://localhost:21001/sse`（消息端点 `/mcp/message`），健康检查 `/actuator/health`
- 工具：`get_current_weather(city)`、`get_weather_forecast(city, days)`
- 离线开发：`WEATHER_PROVIDER=stub`，返回固定假数据，不访问公网
- **对话里怎么用**：`advisory-server` 会在后台连上这个端点（`WEATHER_MCP_URL`）。
  问「北京今天天气怎么样」时编排器直接调工具作答、不检索知识库；MCP 没起来时自动回退到原来的检索流程，
  不会让会话失败。`MCP_ENABLED=false` 可整体关掉。
- **能调起来的前提**（缺一不可，两点都容易踩）：
  1. Agent 绑定的 chat 模型**支持 function calling**。`qwen2.5:7b`、`llama3.1:8b` 可以；
     `deepseek-r1` 不行——它接受 `tools` 参数却从不调用，会凭记忆编造天气（被调用计数校验拦下）。
  2. 提供商地址对 backend 可达。**推荐统一用 `http://host.docker.internal:11434`**：
     容器内由 Docker DNS 解析到宿主网关；Windows 宿主由 hosts 记录解析到 `127.0.0.1`
     （WSL2 的 localhost 端口转发），本地 `mvn` 与容器两种跑法共用同一个地址。
     前提是 hosts 文件里 Docker Desktop 写入的 `host.docker.internal` 指向 `127.0.0.1`
     （若指向旧 IP，请修正；备份在调整时已留）。
- 端口约定：**后端服务 20001–20999**（`advisory-server` 20001，新增后端服务从 20002 顺延），
  **MCP 服务 21001–21999**（`weather-mcp` 21001，新增 MCP 服务从 21002 顺延）；基础设施保留默认
  （PostgreSQL 5432、MinIO 9000/9001、前端 80 / dev 5173）。
- 端口冲突：本地 IDE/`mvn` 已占 21001 时，容器用 `WEATHER_MCP_PORT=21002 docker compose up -d weather-mcp`；
  容器之间仍走 `http://weather-mcp:21001`。后端同理用 `BACKEND_PORT`。
- 客户端接入（以 Claude Desktop 为例）：

```json
{
  "mcpServers": {
    "weather": {
      "url": "http://localhost:21001/sse"
    }
  }
}
```
