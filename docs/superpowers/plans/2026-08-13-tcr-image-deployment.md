# TCR 镜像部署 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将后端 `persist` 部署从 SSH 文件上传改为 TCR 私有镜像发布与服务器精确 SHA 拉取。

**Architecture:** GitHub Actions 在验证通过后构建并推送 `<repository>:<GITHUB_SHA>` 与 `<repository>:persist`。服务器受限脚本从 root 专属配置读取固定仓库名，只接受 SHA，拉取对应镜像并通过 Compose 重建 backend。

**Tech Stack:** GitHub Actions、Docker Buildx、腾讯云 TCR、Docker Compose、POSIX shell。

## Global Constraints

- TCR 用户名、密码和地址仅来自 GitHub Secrets 或服务器 root 配置，禁止提交到仓库。
- 后端容器部署必须使用不可变的 40 位 Git SHA 标签。
- `backend-deploy` 不能通过 sudo 指定任意镜像仓库。
- 不执行镜像、卷或生产数据的清理命令。

---

### Task 1: 将 CI/CD 改为推送 TCR 镜像

**Files:** `.github/workflows/deploy-persist.yml`、`docs/dev-ops/scripts/test-deploy-backend.sh`。

**Interfaces:** 消费 GitHub Secrets `TCR_REGISTRY`、`TCR_NAMESPACE`、`TCR_USERNAME`、`TCR_PASSWORD`，产出 SHA 与 `persist` 标签的镜像。

- [ ] **Step 1: 写入失败的工作流回归检查。** 断言工作流存在 `docker/login-action@v3`、`docker/build-push-action@v6`、`TCR_REGISTRY`、`tags:`，且不包含 `rsync` 或 `actions/upload-artifact@v4`。
- [ ] **Step 2: 运行检查，确认失败。** 执行 `sh docs/dev-ops/scripts/test-deploy-backend.sh`；预期因缺少 TCR 构建步骤退出非 0。
- [ ] **Step 3: 最小改造工作流。** 保留验证与 Java 17 打包；deploy job 用 TCR Secrets 登录、构建应用 Dockerfile、推送 SHA 和 `persist` 标签。移除 artifact、rsync 和源码上传；SSH 仅调用部署脚本并传递 `$GITHUB_SHA`。
- [ ] **Step 4: 运行检查，确认通过。** 执行 `sh docs/dev-ops/scripts/test-deploy-backend.sh`；预期退出 0。
- [ ] **Step 5: 提交。** 执行 `git add .github/workflows/deploy-persist.yml docs/dev-ops/scripts/test-deploy-backend.sh` 后提交 `feat: publish backend images to TCR`。

### Task 2: 将服务器部署脚本改为拉取固定镜像

**Files:** `docs/dev-ops/docker-compose-production.yml`、`docs/dev-ops/scripts/deploy-backend.sh`、`docs/dev-ops/scripts/install-backend-deploy-user.sh`、`docs/dev-ops/scripts/test-deploy-backend.sh`。

**Interfaces:** 消费 `sudo /usr/local/sbin/deploy-drawio-backend <40-lowercase-hex-sha>` 和 `/etc/drawio-backend-deploy.conf` 的 `IMAGE_REPOSITORY`，产出已拉取 SHA 镜像并在运行中的 `backend` 服务。

- [ ] **Step 1: 写入失败的服务器脚本回归检查。** 断言部署脚本读取 root 配置、校验单个 SHA 参数、调用 `docker pull`，且不包含 `rsync`、`mvn`、`docker build`；断言 Compose 从 `DRAWIO_BACKEND_IMAGE` 获取 image。
- [ ] **Step 2: 运行检查，确认失败。** 执行 `sh docs/dev-ops/scripts/test-deploy-backend.sh`；预期因镜像拉取断言失败退出非 0。
- [ ] **Step 3: 最小改造服务器脚本和 Compose。** 安装脚本创建权限 `600` 的 `/etc/drawio-backend-deploy.conf` 模板，不写入凭据。部署脚本只接受 SHA 参数，读取固定仓库，执行 `docker pull`，以 `DRAWIO_BACKEND_IMAGE` 调用 Compose 重建服务并确认运行。
- [ ] **Step 4: 运行 shell 语法与回归检查。** 执行 `sh -n docs/dev-ops/scripts/deploy-backend.sh && sh docs/dev-ops/scripts/test-deploy-backend.sh && git diff --check`；预期退出 0。
- [ ] **Step 5: 提交。** 执行 `git add docs/dev-ops/docker-compose-production.yml docs/dev-ops/scripts` 后提交 `feat: pull backend releases from TCR`。
