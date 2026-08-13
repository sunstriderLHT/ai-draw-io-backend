#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy-backend.sh"
INSTALL_SCRIPT="$SCRIPT_DIR/install-backend-deploy-user.sh"
RUNNER_INSTALL_SCRIPT="$SCRIPT_DIR/install-github-runner-user.sh"
WORKFLOW="$SCRIPT_DIR/../../../.github/workflows/deploy-persist.yml"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose-production.yml"
DOCKERFILE="$SCRIPT_DIR/../../../draw-io-front-harry-app/Dockerfile"

grep -Fq 'runs-on: [self-hosted, linux, x64, drawio-backend]' "$WORKFLOW"
grep -Fq 'docker build -f draw-io-front-harry-app/Dockerfile' "$WORKFLOW"
grep -Fq 'sudo /usr/local/sbin/deploy-drawio-backend "$GITHUB_SHA"' "$WORKFLOW"
if grep -Fq 'rsync ' "$WORKFLOW" || grep -Fq 'actions/upload-artifact@v4' "$WORKFLOW" || \
  grep -Fq 'TCR_' "$WORKFLOW" || grep -Fq 'docker/login-action' "$WORKFLOW" || grep -Fq 'ssh -i ' "$WORKFLOW"; then
  echo "self-hosted deployment workflow must not upload artifacts, use TCR, or SSH to itself" >&2
  exit 1
fi

grep -Fq 'IMAGE="drawio-backend:$RELEASE"' "$DEPLOY_SCRIPT"
grep -Fq 'docker image inspect "$IMAGE"' "$DEPLOY_SCRIPT"
grep -Fq 'DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose' "$DEPLOY_SCRIPT"
if grep -Fq 'rsync ' "$DEPLOY_SCRIPT" || grep -Fq 'mvn ' "$DEPLOY_SCRIPT" || grep -Fq 'docker build' "$DEPLOY_SCRIPT" || grep -Fq 'docker pull' "$DEPLOY_SCRIPT"; then
  echo "server deployment must start the locally built image only" >&2
  exit 1
fi

grep -Fq 'DRAWIO_BACKEND_IMAGE' "$COMPOSE_FILE"
grep -Fq 'github-runner ALL=(root) NOPASSWD: /usr/local/sbin/deploy-drawio-backend' "$RUNNER_INSTALL_SCRIPT"
grep -Fq 'https://maven.aliyun.com/repository/public' "$RUNNER_INSTALL_SCRIPT"
grep -Fq 'FROM eclipse-temurin:17-jre-jammy' "$DOCKERFILE"
if grep -Fq 'MAINTAINER ' "$DOCKERFILE"; then
  echo "Dockerfile must use OCI labels instead of the deprecated MAINTAINER instruction" >&2
  exit 1
fi
