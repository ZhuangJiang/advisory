#!/bin/sh
# 在后端开始接收上传之前，先把文档桶建好，且只做一次。
#
# 应用自身从不创建桶：桶缺失属于配置错误，应该在部署时就暴露出来，而不是被某个请求悄悄兜住。
# 加 `--ignore-existing` 之后，每次 `docker compose up` 重跑都安全。
set -eu

: "${MINIO_ACCESS_KEY:?MINIO_ACCESS_KEY is required}"
: "${MINIO_SECRET_KEY:?MINIO_SECRET_KEY is required}"
: "${MINIO_BUCKET:=advisory}"

# 服务端镜像把客户端放在 /opt/bin/mc，但没加进 PATH。
MC=$(command -v mc || echo /opt/bin/mc)

"$MC" alias set local http://minio:9000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
"$MC" mb --ignore-existing "local/$MINIO_BUCKET"
echo "bucket $MINIO_BUCKET is ready"
