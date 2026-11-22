# Uniapp-Web
Uniapp+Web后端打卡应用

本项目对应 KUAFUAI 开发实习题 **选项 B · 全栈应用**。用户在 UniApp H5 登录、创建习惯、每天打卡并查看该习惯的连续天数。项目采用 Vue 3、Spring Boot、MySQL 和 Redis。

H5 通过 Nginx 调用 Spring Boot。MySQL 保存用户、打卡项和每日打卡记录，Redis 保存登录会话并缓存打卡状态和连续天数。

## 快速启动

需要运行中的 Docker Linux 引擎，以及支持 `docker compose run --env-from-file` 的 Compose。在包含 `compose.yaml` 的 `Uniapp-Web` 目录打开 PowerShell，执行以下命令。首次会拉取镜像并下载构建依赖，耗时取决于网络和缓存。

```powershell
if (-not (Test-Path -LiteralPath .env)) {
    $settings = Get-Content -Raw .env.example
    $settings = $settings.Replace('replace_with_db_name', 'uniapp_web').Replace('replace_with_db_user', 'uniapp_app')
    foreach ($key in 'db_password', 'db_root_password', 'redis_password') {
        $settings = $settings.Replace("replace_with_$key", [guid]::NewGuid().ToString('N'))
    }
    Set-Content -Encoding ascii -LiteralPath .env -Value $settings
}
docker compose -p uniapp-web --env-file .env up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Compose 启动失败，请查看上方输出。' }
curl.exe --noproxy "*" --fail --silent --show-error --retry 30 --retry-all-errors --retry-delay 2 --retry-max-time 90 --max-time 4 http://localhost:5173/api/ready
```

