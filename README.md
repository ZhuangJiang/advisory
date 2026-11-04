# Advisory Platform

面向企业知识库的智能顾问平台：文档入库（解析 / 切分 / 向量化）、混合检索、四阶段推理编排（PLAN → RETRIEVE → REFLECT → ANSWER）、SSE 实时推送与管理员审计。

- 后端：Java 21 + Spring Boot 3.4（单副本）
- 数据库：PostgreSQL 16 + pgvector + pg_trgm + ltree（Flyway 管理表结构）
- 对象存储：MinIO（文档原文件与解析中间产物）
- 前端：Vue 3 + Vite + TypeScript（Element Plus），生产由 nginx 托管并反代 `/api`

## 本地启动

```powershell
# 1. 启动依赖服务（PostgreSQL / MinIO / 建桶）
docker compose up -d postgres minio minio-init

# 2. 运行后端（Windows 下需显式指定 JDK 21；本机默认 JAVA_HOME 可能是 JDK 8）
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml spring-boot:run

# 3. 运行前端（5173，/api 代理到 8080）
npm --prefix frontend install
npm --prefix frontend run dev

# 4. 健康检查
curl http://localhost:8080/actuator/health
```

首次启动由 Flyway 自动执行 `backend/src/main/resources/db/migration/` 下的迁移建表。

- PostgreSQL：`localhost:5432`，库 / 用户 / 密码默认 `advisory` / `advisory` / `advisory`
- MinIO S3 API：`localhost:9000`，控制台 `localhost:9001`
- 后端：`localhost:8080`

## 生产部署（全栈容器）

```powershell
docker compose up -d --build
docker compose ps          # postgres / minio / backend / frontend 全部 healthy
```

- 入口：`http://localhost/`（nginx，80 端口）。SPA 走 `try_files` 回退，`/api/` 反代到后端。
- SSE 参数已固定在 `deploy/nginx.conf`：`proxy_buffering off`、`proxy_read_timeout 300s`；改成默认值会让推理过程变成"最后一次性返回"。
- **必须设置 `MINIO_PUBLIC_ENDPOINT`**：预签名 URL 按该主机签名，浏览器要能直接访问（本地默认 `http://localhost:9000`，生产填对外域名）。留空则回落到 `MINIO_ENDPOINT`，此时容器内的 `minio:9000` 浏览器不可达，上传会失败。
- 镜像构建默认使用阿里云 Maven 镜像；如需官方源，`docker compose build --build-arg MAVEN_MIRROR=`。
- 后端单副本：文档解析任务与 SSE 编排在进程内，不支持横向扩容。

## 配置

所有可变配置通过环境变量注入，默认值见 `.env.example`（复制为 `.env` 使用，`.env` 不入库）。

## 安全提示

> **初始管理员账号默认为 `admin` / `admin`，且首次登录被标记为「必须修改密码」。请在首次启动后立刻修改默认密码，生产部署前务必设置 `ADMIN_PASSWORD`、`JWT_SECRET`、`APP_ENCRYPTION_KEY` 与 `MINIO_PUBLIC_ENDPOINT`。**

## 测试与验收

```powershell
$env:JAVA_HOME='D:\Program Files\Java\jdk-21.0.9+10'
mvn -f backend/pom.xml test          # 197 用例，需 Docker（Testcontainers）
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
backend/          Spring Boot 后端（Maven，artifact: advisory-backend）
frontend/         Vue 3 SPA（Vite；生产镜像由 nginx 托管）
deploy/           nginx.conf、init-bucket.sh
scripts/          smoke.ps1（端到端冒烟）、fake-model-server.mjs（本地模型桩）
docs/             设计 spec、实施计划、验收记录
```
