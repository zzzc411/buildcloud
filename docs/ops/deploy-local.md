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
# ④ 一键启动（默认组 9 容器，含 MinIO；或分批：docker/start-middleware.ps1 -CoreOnly）
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
| 全量启动（默认组 9 容器，含 MinIO） | `docker/start-middleware.ps1` |
| 追加某批组件 | `docker compose -f docker/middleware.yaml up -d <服务名...>`（见 limits §3） |
| 停止（保留数据） | `docker/stop-middleware.ps1` |
| 停止并清空数据卷【不可逆】 | `docker/stop-middleware.ps1 -Purge` |

服务名：`mysql redis nacos rocketmq-namesrv rocketmq-broker seata-server xxl-job-admin postgres minio`。

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
| MinIO | 9000 / 9001 | 同左 | S3 API / 控制台（账号在 `docker/.env`）；**默认随组启动**（镜像来源见 §6） |
| PostgreSQL | 5432 | 5432 | W7 向量库（pgvector），库 `buildcloud_ai`（可改） |

## 5. 健康验证（各组件原生探针，均已实测）

容器级健康检查已内置（`docker compose ps` 看 STATUS=healthy）。手动验证可用（以下命令在 2026-11-25 实测通过）：

```powershell
docker exec bc-mysql sh -c 'mysqladmin ping -h127.0.0.1 -uroot -p"$MYSQL_ROOT_PASSWORD" --silent'   # mysqld is alive
docker exec bc-redis redis-cli ping                              # PONG（密码经 REDISCLI_AUTH 注入）
docker exec bc-nacos curl -s -o NUL -w '%{http_code}' http://127.0.0.1:8848/nacos/v3/admin/core/state   # 200
docker exec bc-rmq-namesrv sh mqadmin clusterList -n 127.0.0.1:9876   # 看到 broker-a @ 127.0.0.1:10911 即注册成功
docker exec bc-postgres pg_isready -U buildcloud                 # accepting connections
docker exec bc-minio curl -fsS http://127.0.0.1:9000/minio/health/live   # 退出码 0 即正常（2026-11-25 实测）
# 控制台类：Nacos http://127.0.0.1:8080 / MinIO(启用后) http://127.0.0.1:9001 /
#           XXL-Job http://127.0.0.1:9090/xxl-job-admin / Seata http://127.0.0.1:7091
```

## 6. 基座服务启动（Phase 2，2026-11-25 实测）

> 前置：中间件 **mysql/redis/nacos/rocketmq-namesrv+broker** 必须运行（`yudao-spring-boot-starter-mq` 启动期即创建 Producer，缺 namesrv 直接失败）；xxl-job/seata/postgres/minio 在 local profile 下不需要，可停掉省约 1.5GB。

### 6.1 JDK / Maven

- JDK **17**（master-jdk17 基线）：本机 PATH 默认为 JDK 21，17 安装在 `D:\project\tools\jdk-17.0.21.1+2`，构建/启动前显式设置 `JAVA_HOME`；
- Maven 3.9.1：`mvn -v` 可查。

### 6.2 数据库准备（bc-mysql:13306，库 `ruoyi-vue-pro`）

| 脚本 | 来源 | 说明 |
| --- | --- | --- |
| `ruoyi-vue-pro.sql` | 基座仓库 `sql/mysql/`（参考克隆 `D:\project\yudao-cloud-ref\sql\mysql\`） | 48 张 system_/infra_ 表 + 初始数据 |
| `bpm.sql` | 芋道星球附件 `bpm-2025-03-15`（**仅本地使用；公开仓库嵌入前须评估授权**） | 8 张 bpm_ 表；其中 2 表带数据（definition_info 250 条、expression 2 条），category/form/user_group 为空 |
| `bpm-alter`（见下） | 由当前 DO 与 2025-03 版差异推出 | bpm 启动前必须补 7 列 |

导入（容器内 mysql 客户端 + UTF-8）：

```powershell
docker cp <sql文件> bc-mysql:/tmp/x.sql
docker exec bc-mysql sh -c 'mysql -uroot -p<PWD> --default-character-set=utf8mb4 ruoyi-vue-pro < /tmp/x.sql'
```

补齐列（以当前 DO 为准，`allow_withdraw_task` 默认 b'0' 与 `BpmTaskServiceImpl` 的 `Boolean.TRUE` 判定一致）：

```sql
ALTER TABLE `bpm_process_definition_info`
  ADD COLUMN `start_dept_ids` varchar(500) NULL DEFAULT NULL COMMENT '可发起部门编号数组' AFTER `start_user_ids`,
  ADD COLUMN `allow_withdraw_task` bit(1) NOT NULL DEFAULT b'0' COMMENT '是否允许审批人撤回任务' AFTER `allow_cancel_running_process`,
  ADD COLUMN `process_before_trigger_setting` text NULL DEFAULT NULL COMMENT '流程前置触发配置' AFTER `summary_setting`,
  ADD COLUMN `process_after_trigger_setting` text NULL DEFAULT NULL COMMENT '流程后置触发配置' AFTER `process_before_trigger_setting`,
  ADD COLUMN `task_before_trigger_setting` text NULL DEFAULT NULL COMMENT '任务前置触发配置' AFTER `process_after_trigger_setting`,
  ADD COLUMN `task_after_trigger_setting` text NULL DEFAULT NULL COMMENT '任务后置触发配置' AFTER `task_before_trigger_setting`,
  ADD COLUMN `print_template_setting` text NULL DEFAULT NULL COMMENT '打印模板配置' AFTER `task_after_trigger_setting`;
