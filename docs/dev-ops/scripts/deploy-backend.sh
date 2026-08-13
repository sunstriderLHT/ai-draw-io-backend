#!/bin/sh
set -eu

APP_DIR=/home/ubuntu/drawio-backend
ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose-production.yml"
DEPLOY_CONFIG=/etc/drawio-backend-deploy.conf

if [ "$#" -ne 1 ]; then
  echo "usage: $0 <40-character-lowercase-git-sha>" >&2
  exit 1
fi

RELEASE="$1"
if ! printf '%s\n' "$RELEASE" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "release must be a 40-character lowercase hexadecimal Git SHA" >&2
  exit 1
fi

test ! -L "$DEPLOY_CONFIG"
test -f "$DEPLOY_CONFIG"
# This file is root-owned and mode 600; it pins the only image repository a
# restricted deployment account may release from.
. "$DEPLOY_CONFIG"
: "${IMAGE_REPOSITORY:?IMAGE_REPOSITORY must be set in $DEPLOY_CONFIG}"

test -f "$ENV_FILE"
test -f "$COMPOSE_FILE"
IMAGE="$IMAGE_REPOSITORY:$RELEASE"

docker pull "$IMAGE"
DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d --no-deps backend
DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  ps --status running --services | grep -qx backend
