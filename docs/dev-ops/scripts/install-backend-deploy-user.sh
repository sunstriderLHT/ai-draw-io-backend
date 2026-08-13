#!/bin/sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "usage: $0 /path/to/backend-deploy-user-public-key" >&2
  exit 1
fi

PUBLIC_KEY_FILE="$1"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

if ! id backend-deploy >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash backend-deploy
fi
passwd --lock backend-deploy
install -d -o backend-deploy -g backend-deploy -m 700 \
  /home/backend-deploy/.ssh /home/backend-deploy/staging
install -o backend-deploy -g backend-deploy -m 600 "$PUBLIC_KEY_FILE" \
  /home/backend-deploy/.ssh/authorized_keys
install -o root -g root -m 750 "$SCRIPT_DIR/deploy-backend.sh" \
  /usr/local/sbin/deploy-drawio-backend
printf '%s\n' 'backend-deploy ALL=(root) NOPASSWD: /usr/local/sbin/deploy-drawio-backend' \
  > /etc/sudoers.d/drawio-backend-deploy
chmod 440 /etc/sudoers.d/drawio-backend-deploy
visudo -cf /etc/sudoers.d/drawio-backend-deploy