已有 `.env` 时直接复用。就绪接口返回 `{"status":"ready","mysql":"up","redis":"up"}` 后，打开 [H5 首页](http://localhost:5173/)。首次登录前执行下文的账号初始化。若就绪检查失败，在同一目录运行 `docker compose -p uniapp-web --env-file .env logs --tail=80 api mysql redis`。

## 项目结构

| 路径 | 用途 |
| --- | --- |
| `frontend/` | UniApp Vue 3 H5 CLI 工程，使用 Node 22 构建，由 Nginx 提供页面和 `/api` 代理 |
| `backend/` | Spring Boot 3.5.16 应用，使用 Maven 和 JDK 21 构建 |
| `compose.yaml` | 编排 `frontend`、`api`、`mysql` 和 `redis` |
| `compose.local-mysql.yaml` | 可选的本机 MySQL 验收配置，使用单独的 H5/API 端口 |
| `.env.example` | MySQL 和 Redis 的本地配置样例 |

## 配置说明

手动配置时，在仓库根目录复制 `.env.example` 为 `.env`，将五个占位值替换为本机配置。用简单标识符填写 MySQL 库名和用户名。初始化变量在空的 MySQL 数据卷首次启动时生效，后续启动使用与现有数据卷匹配的 `.env`。`.env` 受 Git 忽略。

```powershell
docker compose -p uniapp-web --env-file .env config --quiet
docker compose -p uniapp-web --env-file .env pull mysql redis
docker compose -p uniapp-web --env-file .env build frontend api
docker compose -p uniapp-web --env-file .env up -d --no-build
docker compose -p uniapp-web --env-file .env ps
```

MySQL 和 Redis 通过 Compose 网络访问，连接参数按服务注入。MySQL 数据保存在 `uniapp-web_mysql_data` 命名卷。

## 初始化账号并登录

API 启动时用 Flyway 迁移数据库。就绪后，在同一目录执行以下命令。首次生成两个不同的演示密码，后续运行复用 Git 忽略的 `.env.demo`。

```powershell
if (-not (Test-Path -LiteralPath .env.demo)) {
    @(
        "DEMO_A_PASSWORD=$([guid]::NewGuid().ToString('N'))"
        "DEMO_B_PASSWORD=$([guid]::NewGuid().ToString('N'))"
    ) | Set-Content -Encoding ascii .env.demo
}
docker compose -p uniapp-web --env-file .env run --rm --no-deps --env-from-file .env.demo api --spring.main.web-application-type=none --spring.profiles.active=demo-init
Get-Content .env.demo
```

初始化只在单次运行的容器中读取演示密码。由 MySQL 唯一索引处理重复执行，保留原账号 ID 和密码摘要。

打开 [H5 首页](http://localhost:5173/)，用 `demo_a` 或 `demo_b` 配对应密码登录。点击「新建打卡项」创建「阅读」，再点「打今日卡」。确认卡片显示「今日已打卡」、连续 1 天，并且今日完成数增加 1。刷新后状态应保持。验证服务端防重时，在浏览器 Network 中重发当天的打卡 POST。首次返回 `201`，重复请求返回 `200`，两个响应包含同一个打卡记录 ID。会话有效期从登录起固定为 30 分钟。

本地 Compose 公开来源默认为 `http://localhost:5173`。通过 HTTPS 代理部署时，用 `AUTH_PUBLIC_ORIGIN` 指定实际公开来源，并设置 `AUTH_COOKIE_SECURE=true`。

## 使用本机 MySQL 验收

在 Docker Desktop 环境中，可显式启用本机 MySQL 配置。此模式通过 `host.docker.internal:3306` 访问宿主机 MySQL，只启动 H5、API 和 Redis。验收环境有独立的数据库和 Compose 项目，H5 使用 `127.0.0.1:5174`，API 使用 `127.0.0.1:8081`。浏览器按主机名保存会话 Cookie，因此请用 `127.0.0.1` 打开验收页面，与默认 `localhost:5173` 的会话隔离。

在本机 MySQL 创建独立验收库，并用 Git 忽略的 `.env.local-mysql` 保存库名和连接信息。把下列值替换为本机配置。Flyway 会在这一数据库创建应用表。

```dotenv
LOCAL_MYSQL_DATABASE=uniapp_web_local_acceptance
LOCAL_MYSQL_USERNAME=your_local_mysql_user
LOCAL_MYSQL_PASSWORD=your_local_mysql_password
```

在项目目录执行以下命令。`.env.local-mysql.demo` 采用上文 `.env.demo` 的格式，保存此验收库的演示密码。

```powershell
$localCompose = @('-f', 'compose.yaml', '-f', 'compose.local-mysql.yaml', '--env-file', '.env', '--env-file', '.env.local-mysql', '-p', 'uniapp-web-local-mysql')
docker compose @localCompose up -d --build
curl.exe --noproxy "*" --fail --silent --show-error --retry 30 --retry-all-errors --retry-delay 2 --retry-max-time 90 --max-time 4 http://127.0.0.1:5174/api/ready
docker compose @localCompose run --rm --no-deps --env-from-file .env.local-mysql.demo api --spring.main.web-application-type=none --spring.profiles.active=demo-init
Get-Content .env.local-mysql.demo
```

打开 [本机 MySQL 验收首页](http://127.0.0.1:5174/)。用 `docker compose @localCompose stop` 停止该验收环境，宿主机 MySQL 继续运行。

### 验证会话失效和恢复

按下文的独立复跑步骤启动测试项目。在测试 Redis 中对比登录前后的 `auth:session:v1:*`，把新增的测试会话键放入 `$testSessionKey`。刷新浏览器后查看 TTL，应继续下降。缩短该测试键的有效期后，等 TTL 返回 `-2` 再刷新，Network 中的 `GET /api/auth/me` 应返回会话失效的 401。

```powershell
docker compose -p $projectName --env-file $envFile exec -T redis redis-cli --raw TTL $testSessionKey
docker compose -p $projectName --env-file $envFile exec -T redis redis-cli --raw EXPIRE $testSessionKey 2
```

重新登录后，按「演练与恢复」的命令暂停并启动测试项目的 Redis。暂停期间刷新 H5，`/api/auth/me` 应返回 503，页面显示重试。恢复后再次验证登录。验收记录保留 HTTP 状态、业务码和 TTL 数字，对 Cookie 与会话键脱敏。

## 验证就绪

打开 [H5 首页](http://localhost:5173/)。首次启动时，H5 可能早于 API 就绪，Network 中会短暂出现 502。页面出现服务提示时点击「重新检查」。在 Network 中确认同源 `GET /api/ready` 返回 200，服务提示随即消失。

以下两条实测命令分别检查直连 API 和 Nginx 代理。

```powershell
curl.exe --noproxy "*" -i http://localhost:8080/api/ready
curl.exe --noproxy "*" -i http://localhost:5173/api/ready
```

API 执行 MySQL `SELECT 1` 和 Redis `PING`。两项都通过时返回 HTTP 200 和 `{"status":"ready","mysql":"up","redis":"up"}`。任一失败时返回 HTTP 503，`status` 为 `not_ready`，失败依赖为 `down`。

## 演练与恢复

在本项目的测试环境中，一次停止一个依赖并复查代理地址。每次恢复后等同一地址返回 HTTP 200，再开始下一项。

```powershell
docker compose -p uniapp-web --env-file .env stop mysql
curl.exe --noproxy "*" -i http://localhost:5173/api/ready
docker compose -p uniapp-web --env-file .env start mysql
```

MySQL 停止期间返回 HTTP 503，状态为 `mysql=down`、`redis=up`。启动后重复请求代理地址，确认恢复 200。

```powershell
docker compose -p uniapp-web --env-file .env stop redis
curl.exe --noproxy "*" -i http://localhost:5173/api/ready
docker compose -p uniapp-web --env-file .env start redis
```

Redis 停止期间返回 HTTP 503，状态为 `mysql=up`、`redis=down`。启动后重复请求代理地址，确认恢复 200，再点击 H5 的「重试登录状态」。上述 `stop` 和 `start` 操作会保留容器及 MySQL 命名卷。

## 独立复跑与验证数据卷

选一个未使用的 Compose 项目名，复制配置样例到独立的本地文件，并填写该测试库的配置。后续对测试项目统一使用这对项目名和配置文件。

```powershell
$projectName = 'uniapp-web-p04'
$envFile = '.env.p04'
Copy-Item .env.example $envFile
```

先按上文顺序执行 `config --quiet`、`pull mysql redis` 和 `build frontend api`，将命令中的 `-p uniapp-web --env-file .env` 替换为 `-p $projectName --env-file $envFile`。如果原项目占用 5173 或 8080，记录其运行服务，再用原项目的项目名和配置文件执行 `docker compose -p uniapp-web --env-file .env stop`。

```powershell
docker compose -p $projectName --env-file $envFile up -d --no-build
docker compose -p $projectName --env-file $envFile ps
```

按上文验收 H5 就绪。在独立测试库写入校验记录，使用应用账号查询并记下返回值。可以把待执行的 SQL 放入 `$sql`，再通过 MySQL 容器的应用账号执行。

```powershell
$sql | docker compose -p $projectName --env-file $envFile exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --raw --skip-column-names'
docker compose -p $projectName --env-file $envFile down
docker volume inspect "${projectName}_mysql_data" --format '{{.Name}}'
docker compose -p $projectName --env-file $envFile up -d --no-build
```

普通 `down` 会保留命名卷。重新启动后，用同一应用账号查回原记录并复查 H5。复跑结束时，用普通 `down` 释放测试项目的端口，然后用原项目的项目名和配置文件执行 `start` 启动原先运行的服务，复查就绪。

检查必填项时，另复制一份样例，删除其中的 `REDIS_PASSWORD` 赋值，并在该变量未设置的终端中对这份副本运行 `config --quiet`。预期命令失败且指出 `REDIS_PASSWORD`。
