# Draw.io Agent 部署方案复盘

本文记录前后端生产部署从手工操作到自动化过程中的方案选择、实际故障和最终流程。它面向后续维护者，避免重复踩坑。

## 最终采用的方案

### 前端

`persist` 分支推送后，GitHub Actions 负责安装依赖、类型检查、Lint 和测试；验证通过后通过 SSH 上传前端源码到服务器。服务器上的受限 `deploy` 用户只能调用 root 专属部署脚本，脚本使用服务器持久化的 `.env.production` 构建并重启前端容器。

前端 Nginx 将同源 `/api/v1/` 反向代理到私网后端。代理配置需保留 `Authorization` 和 `Idempotency-Key` 请求头；无令牌访问 `GET /api/v1/quota` 返回 `401` 是正常验证结果，表示请求确实到达后端鉴权层。

### 后端

`persist` 分支推送后：

```text
GitHub Hosted Runner
  └─ MySQL 服务 + Java 17：运行指定的后端验证测试
       ↓ 验证通过
GitHub Hosted Runner
  └─ 通过 SSH 上传源码（不上传 target/、JAR 或 Docker 镜像）
       ↓
生产服务器
  └─ root 受限部署脚本：阿里云 Maven 镜像打包 JAR
       └─ 本地 docker build（镜像标签为本次 Git SHA）
            └─ Docker Compose 重建 backend 并检查运行状态
```

这适合当前“单台生产服务器、低频发布”的规模：传输量小，生产服务器已经验证具备 Maven、Java 17 和 Docker 构建能力，不需要镜像仓库或 self-hosted Runner。

后端服务器 Maven 使用 `/home/ubuntu/.m2/settings.xml` 中的阿里云公共代理：

```xml
<mirror>
  <id>aliyun-public</id>
  <name>Aliyun public Maven proxy</name>
  <url>https://maven.aliyun.com/repository/public</url>
  <mirrorOf>central</mirrorOf>
</mirror>
```

部署脚本固定使用 Java 17，并设置 Maven 网络超时：连接 20 秒、读取 120 秒、重试 2 次。这样远端依赖仓库异常会失败，而不是无限等待。

## 方案比较

| 方案 | 流程 | 优点 | 缺点 | 结论 |
| --- | --- | --- | --- | --- |
| GitHub 构建 JAR，经 SSH 上传 | CI 测试/打包，服务器仅构建镜像 | 生产机不访问 Maven | 大 JAR 通过跨境 SSH 上传很慢 | 不采用 |
| GitHub 推送 TCR，服务器拉取镜像 | CI 测试/打包/构建/推送，服务器 pull | 多服务器部署、回滚与镜像缓存友好 | GitHub 到国内 TCR 推送约 260MB 镜像耗时约 2.5 小时 | 当前不采用，未来多机时启用 |
| 腾讯云 self-hosted Runner | GitHub 测试，服务器本地构建并部署 | 避免大文件跨境上传和 TCR 推送 | 要维护 Runner 服务；Runner 可执行仓库工作流，权限与运维复杂度更高 | 当前不采用 |
| GitHub 测试 + 上传源码 + 服务器本地构建 | 当前最终流程 | 不上传大 JAR/镜像；配置最少；Maven 国内镜像已验证 | 构建占用生产机 CPU、内存和磁盘；不适合高频、多机发布 | 当前采用 |

## 已遇到的问题与根因

### 1. 新额度接口无法访问的担忧

检查后确认 Nginx 已有 `location ^~ /api/v1/`，且正确代理到 `drawio-backend:8091`。`curl` 未携带令牌返回 `401` 不是代理故障，而是鉴权正常工作。

### 2. 前端显示“认证服务未配置”

Supabase 的两个公开配置在 Dockerfile 的 `build` 阶段存在，但运行时容器没有这两个环境变量。登录页使用服务端渲染，会在运行时读取它们，因此始终判定未配置。

修复：

- Compose 的 `frontend.environment` 同时传入 `NEXT_PUBLIC_SUPABASE_URL` 与 `NEXT_PUBLIC_SUPABASE_PUBLISHABLE_KEY`。
- 服务器的 `/home/ubuntu/drawio-front/.env.production` 必须包含这两个值。
- 这两个值是浏览器公开配置；严禁将 Supabase service-role 或 secret key 放入前端环境变量。

### 3. GitHub Actions 后端测试无法启动 Spring Context

GitHub Hosted Runner 没有生产 MySQL，Flyway 初始化失败，进一步导致 MyBatis `sqlSessionTemplate` 和 Controller 创建失败。

