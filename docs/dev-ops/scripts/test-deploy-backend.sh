#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy-backend.sh"
INSTALL_SCRIPT="$SCRIPT_DIR/install-backend-deploy-user.sh"
WORKFLOW="$SCRIPT_DIR/../../../.github/workflows/deploy-persist.yml"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose-production.yml"
DOCKERFILE="$SCRIPT_DIR/../../../draw-io-front-harry-app/Dockerfile"

grep -Fq 'runs-on: ubuntu-latest' "$WORKFLOW"
grep -Fq 'Upload validated backend source' "$WORKFLOW"
grep -Fq 'rsync -a --delete --info=progress2 --timeout=300' "$WORKFLOW"
grep -Fq 'sudo /usr/local/sbin/deploy-drawio-backend' "$WORKFLOW"
grep -Fq 'ServerAliveInterval=30' "$WORKFLOW"
grep -Fq 'ConnectTimeout=20' "$WORKFLOW"
if grep -Fq 'TCR_' "$WORKFLOW" || grep -Fq 'docker/login-action' "$WORKFLOW" || \
  grep -Fq 'docker/build-push-action' "$WORKFLOW" || grep -Fq 'runs-on: [self-hosted' "$WORKFLOW"; then
  echo "source-upload deployment must not use TCR or a self-hosted runner" >&2
  exit 1
fi

grep -Fq 'mvn -s /home/ubuntu/.m2/settings.xml -B -ntp -DskipTests' "$DEPLOY_SCRIPT"
grep -Fq 'docker build -f draw-io-front-harry-app/Dockerfile' "$DEPLOY_SCRIPT"
grep -Fq 'IMAGE="drawio-backend:$RELEASE"' "$DEPLOY_SCRIPT"
grep -Fq 'DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose' "$DEPLOY_SCRIPT"
if grep -Fq 'docker pull' "$DEPLOY_SCRIPT"; then
  echo "server deployment must use its locally built image" >&2
  exit 1
fi

grep -Fq 'DRAWIO_BACKEND_IMAGE' "$COMPOSE_FILE"
if grep -Fq 'TCR' "$INSTALL_SCRIPT" || grep -Fq 'drawio-backend-deploy.conf' "$INSTALL_SCRIPT"; then
  echo "deployment installer must not retain TCR configuration" >&2
  exit 1
fi
grep -Fq 'FROM eclipse-temurin:17-jre-jammy' "$DOCKERFILE"
if grep -Fq 'MAINTAINER ' "$DOCKERFILE"; then
  echo "Dockerfile must use OCI labels instead of the deprecated MAINTAINER instruction" >&2
  exit 1
fi
