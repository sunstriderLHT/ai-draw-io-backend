#!/bin/sh
set -eu

APP_DIR=/home/ubuntu/drawio-backend
STAGING_ROOT=/home/backend-deploy/staging
RELEASE_FILE="$STAGING_ROOT/release"
ENV_FILE="$APP_DIR/.env"
COMPOSE_FILE="$APP_DIR/docker-compose-production.yml"

test ! -L "$RELEASE_FILE"
test -f "$RELEASE_FILE"
RELEASE="$(cat "$RELEASE_FILE")"
if ! printf '%s\n' "$RELEASE" | grep -Eq '^[0-9a-f]{40}$'; then
  echo "release must be a 40-character lowercase hexadecimal Git SHA" >&2
  exit 1
fi

SOURCE_DIR="$STAGING_ROOT/$RELEASE"
test ! -L "$SOURCE_DIR"
test -d "$SOURCE_DIR"
test -f "$SOURCE_DIR/pom.xml"
test -f "$SOURCE_DIR/draw-io-front-harry-app/Dockerfile"
test -f "$SOURCE_DIR/docs/dev-ops/docker-compose-production.yml"
test -f "$ENV_FILE"

if find "$SOURCE_DIR" -type l -print -quit | grep -q .; then
  echo "staged source must not contain symbolic links" >&2
  exit 1
fi

cleanup() {
  rm -rf -- "$SOURCE_DIR"
  rm -f -- "$RELEASE_FILE"
}
trap cleanup EXIT HUP INT TERM

rsync -a --delete --exclude=.env --exclude=log/ --exclude=target/ \
  "$SOURCE_DIR/" "$APP_DIR/"
install -m 644 "$APP_DIR/docs/dev-ops/docker-compose-production.yml" "$COMPOSE_FILE"
mkdir -p "$APP_DIR/log"

cd "$APP_DIR"
mvn -B -ntp -DskipTests package
docker build -f draw-io-front-harry-app/Dockerfile \
  -t drawio-backend:latest draw-io-front-harry-app
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" up -d --no-deps backend
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" ps --status running --services \
  | grep -qx backend

