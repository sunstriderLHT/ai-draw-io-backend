# TCR 镜像部署设计

## 目标

将 `persist` 分支的后端部署改为 GitHub Actions 推送镜像到腾讯云 TCR，生产服务器拉取该镜像并滚动重建 `backend` 容器。部署不再通过 SSH 上传源码或 JAR。

## 发布流程

1. GitHub Actions 使用现有 MySQL 服务运行后端验证测试。
2. 验证通过后，Actions 使用 Java 17 打包应用 JAR，并以应用 Dockerfile 构建镜像。
3. Actions 登录 TCR，推送两个标签：不可变的 Git SHA 和可读的 `persist`。
4. Actions 通过现有 `backend-deploy` SSH 密钥调用服务器受限命令，并只传递 Git SHA。
5. 服务器根据 root 专属配置中固定的镜像仓库与该 SHA 拼出镜像引用，拉取镜像，重建 `backend` 容器并确认容器运行。

## 配置与权限

GitHub Secrets：

- `TCR_REGISTRY`
- `TCR_NAMESPACE`
- `TCR_USERNAME`
- `TCR_PASSWORD`

服务器 `/etc/drawio-backend-deploy.conf` 由 root 创建，权限为 `600`，仅包含固定镜像仓库：

```sh
IMAGE_REPOSITORY=ccr.ccs.tencentyun.com/drawio/drawio-backend
```

生产机由 root 预先执行一次 `docker login`；登录凭据保存在 root 的 Docker 配置中。受限账户 `backend-deploy` 无法指定任意镜像，只能传入格式受限的 40 位小写 Git SHA。

## 失败处理与验证

- 测试、JAR 打包、镜像推送或服务器拉取任一步失败时，容器不重启。
- SHA 镜像标签使服务器始终拉取经过本次 CI 构建的确定版本；不以可变 `persist` 标签作为部署依据。
- 部署后通过 Compose 检查 `backend` 服务处于运行状态。
- 工作流的 shell 回归检查验证：不再包含 rsync/Maven 服务器构建，存在 TCR 登录、SHA 标签推送和受限拉取配置。

## 非目标

- 不改动业务代码或数据库。
- 不配置公开镜像仓库。
- 不清理 Docker 镜像或卷；镜像保留策略另行处理。
