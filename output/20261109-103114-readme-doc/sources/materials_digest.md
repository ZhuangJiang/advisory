# 资料摘要（materials_digest）

## 资料一：README.md（仓库根目录，171 行）

**来源文件**：`E:/webapps-ai/advisory/README.md`

**定位原句**：面向企业知识库的智能顾问平台：文档入库（解析 / 切分 / 向量化）、混合检索、四阶段推理编排（PLAN → RETRIEVE → REFLECT → ANSWER）、SSE 实时推送与管理员审计。

**技术栈（四层）**

| 层 | 内容 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.4（单副本） |
| 数据库 | PostgreSQL 16 + pgvector + pg_trgm + ltree，Flyway 管理表结构 |
| 对象存储 | MinIO（文档原文件与解析中间产物） |
| 前端 | Vue 3 + Vite + TypeScript（Element Plus），生产由 nginx 托管并反代 `/api` |

**文档索引**：backend.md、frontend.md、acceptance.md、AGENTS.md、docs/superpowers/specs/。

**本地启动**：`docker compose up -d postgres minio minio-init` → `mvn -f backend/advisory-server/pom.xml spring-boot:run`（需显式 `JAVA_HOME` 指向 JDK 21）→ `npm --prefix frontend install && npm --prefix frontend run dev`（5173，代理到 20001）→ 健康检查 `http://localhost:20001/actuator/health`。首次启动由 Flyway 自动执行迁移建表。

**默认端口**：PostgreSQL `5432`（库/用户/密码 `advisory`）；MinIO S3 API `9000`、控制台 `9001`；后端 `20001`；天气 MCP `21001`；前端 80（dev 5173）。

**生产部署**：`docker compose up -d --build`，五个服务（postgres / minio / backend / frontend / weather-mcp）全部 healthy；入口 `http://localhost/`，SPA `try_files` 回退、`/api/` 反代。SSE 参数固定在 `deploy/nginx.conf`（`proxy_buffering off`、`proxy_read_timeout 300s`）。必须设置 `MINIO_PUBLIC_ENDPOINT`（预签名按该主机签名，留空回落 `MINIO_ENDPOINT` 会导致上传失败）。镜像默认阿里云 Maven 镜像，可用 `--build-arg MAVEN_MIRROR=` 换官方源。后端单副本，不支持横向扩容。MCP 服务独立于主链路。

**配置**：全部环境变量注入，默认值见 `.env.example`（复制为 `.env`，`.env` 不入库）。

**安全提示（原句）**：初始管理员账号默认为 `admin` / `admin`，且首次登录被标记为「必须修改密码」。请在首次启动后立刻修改默认密码，生产部署前务必设置 `ADMIN_PASSWORD`、`JWT_SECRET`、`APP_ENCRYPTION_KEY` 与 `MINIO_PUBLIC_ENDPOINT`。

**测试**：`mvn -f backend/pom.xml test`（聚合：advisory-server 276 用例需 Docker + weather-mcp 12 + news-mcp 33）；可单独跑主服务或天气 MCP（无需 Docker）；`npm --prefix frontend run test`；`npm --prefix frontend run build`。冒烟 `scripts/smoke.ps1` 需要 chat 与 1024 维 embedding 端点，未配置以退出码 2 跳过。无模型环境用 `scripts/fake-model-server.mjs`（OpenAI 兼容桩）。

**目录结构**：`backend/`（Maven 聚合：`advisory-server/` 主后端、`mcp-server/` 含 `weather-mcp/`、`news-mcp/`）、`frontend/`、`deploy/`、`scripts/`、`docs/`。

**MCP 服务族**：`backend/mcp-server` 每个子模块 = 一个独立部署的 Spring Boot 进程，与 `advisory-server` 无代码与数据依赖；新增只需加一个 `<module>`。两个服务均为 Spring AI MCP Server + SSE、无数据库、数据源可切 stub：
- `weather-mcp` 21001，工具 `get_current_weather`、`get_weather_forecast`，数据源 Open-Meteo（免费、无 Key）
- `news-mcp` 21002，工具 `search_news`、`get_headlines`，数据源 RSS 聚合（中新网、新浪、IT之家等 6 源，免费、无 Key）

MCP 端点 `/sse`（消息端点 `/mcp/message`），健康检查 `/actuator/health`；离线 `WEATHER_PROVIDER=stub` / `NEWS_PROVIDER=stub`。新闻边界：RSS 只能拿到各源最近几十条，`search_news` 是「在最近新闻里筛」，匹配标题 + 摘要 + 正文；免 Key 全网检索 API 基本绝迹。对话接入：`advisory-server` 后台连两个端点（`WEATHER_MCP_URL` / `NEWS_MCP_URL`），编排器按问题交给模型选择；MCP 没起来自动回退检索流程，`MCP_ENABLED=false` 可整体关掉。前提：① Agent 绑定的 chat 模型支持 function calling（qwen2.5:7b、llama3.1:8b 可以，deepseek-r1 不行）；② 提供商地址对 backend 可达，推荐 `http://host.docker.internal:11434`。端口分段：后端服务 20001–20999，MCP 21001–21999。客户端接入示例为 Claude Desktop 的 `mcpServers` JSON。

## 资料二：用户提供的仓库地址

**Git 仓库**：`https://gitee.com/buile/advisory.git`

**本地核对**（非资料内容，仅用于校验）：当前分支 `feat/advisory-platform-mvp`，最近提交 `d4b47cb`（2026-11-06，feat(mcp): 新增 news-mcp）。
