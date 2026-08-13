#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy-backend.sh"
INSTALL_SCRIPT="$SCRIPT_DIR/install-backend-deploy-user.sh"
WORKFLOW="$SCRIPT_DIR/../../../.github/workflows/deploy-persist.yml"
COMPOSE_FILE="$SCRIPT_DIR/../docker-compose-production.yml"

grep -Fq 'docker/login-action@v3' "$WORKFLOW"
grep -Fq 'docker/build-push-action@v6' "$WORKFLOW"
grep -Fq 'TCR_REGISTRY' "$WORKFLOW"
grep -Fq 'tags:' "$WORKFLOW"
grep -Fq 'sudo /usr/local/sbin/deploy-drawio-backend $GITHUB_SHA' "$WORKFLOW"
grep -Fq 'ServerAliveInterval=30' "$WORKFLOW"
grep -Fq 'ConnectTimeout=20' "$WORKFLOW"
if grep -Fq 'rsync ' "$WORKFLOW" || grep -Fq 'actions/upload-artifact@v4' "$WORKFLOW"; then
  echo "TCR deployment workflow must not upload source or artifacts to the server" >&2
  exit 1
fi

grep -Fq 'IMAGE_REPOSITORY' "$DEPLOY_SCRIPT"
grep -Fq 'docker pull "$IMAGE"' "$DEPLOY_SCRIPT"
grep -Fq 'DRAWIO_BACKEND_IMAGE="$IMAGE" docker compose' "$DEPLOY_SCRIPT"
if grep -Fq 'rsync ' "$DEPLOY_SCRIPT" || grep -Fq 'mvn ' "$DEPLOY_SCRIPT" || grep -Fq 'docker build' "$DEPLOY_SCRIPT"; then
  echo "server deployment must pull a pre-built image" >&2
  exit 1
fi

grep -Fq 'DRAWIO_BACKEND_IMAGE' "$COMPOSE_FILE"
grep -Fq '/etc/drawio-backend-deploy.conf' "$INSTALL_SCRIPT"
