# 本地部署手册 v1（W1 Phase 1：中间件）

> 对应计划：`docs/plans/plan-w1-infra-up.md` Phase 1。本版覆盖**中间件 docker-compose 编排**；
> 基座四服务 + 管理端的启动在 Phase 2 补充「服务启动顺序与分批策略」。
> 内存限额与分批启动详见 [middleware-limits.md](middleware-limits.md)。

## 1. 前置条件

| 项 | 要求 | 备注 |
| --- | --- | --- |
| Docker Desktop | 已安装并可启动（WSL2 后端） | `docker version` 客户端/服务端都能出版本号才算就绪 |
| Docker Compose | V2.20.2+ | `docker compose version` 可查；`depends_on.condition` 需要 2.20+ |
| Docker VM 内存 | 建议 10 GB | Docker Desktop → Settings → Resources → Memory；依据见 middleware-limits.md §2 |
| 端口占用 | MySQL 宿主端口默认 **13306**、Redis 默认 **16379**（本机 3306/6379 已分别被本地 mysqld、redis-server 占用，见 §6）；另占用 8848/9848/8080/9876/10911/8091/7091/9090/9000/9001/5432 | 与本机已有服务冲突时改 `docker/.env` 的 `*_HOST_PORT` |

## 2. 首次部署（5 步）

```powershell
# ① 生成本地环境变量（不入库，.gitignore 已忽略 docker/.env）
Copy-Item docker/.env.template docker/.env
# ② 修改占位密码：全部 ChangeMe_* 值必须替换（AGENTS.md R-1：真实密码只存在本地 .env）
notepad docker\.env
# ③ 语法校验（不启动）
docker compose -f docker/middleware.yaml config -q
# ④ 一键启动（默认组 8 容器，不含 MinIO——阻塞见 §6；或分批：docker/start-middleware.ps1 -CoreOnly）
docker/start-middleware.ps1
# ⑤ 等待全部 healthy 后确认
docker compose -f docker/middleware.yaml ps
```

首次启动 mysql 容器会自动执行初始化（`/docker-entrypoint-initdb.d`）：

1. `01-create-databases.sql`：建 `ruoyi-vue-pro`（基座业务库）+ `pm_log_0..3`（D18 分片预留，四个空 schema，utf8mb4）；
2. `02-grants.sh`：给业务账号授权 `pm_log_0..3`，创建 XXL-Job 专用账号；
3. `03-tables_xxl_job.sql`：xuxueli/xxl-job **v2.4.0 官方** `tables_xxl_job.sql`（原样，出处见文件头），建 `xxl_job` 库。

> 基座业务数据（`ruoyi-vue-pro` 的表与初始数据）在 **Phase 2** 从基座仓库 `sql/mysql/ruoyi-vue-pro.sql` 导入（该 SQL 不进本仓库），命令届时由 Phase 2 文档给出；导入前 `ruoyi-vue-pro` 为空库。
> pgvector：镜像已内置 vector 0.8.7 扩展，但需在建库阶段 `CREATE EXTENSION vector;`（W7 落地时执行）。

## 3. 一键起停

| 动作 | 命令 |
| --- | --- |
| 全量启动（默认组 8 容器，不含 MinIO） | `docker/start-middleware.ps1` |
| 追加 MinIO（镜像可用后） | `docker/start-middleware.ps1 -WithMinio` |
| 只起基础层（mysql/redis/nacos） | `docker/start-middleware.ps1 -CoreOnly` |
| 追加某批组件 | `docker compose -f docker/middleware.yaml up -d <服务名...>`（见 limits §3） |
| 停止（保留数据） | `docker/stop-middleware.ps1` |
| 停止并清空数据卷【不可逆】 | `docker/stop-middleware.ps1 -Purge` |

服务名：`mysql redis nacos rocketmq-namesrv rocketmq-broker seata-server xxl-job-admin postgres`；`minio` 为 profile 门控服务（`--profile minio`）。

## 4. 端口表（默认值，全部绑定 127.0.0.1）

