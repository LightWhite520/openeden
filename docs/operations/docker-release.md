# Release 自动构建与 Docker 启动

发布 GitHub Release 后，Actions 自动测试、生成 JAR 和发行包，并将 Docker
镜像推送到 GHCR。之后在服务器执行 Docker 拉取、启动命令即可。

## 触发与产物

`.github/workflows/release.yml` 仅监听 `release.published`。
push、PR、仅推送 tag、保存草稿或编辑 Release 不触发此流程。
原有 CLI 测试工作流仍负责 push / PR 测试。

Release 附件：

| 文件 | 用途 |
| --- | --- |
| `openeden-server.jar` | 包含 JVM 依赖的可执行 Fat JAR |
| `openeden-server.tar.gz` | 包含启动脚本和依赖的标准服务端发行包 |
| `compose.yaml` | 服务器 Docker Compose 配置 |
| `openeden.env.example` | Docker 环境变量模板 |
| `docker-image.txt` | 本次镜像的完整摘要引用 |
| `SHA256SUMS` | 附件校验和 |

镜像为 `ghcr.io/lightwhite520/openeden:<release-tag>`。
正式版还更新 `latest`；预发布版只发布对应版本标签。两者都会上传附件。

## 服务器拉取并启动

需要 x86-64 Linux、Docker Engine、Compose v2（建议 v2.24+）。
从同一 Release 下载 `compose.yaml`、`openeden.env.example`、`docker-image.txt`，
放到服务器固定目录，例如 `/opt/openeden`。

```bash
cd /opt/openeden
cp openeden.env.example .env
chmod 600 .env
```

编辑 `.env` 设置 API Key、`OPENEDEN_OPENAI_BASE_URL`、模型名称和 `OPENEDEN_IMAGE`。
`OPENEDEN_OPENAI_BASE_URL` 默认是 `https://api.openai.com/v1`，使用兼容接口时改为对应的 API 地址。
镜像可指定版本，如 `ghcr.io/lightwhite520/openeden:v1.0.0`，也可复制
`docker-image.txt` 中的 `ghcr.io/...@sha256:...` 固定到确切构建。

```bash
docker compose pull
docker compose up -d --wait --wait-timeout 600
docker compose ps
curl --fail http://127.0.0.1:8080/health
```

默认 HTTP 端口仅绑定回环地址，通过反向代理提供外部访问。
Qdrant 不发布宿主机端口。镜像内置人格和生产模型，继续使用 DJL / VQ-VAE。
首次启动可能下载 Linux 原生依赖，需要出站网络，之后使用持久缓存。

GHCR 包首次发布后确认可见性；私有包先在服务器登录：

```bash
printf '%s' "$GHCR_READ_TOKEN" | docker login ghcr.io -u YOUR_GITHUB_USER --password-stdin
```

服务器 token 只需 `read:packages` 权限。API Key 等私有配置留在服务器 `.env`。
其他配置参考根目录 `.env.example`，不要复制 Windows 绝对路径。
ChatGPT 订阅认证参考[登录文档](chatgpt-and-model-selection.md)，容器凭据目录为
`/app/data/operator/chatgpt`，保存在 `operator` 卷中。

## 更新和手动回退

修改 `.env` 中的 `OPENEDEN_IMAGE`，再执行：

```bash
docker compose pull
docker compose up -d --wait --wait-timeout 600
```

查看日志：`docker compose logs --tail=100 -f server`。
回退时将镜像改为旧版本标签或摘要，再执行相同命令。
Compose 有变更时使用目标 Release 对应的文件。

SQLite / incarnation、导出、凭据、DJL 缓存和 Qdrant 使用持久卷。
保留固定的部署目录和项目名，不要执行 `docker compose down -v`。
不要把整个 `/app/data` 挂为空目录，否则会遮盖内置模型。
已有本地 SQLite / Qdrant 数据需单独迁移，不会自动导入。
单实例更新会短暂中断服务；镜像回退不会撤销数据库迁移，升级前按需备份。

## 直接运行 JAR 或发行包

需要 Java 21。Fat JAR 包含 JVM 依赖，`persona/`、`data/models/` 是外部数据，
需另行准备与 Release 对应的版本，参见[模型配置](../local-model-training.md)。
标准发行包也需要这些外部数据。在包含这些目录的工作目录设置运行环境后执行：

```bash
java -jar openeden-server.jar
# 或解压发行包后执行其中的 bin/server
```

希望直接使用内置模型时，使用 Docker 镜像。

## 本地构建镜像

```bash
bash ./gradlew :server:installDist ensureThymosAffectModel --no-daemon
docker build -t openeden:local .
```

构建上下文使用白名单，仅包含发行包、人格 YAML 和所需模型。
Gradle、Docker 分别缓存；运行镜像只包含 JRE 与运行所需文件。
