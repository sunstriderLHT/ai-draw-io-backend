#!/bin/sh
set -eu

RUNNER_USER=github-runner
RUNNER_HOME=/home/$RUNNER_USER
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"

if [ "$(id -u)" -ne 0 ]; then
  echo "run as root" >&2
  exit 1
fi

if ! getent group docker >/dev/null; then
  echo "Docker must be installed before configuring the GitHub runner user" >&2
  exit 1
fi
if ! id "$RUNNER_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$RUNNER_USER"
fi
usermod -aG docker "$RUNNER_USER"

install -d -o "$RUNNER_USER" -g "$RUNNER_USER" -m 700 "$RUNNER_HOME/.m2"
install -o "$RUNNER_USER" -g "$RUNNER_USER" -m 600 /dev/null "$RUNNER_HOME/.m2/settings.xml"
printf '%s\n' \
  '<?xml version="1.0" encoding="UTF-8"?>' \
  '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
  '  <mirrors>' \
  '    <mirror>' \
  '      <id>aliyun-public</id>' \
  '      <name>Aliyun public Maven proxy</name>' \
  '      <url>https://maven.aliyun.com/repository/public</url>' \
  '      <mirrorOf>central</mirrorOf>' \
  '    </mirror>' \
  '  </mirrors>' \
  '</settings>' \
  > "$RUNNER_HOME/.m2/settings.xml"
chown "$RUNNER_USER:$RUNNER_USER" "$RUNNER_HOME/.m2/settings.xml"

install -o root -g root -m 750 "$SCRIPT_DIR/deploy-backend.sh" \
  /usr/local/sbin/deploy-drawio-backend
printf '%s\n' \
  'github-runner ALL=(root) NOPASSWD: /usr/local/sbin/deploy-drawio-backend' \
  > /etc/sudoers.d/drawio-backend-github-runner
chmod 440 /etc/sudoers.d/drawio-backend-github-runner
visudo -cf /etc/sudoers.d/drawio-backend-github-runner