```

### 6.3 本地配置（仅参考克隆，W2 裁剪时按模板化落地）

4 份 `application-local.yaml`（gateway/system/infra/bpm）：MySQL → `jdbc:mysql://127.0.0.1:13306/ruoyi-vue-pro`、root + `docker/.env` 密码；Redis → `127.0.0.1:16379` + 密码；Nacos `namespace: public`（dev 不可用，见 ADR-002）；`spring.profiles.active: local`。

### 6.4 构建与启动

```powershell
# 构建（首轮含依赖下载，实测 6m50s）
$env:JAVA_HOME='D:\project\tools\jdk-17.0.21.1+2'
mvn -DskipTests install -pl yudao-gateway,yudao-module-system/yudao-module-system-server,yudao-module-infra/yudao-module-infra-server,yudao-module-bpm/yudao-module-bpm-server -am
# 启动顺序（每个服务等端口就绪）：system → infra → bpm → gateway
java -Xms128m -Xmx768m -XX:MaxMetaspaceSize=360m -XX:+UseSerialGC -jar <模块>\target\<模块>.jar --spring.profiles.active=local
```

- 端口：gateway 48080 / system 48081 / infra 48082 / bpm 48083；**自研 4 服务预留 48084-48087**（W2 接入网关路由）；
- 内存实测：4 JVM 合计 ~1.73GB（-Xmx 768/640/768/512 + SerialGC）+ 容器 ~2.4GB；16G 机器剩余约 1.25GB，起 vite dev 前先停无用容器；
- bpm 首启由 Flowable `database-schema-update` 自动建 45 张 ACT_/FLW_ 表（约 60s），勿误判卡死。

### 6.5 验证

```powershell
curl.exe -s http://127.0.0.1:48080/                                   # {"code":404,...}
curl.exe -s -H "tenant-id: 1" http://127.0.0.1:48081/admin-api/system/ # {"code":401,...}
# 登录：PowerShell 直传 JSON 会被引号吞噬，body 写文件再 --data-binary 提交
curl.exe -s -X POST "http://127.0.0.1:48080/admin-api/system/auth/login" -H "tenant-id: 1" -H "Content-Type: application/json" --data-binary "@login-body.json"
# 期望 code=0 + accessToken；再用 Bearer token 调 /admin-api/system/auth/get-permission-info（menus=28）
```

### 6.6 前端（W1 验收用预编译版）

`yudao-demo`（gitee.com/yudaocode/yudao-demo）预编译管理端：`java -cp "target\classes;<cp>" cn.iocoder.ui.ElementPlusApplication` → http://127.0.0.1:2048/login（admin/admin123）。仅用于 W1 验证；W2 换 `yudao-ui-admin-vue3` 源码裁剪。

## 7. 常见问题