| 组件 | 容器端口 | 宿主端口 | 用途 / 入口 |
| --- | --- | --- | --- |
| MySQL | 3306 | **13306** | 业务库 + 分片库；本机 3306 被本地 mysqld 占用，故容器映射 13306（基座官方 compose 同款建议值）；Phase 2 服务连接串经 `application-local.yaml` 覆盖端口；`root` 或 `yudao`（密码在 `docker/.env`） |
| Redis | 6379 | **16379** | 缓存/分布式锁；开启 requirepass + AOF；本机 6379 被本地 redis-server 占用，故容器映射 16379（基座官方 compose 同款建议值），Phase 2 服务侧经 `application-local.yaml` 指向 16379 |
| Nacos | 8848 / 9848 / 8080 | 同左 | 注册+配置中心；控制台 http://127.0.0.1:8080 （v3 独立端口，本地未开鉴权） |
| RocketMQ NameServer | 9876 | 9876 | 与基座 `application-dev.yaml` 默认一致 |
| RocketMQ Broker | 10911 | 10911 | 本地单机；`brokerIP1=127.0.0.1` 供宿主机客户端直连 |
| Seata Server | 8091 / 7091 | 同左 | TC 服务 / 控制台（默认账号 seata/seata，官方出厂值，仅本机） |
| XXL-Job-Admin | 8080 | **9090** | 控制台 http://127.0.0.1:9090/xxl-job-admin （与基座 dev 配置对齐；默认 admin/123456，**接入业务前请改密**） |
| MinIO | 9000 / 9001 | 同左 | S3 API / 控制台（账号在 `docker/.env`）；⚠ **profile 门控，默认不启动**（镜像阻塞见 §6） |
| PostgreSQL | 5432 | 5432 | W7 向量库（pgvector），库 `buildcloud_ai`（可改） |

## 5. 健康验证（各组件原生探针，均已实测）

容器级健康检查已内置（`docker compose ps` 看 STATUS=healthy）。手动验证可用（以下命令在 2026-11-25 实测通过）：

```powershell
docker exec bc-mysql sh -c 'mysqladmin ping -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'   # mysqld is alive
docker exec bc-redis redis-cli ping                              # PONG（密码经 REDISCLI_AUTH 注入）
docker exec bc-nacos curl -s -o NUL -w '%{http_code}' http://127.0.0.1:8848/nacos/v3/admin/core/state   # 200
docker exec bc-rmq-namesrv sh mqadmin clusterList -n 127.0.0.1:9876   # 看到 broker-a @ 127.0.0.1:10911 即注册成功
docker exec bc-postgres pg_isready -U buildcloud                 # accepting connections
# 控制台类：Nacos http://127.0.0.1:8080 / MinIO(启用后) http://127.0.0.1:9001 /
#           XXL-Job http://127.0.0.1:9090/xxl-job-admin / Seata http://127.0.0.1:7091
```

## 6. 常见问题

