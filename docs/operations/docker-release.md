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
| `openeden-models-<SHA256>.tar.gz` | 独立模型包，首次启动下载后持久保存 |
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
Release 附件中的环境变量模板已填写本次镜像摘要。镜像也可指定版本，如
`ghcr.io/lightwhite520/openeden:v1.0.0`，或复制
`docker-image.txt` 中的 `ghcr.io/...@sha256:...` 固定到确切构建。

```bash
docker compose pull
docker compose up -d --wait --wait-timeout 3600
docker compose ps
curl --fail http://127.0.0.1:8080/health
```

默认 HTTP 端口仅绑定回环地址，通过反向代理提供外部访问。
Qdrant 不发布宿主机端口。镜像保留程序和人格，生产模型独立存放，继续使用 DJL / VQ-VAE。
首次启动会从该 Release 下载独立模型包，验证 SHA-256 后解压到 `models` 持久卷。
首次下载耗时取决于网络速度，可通过 `docker compose logs -f server` 查看进度。
以后重启、重建容器或更新程序，只要模型内容不变，均直接复用缓存，不重新下载。
模型内容发生变化时，才下载新的模型包，旧缓存保留供回退使用。
首次启动可能下载 Linux 原生依赖，需要出站网络，之后使用持久缓存。

如果服务器访问 GitHub Release 较慢，可以自行下载模型附件，再通过自己的下载地址提供。
在 `.env` 中设置 `OPENEDEN_MODEL_DOWNLOAD_URL` 为该文件的直链即可，文件内容必须与
原始附件完全相同，校验仍然生效。镜像本身不再包含模型文件。

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

SQLite / incarnation、导出、凭据、模型、DJL 缓存和 Qdrant 使用持久卷。
保留固定的部署目录和项目名，不要执行 `docker compose down -v`。
只挂载 Compose 列出的各个子目录，不要用一个空目录覆盖整个 `/app/data`。
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

可以下载独立模型包，解压到工作目录的 `data/models/`，另行准备同版本 `persona/`。
Docker 镜像会自动下载和缓存模型。

## 从此前包含模型的 beta 镜像更新

重新下载本版本的 `compose.yaml`，新增的 `models:/app/data/models` 挂载必须保留。
原来的 `.env` 可以继续使用，但 `OPENEDEN_IMAGE` 若固定为旧摘要，需要改为新版摘要
或 `ghcr.io/lightwhite520/openeden:v1.0.0-beta.1`，再执行 `docker compose pull`。
首次切换到独立模型卷时会初始化一次模型；后续升级复用该卷。
已有运行状态、凭据和 Qdrant 卷保持原样，不要删除卷来更新。

## 本地构建镜像

```bash
bash ./gradlew :server:installDist ensureThymosAffectModel --no-daemon
bash deploy/package-models.sh LightWhite520/openeden v1.0.0-beta.1
docker build -t openeden:local .
```

构建上下文使用白名单，仅包含发行包、人格 YAML、启动脚本和模型摘要清单。
`data/models/` 不会传入 Docker 构建上下文，模型附件位于 `release-assets/`。
Gradle、Docker 分别缓存；运行镜像只包含 JRE 与运行所需文件。
