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

rsync -a --delete --no-owner --no-group --exclude=.env --exclude=log/ --exclude=target/ \
  "$SOURCE_DIR/" "$APP_DIR/"
install -m 644 "$APP_DIR/docs/dev-ops/docker-compose-production.yml" "$COMPOSE_FILE"
install -o root -g root -m 750 "$APP_DIR/docs/dev-ops/scripts/deploy-backend.sh" \
  /usr/local/sbin/deploy-drawio-backend
mkdir -p "$APP_DIR/log"

cd "$APP_DIR"
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
mvn -s /home/ubuntu/.m2/settings.xml -B -ntp -DskipTests \
  -Dmaven.wagon.http.connectionTimeout=20000 \
  -Dmaven.wagon.http.readTimeout=120000 \
  -Dmaven.wagon.http.retryHandler.count=2 \
  package
IMAGE="drawio-backend:$RELEASE"
docker build -f draw-io-front-harry-app/Dockerfile -t "$IMAGE" draw-io-front-harry-app
DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  up -d --no-deps backend
DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" \
  ps --status running --services | grep -qx backend
