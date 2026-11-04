#!/bin/sh
# Creates the document bucket once, before the backend accepts uploads.
#
# The application never creates buckets itself: a missing bucket is a configuration error that
# should be visible at deploy time, not something a request silently fixes. `--ignore-existing`
# makes this safe to re-run on every `docker compose up`.
set -eu

: "${MINIO_ACCESS_KEY:?MINIO_ACCESS_KEY is required}"
: "${MINIO_SECRET_KEY:?MINIO_SECRET_KEY is required}"
: "${MINIO_BUCKET:=advisory}"

# The server image ships the client at /opt/bin/mc but not on PATH.
MC=$(command -v mc || echo /opt/bin/mc)

"$MC" alias set local http://minio:9000 "$MINIO_ACCESS_KEY" "$MINIO_SECRET_KEY" >/dev/null
"$MC" mb --ignore-existing "local/$MINIO_BUCKET"
echo "bucket $MINIO_BUCKET is ready"