修复：验证 job 增加 MySQL 8 服务，并向测试注入专用 datasource、Supabase 占位发行者/JWKS 地址和测试 AI key。多模块 Maven 使用 `-am` 时，中间模块没有指定测试会导致 Surefire 失败，因此增加 `-Dsurefire.failIfNoSpecifiedTests=false`；应用模块中指定的测试仍会执行。

### 4. 服务器 Maven 长时间卡住、SSH 出现 Broken pipe

线程栈显示 Maven Resolver 在 HTTPS socket 的 `read` 中等待远端仓库响应。Actions 的 SSH 断开并不一定会终止服务器构建，因此会留下孤儿 Maven 进程。

修复：

- Maven 改用阿里云公共代理；预检已观察到约 1.8–1.9MB/s 下载，并完成 `BUILD SUCCESS`。
- 加入 Maven 连接、读取与重试限制。
- SSH 使用 `ServerAliveInterval`、`ServerAliveCountMax` 和 `ConnectTimeout`。
- 部署前若怀疑残留构建，检查 `pgrep -af 'mvn|deploy-drawio-backend'`，不要直接并发重跑。

### 5. Maven 写入 `target/` 被拒绝

旧部署方式以 `backend-deploy` 上传源码，root 使用 `rsync -a` 同步时保留了属主；手工改用 `ubuntu` 执行 Maven 时无法创建或覆盖 `target/`。

修复：手工预检前将模块源码恢复给 `ubuntu`：

```bash
sudo chown -R ubuntu:ubuntu \
  /home/ubuntu/drawio-backend/draw-io-front-harry-* \
  /home/ubuntu/drawio-backend/pom.xml
```

当前部署脚本以 root 构建，并使用 `rsync --no-owner --no-group`，不再依赖上传用户的文件属主。

### 6. 旧 Java 基础镜像标签失效

`openjdk:17-jdk-slim` 已不存在，导致 Docker 构建无法解析基础镜像。`MAINTAINER` 只是弃用警告，不是构建失败原因。

修复：运行镜像改为 `eclipse-temurin:17-jre-jammy`，并以 OCI `LABEL` 替代 `MAINTAINER`。

### 7. TCR 镜像构建显示两条版本记录

同一镜像同时打了 Git SHA 与 `persist` 两个标签。两行具有相同 SHA256 时，它们指向同一底层镜像，不会双倍占用存储。

### 8. TCR 推送耗时两小时以上

日志显示镜像构建本身约 10 秒，但 `pushing layers` 耗时 `9100` 秒；这说明瓶颈是 GitHub Runner 到国内 TCR 的跨境上传，不是 Dockerfile、Maven 或 Java 构建慢。胖 JAR 约 260MB，使每次发布都需要传输新的应用层。

因此当前恢复为上传小型源码并让服务器本地构建。若未来改为多服务器部署，应优先使用同地域构建机或腾讯云 self-hosted Runner 构建后推送 TCR。

## 常用检查命令

### 检查 Nginx API 代理

```bash
sudo docker compose --env-file ../.env.production -f docker-compose.yml \
  exec nginx nginx -T | grep -A18 -B2 'location \^~ /api/v1/'

curl -k -sS -o /dev/null -w '%{http_code}\n' \
  https://127.0.0.1/api/v1/quota
```

第二条预期是 `401`。

### 检查 Maven 镜像是否生效

```bash
sudo -u ubuntu env \
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  mvn help:effective-settings | grep -A5 -B2 aliyun-public
```

### 安全的服务器构建预检

```bash
cd /home/ubuntu/drawio-backend

sudo -u ubuntu env \
  JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  PATH=/usr/lib/jvm/java-17-openjdk-amd64/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  timeout --foreground 30m \
  mvn -B -ntp -DskipTests \
  -Dmaven.wagon.http.connectionTimeout=20000 \
  -Dmaven.wagon.http.readTimeout=120000 \
  -Dmaven.wagon.http.retryHandler.count=2 \
  package
```

### 部署后检查

```bash
sudo docker compose --env-file /home/ubuntu/drawio-backend/.env \
  -f /home/ubuntu/drawio-backend/docker-compose-production.yml \
  ps

sudo docker logs --tail 100 drawio-backend
```

## 后续维护建议

- 确认当前流程稳定后，删除未使用的 TCR GitHub Secrets、服务器 TCR 登录凭据和旧的 `/etc/drawio-backend-deploy.conf`；删除前先确认不再有其他服务使用它们。
- SSH 日志显示公网存在密码爆破尝试。完成运维调整后，应关闭 SSH 密码认证、禁止 root SSH 登录，并仅保留密钥登录。
- 若发布频率提高、服务器增至两台以上，重新启用“国内构建机/自托管 Runner → TCR → 多服务器拉取 SHA 镜像”的方案。
- 不要把生产 `.env`、私钥、Supabase secret/service-role key、TCR 密码提交到 Git。