| 现象 | 处理 |
| --- | --- |
| `docker compose config` 报变量缺失 | 未创建/未填 `docker/.env`（见 §2 ①②） |
| 端口被占用（如本机已装 MySQL/Redis） | 改 `docker/.env` 对应 `*_HOST_PORT` 后 `docker compose up -d` 重跑；基座服务侧同步改（Phase 2 用 `application-local.yaml` 覆盖）。本机实测：3306 被本地 `mysqld`、6379 被本地 `redis-server`（均为 Windows 服务）占用，MySQL/Redis 已分别默认映射 **13306/16379**；报告「forbidden by its access permissions」时优先查 `netsh interface ipv4 show excludedportrange protocol=tcp` 与 `Get-NetTCPConnection -LocalPort <端口>` |
| Docker Desktop 起不来 / `docker info` 报错 | 重启 Docker Desktop；仍失败再 `wsl --shutdown` 后重启（会影响其它 WSL 实例，先确认无重要进程） |
| 容器反复重启 / OOM | `docker logs <容器>` 排查；对照 middleware-limits.md 检查是否自行调小过限额；必要时分批启动 |
| MinIO 拉取失败 | **2026-11-25 实测阻塞**：MinIO 已停止官方镜像匿名分发——Docker Hub `minio/minio` 仓库 404；`quay.io/minio/minio` 匿名 401（`latest`/`RELEASE.2025-09-06T17-38-46Z`/`RELEASE.2025-09-07T16-13-09Z` 实测均拒）；`ghcr.io` 无公开镜像。技术选型仍按 spec §3.3 保留 MinIO，服务以 profile `minio` 门控。待主控裁决：① 官方/镜像源恢复后启用；② 提供注册表凭据或私有构建镜像；③ 经 ADR 决议替换方案。**本 Phase 未擅自变更存储组件（R-4）。** |
| Nacos 启动报 `env NACOS_AUTH_TOKEN must be set with Base64 String.` | Nacos v3 入口脚本强制要求三个鉴权占位变量（即使 `NACOS_AUTH_ENABLE=false`）：在 `docker/.env` 填 `NACOS_AUTH_TOKEN`（32 字节以上字符串的 Base64）/`NACOS_AUTH_IDENTITY_KEY`/`NACOS_AUTH_IDENTITY_VALUE`（模板已给占位示例，2026-11-25 实测通过） |
| RocketMQ Broker 反复重启，日志只有 `ScheduleMessageService ... NullPointerException` | 原因：apache/rocketmq 镜像未预建 `/home/rocketmq/logs`、`/home/rocketmq/store`，挂载命名卷后属主 root，默认用户 rocketmq(uid 3000) 无写权限 → `initialize()` 失败。已通过 `docker/rocketmq/Dockerfile` 预建目录并授权解决；若从旧版编排迁移，先 `docker compose down` 再删除空的 `buildcloud_broker-logs/buildcloud_broker-store/buildcloud_namesrv-logs` 卷后重建 |
| Broker 连不上（宿主机客户端） | 确认 `docker/rocketmq/conf/broker.conf` 的 `brokerIP1=127.0.0.1` 未被改动；`docker logs bc-rmq-broker` 看 namesrvAddr 是否解析 |
| XXL-Job 登录不进 | 首次账号为官方种子 admin/123456（仅本地）；改密入口：控制台 → 用户管理 |
| `docker compose config` 报「invalid character」/变量读不到 | `docker/.env` 必须是 UTF-8（无 BOM）。Windows PowerShell 5.1 的 `Set-Content` 默认 ANSI 编码，生成 .env 请用 `-Encoding UTF8` 或 `[IO.File]::WriteAllText(path, text, UTF8Encoding($false))`（2026-11-25 实测踩坑） |
| 换了 .env 密码后 mysql root 登录被拒 | MySQL 账号密码仅在**数据卷首次初始化**时生效；改 .env 后需重置卷重建：`docker compose -f docker/middleware.yaml down` → `docker volume rm buildcloud_mysql-data` → `docker/start-middleware.ps1`（卷内仅中间件初始化数据，无业务数据；2026-11-25 实测） |
| 想彻底重来 | `docker/stop-middleware.ps1 -Purge`（删除全部数据卷，不可逆），再从 §2 重来 |

## 7. 版本依据（与基座对齐，升级需同步评估）

| 组件 | 镜像 tag | 依据 |
| --- | --- | --- |
| MySQL | `mysql:8.4` | 基座官方编排 `script/docker-compose/docker-compose.yml` 同款；业务库名 `ruoyi-vue-pro` 与 `application-dev.yaml` 一致 |
| Redis | `redis:7.4-alpine` | 基座官方编排同款（requirepass + AOF） |
| Nacos | `nacos/nacos-server:v3.0.3` | 基座 README 框架版本表 3.0.3；官方编排同款（standalone + 嵌入式存储） |
| RocketMQ | `buildcloud-rocketmq:5.3.1-local`（`FROM apache/rocketmq:5.3.1`） | 基座 README 框架版本表 5.3.1（rocketmq-spring 2.3.6 客户端）；本地 Dockerfile 仅预建 logs/store 目录并授权（见 §6），不改任何组件版本 |
| Seata | `apache/seata-server:2.5.0.jdk17` | 基座 README 框架版本表 2.5.0；jdk17 tag 与项目 JDK 17 基线一致 |
| XXL-Job | `xuxueli/xxl-job-admin:2.4.0` | 基座 README 框架版本表 2.4.0；建表 SQL 取自同名官方 tag |
| MinIO | `quay.io/minio/minio:RELEASE.2025-09-06T17-38-46Z` | spec §3.3 保留 MinIO 选型；⚠ 该镜像 2026-11-25 起无法匿名获取（Docker Hub 404、quay 401、ghcr 无），以 profile `minio` 门控，见 §6 阻塞记录 |
| PostgreSQL+pgvector | `pgvector/pgvector:0.8.7-pg17` | spec §3.3（Spring AI + pgvector）；官方 pgvector 镜像 0.8.7 / PG17 组合 |

> 依据核对时间：2026-11-25（Docker Hub / quay tag 列表 + 基座参考克隆 `D:\project\yudao-cloud-ref` 只读核对）。
