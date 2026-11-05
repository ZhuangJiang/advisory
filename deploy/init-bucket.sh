#!/bin/sh
# 在后端开始接收上传之前，先把「历史共享桶」建好，且只做一次。
#
# 业务对象现在写入每个知识库自己的桶（<MINIO_BUCKET>-kb-<id>）：新建知识库时由应用创建，
# 启动完成后还会为存量知识库补齐。这里保留共享桶，是因为升级前的文件都放在它的 `kb/<id>/` 前缀下，
# 迁移脚本需要一个稳定的源桶。
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
