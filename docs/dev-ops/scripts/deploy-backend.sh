#!/bin/sh
set -eu

APP_DIR=/home/ubuntu/drawio-backend
ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose-production.yml"

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <40-character-lowercase-git-sha>" >&2
  exit 1
fi

RELEASE="$1"
if ! printf '%s\n' "$RELEASE" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "release must be a 40-character lowercase hexadecimal Git SHA" >&2
  exit 1
fi

test -f "$ENV_FILE"
test -f "$COMPOSE_FILE"
IMAGE="drawio-backend:$RELEASE"
docker image inspect "$IMAGE" >/dev/null

DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d --no-deps backend
DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  ps --status running --services | grep -qx backend