| 现象 | 处理 |
| --- | --- |
| `docker compose config` 报变量缺失 | 未创建/未填 `docker/.env`（见 §2 ①②） |
| 端口被占用（如本机已装 MySQL/Redis） | 改 `docker/.env` 对应 `*_HOST_PORT` 后 `docker compose up -d` 重跑；基座服务侧同步改（Phase 2 用 `application-local.yaml` 覆盖）。本机实测：3306 被本地 `mysqld`、6379 被本地 `redis-server`（均为 Windows 服务）占用，MySQL/Redis 已分别默认映射 **13306/16379**；报告「forbidden by its access permissions」时优先查 `netsh interface ipv4 show excludedportrange protocol=tcp` 与 `Get-NetTCPConnection -LocalPort <端口>` |
| Docker Desktop 起不来 / `docker info` 报错 | 重启 Docker Desktop；仍失败再 `wsl --shutdown` 后重启（会影响其它 WSL 实例，先确认无重要进程） |
| 容器反复重启 / OOM | `docker logs <容器>` 排查；对照 middleware-limits.md 检查是否自行调小过限额；必要时分批启动 |
| MinIO 镜像来源（官方分发已停） | 2026-11-25 实测：MinIO 官方已停止镜像与二进制匿名分发——Docker Hub `minio/minio` 404、quay.io 401、`dl.min.io` 410。改用 **`bitnamilegacy/minio:latest`**（Bitnami 归档镜像，匿名可拉取；内置 MinIO DEVELOPMENT.2025-05-24、S3 健康 200 实测通过），已默认纳入启动组（容器以 `user: "0:0"` 运行——数据卷由 Docker 创建为 root:root，镜像默认用户 1001 无写权限）。**升级路径（须记 ADR）**：① 使用登记过的镜像仓库/私有构建；② 经 ADR 替换存储组件。镜像停留在 2025-05 归档版本，安全补丁需关注。 |
| Nacos 启动报 `env NACOS_AUTH_TOKEN must be set with Base64 String.` | Nacos v3 入口脚本强制要求三个鉴权占位变量（即使 `NACOS_AUTH_ENABLE=false`）：在 `docker/.env` 填 `NACOS_AUTH_TOKEN`（32 字节以上字符串的 Base64）/`NACOS_AUTH_IDENTITY_KEY`/`NACOS_AUTH_IDENTITY_VALUE`（模板已给占位示例，2026-11-25 实测通过） |
| RocketMQ Broker 反复重启，日志只有 `ScheduleMessageService ... NullPointerException` | 原因：apache/rocketmq 镜像未预建 `/home/rocketmq/logs`、`/home/rocketmq/store`，挂载命名卷后属主 root，默认用户 rocketmq(uid 3000) 无写权限 → `initialize()` 失败。已通过 `docker/rocketmq/Dockerfile` 预建目录并授权解决；若从旧版编排迁移，先 `docker compose down` 再删除空的 `buildcloud_broker-logs/buildcloud_broker-store/buildcloud_namesrv-logs` 卷后重建 |
| Broker 连不上（宿主机客户端） | 确认 `docker/rocketmq/conf/broker.conf` 的 `brokerIP1=127.0.0.1` 未被改动；`docker logs bc-rmq-broker` 看 namesrvAddr 是否解析 |
| XXL-Job 登录不进 | 首次账号为官方种子 admin/123456（仅本地）；改密入口：控制台 → 用户管理 |
| `docker compose config` 报「invalid character」/变量读不到 | `docker/.env` 必须是 UTF-8（无 BOM）。Windows PowerShell 5.1 的 `Set-Content` 默认 ANSI 编码，生成 .env 请用 `-Encoding UTF8` 或 `[IO.File]::WriteAllText(path, text, UTF8Encoding($false))`（2026-11-25 实测踩坑） |
| 换了 .env 密码后 mysql root 登录被拒 | MySQL 账号密码仅在**数据卷首次初始化**时生效；改 .env 后需重置卷重建：`docker compose -f docker/middleware.yaml down` → `docker volume rm buildcloud_mysql-data` → `docker/start-middleware.ps1`（卷内仅中间件初始化数据，无业务数据；2026-11-25 实测） |
| 想彻底重来 | `docker/stop-middleware.ps1 -Purge`（删除全部数据卷，不可逆），再从 §2 重来 |

## 8. 版本依据（与基座对齐，升级需同步评估）

| 组件 | 镜像 tag | 依据 |
| --- | --- | --- |
| MySQL | `mysql:8.4` | 基座官方编排 `script/docker-compose/docker-compose.yml` 同款；业务库名 `ruoyi-vue-pro` 与 `application-dev.yaml` 一致 |
| Redis | `redis:7.4-alpine` | 基座官方编排同款（requirepass + AOF） |
| Nacos | `nacos/nacos-server:v3.0.3` | 基座 README 框架版本表 3.0.3；官方编排同款（standalone + 嵌入式存储） |
| RocketMQ | `buildcloud-rocketmq:5.3.1-local`（`FROM apache/rocketmq:5.3.1`） | 基座 README 框架版本表 5.3.1（rocketmq-spring 2.3.6 客户端）；本地 Dockerfile 仅预建 logs/store 目录并授权（见 §6），不改任何组件版本 |
| Seata | `apache/seata-server:2.5.0.jdk17` | 基座 README 框架版本表 2.5.0；jdk17 tag 与项目 JDK 17 基线一致 |
| XXL-Job | `xuxueli/xxl-job-admin:2.4.0` | 基座 README 框架版本表 2.4.0；建表 SQL 取自同名官方 tag |
| MinIO | `bitnamilegacy/minio:latest`（内置 MinIO DEVELOPMENT.2025-05-24） | spec §3.3 保留 MinIO 选型；官方分发停止（2026-10 起 Docker Hub 下架），改用 Bitnami 归档镜像，2026-11-25 实测 S3 健康 200；升级路径见 §6 |
| PostgreSQL+pgvector | `pgvector/pgvector:0.8.7-pg17` | spec §3.3（Spring AI + pgvector）；官方 pgvector 镜像 0.8.7 / PG17 组合 |

> 依据核对时间：2026-11-25（Docker Hub / quay tag 列表 + 基座参考克隆 `D:\project\yudao-cloud-ref` 只读核对）。
