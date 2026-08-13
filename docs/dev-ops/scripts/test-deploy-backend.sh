#!/bin/sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_SCRIPT="$SCRIPT_DIR/deploy-backend.sh"
WORKFLOW="$SCRIPT_DIR/../../../.github/workflows/deploy-persist.yml"

grep -Fq 'test -f "$SOURCE_DIR/draw-io-front-harry-app/target/ai-agent-scaffold-app.jar"' "$DEPLOY_SCRIPT"
if grep -Fq 'mvn -B -ntp -DskipTests package' "$DEPLOY_SCRIPT"; then
  echo "production deployment must use the JAR built by CI, not run Maven on the server" >&2
  exit 1
fi
grep -Fq 'install -o root -g root -m 750 "$APP_DIR/docs/dev-ops/scripts/deploy-backend.sh"' "$DEPLOY_SCRIPT"

grep -Fq 'actions/upload-artifact@v4' "$WORKFLOW"
grep -Fq 'actions/download-artifact@v4' "$WORKFLOW"
grep -Fq 'draw-io-front-harry-app/target/ai-agent-scaffold-app.jar' "$WORKFLOW"
grep -Fq 'ServerAliveInterval=30' "$WORKFLOW"
