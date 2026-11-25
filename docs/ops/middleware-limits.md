# 中间件内存限额与 16G 预算（W1 Phase 1）

> 对应计划：`docs/plans/plan-w1-infra-up.md` Phase 1；编排文件：`docker/middleware.yaml`
> 环境约束（spec D17）：本机 16GB 内存 + Docker Desktop（WSL2 后端）。起全栈前先看本文；必要时分批启动（AGENTS.md §6.7）。

## 1. 每容器内存限额表

限额以 `mem_limit` 写死在 `docker/middleware.yaml`（超限由内核 OOM kill，防止拖垮整机）。容器内 JVM 参数已同步压小（见下表），保证限额约束下不会出现「堆外内存吃光限额」的情况。

| # | 容器 | 镜像（tag） | mem_limit | JVM/进程内存控制 | 宿主端口（默认） |
| --- | --- | --- | --- | --- | --- |
| 1 | bc-mysql | `mysql:8.4` | 1500m | 无 JVM（mysqld 原生） | 13306 |
| 2 | bc-redis | `redis:7.4-alpine` | 256m | 无 JVM | 16379 |
| 3 | bc-nacos | `nacos/nacos-server:v3.0.3` | 1024m | JVM_XMS=XMX=512m，XMN=256m | 8848 / 9848 / 8080 |
| 4 | bc-rmq-namesrv | `apache/rocketmq:5.3.1` | 384m | JAVA_OPT_EXT -Xms/-Xmx 256m | 9876 |
| 5 | bc-rmq-broker | `apache/rocketmq:5.3.1` | 1024m | JAVA_OPT_EXT -Xms/-Xmx 512m，DirectMemory 128m | 10911 |
| 6 | bc-seata | `apache/seata-server:2.5.0.jdk17` | 768m | 默认堆 ≤512m + file 存储 | 8091 / 7091 |
| 7 | bc-xxl-job-admin | `xuxueli/xxl-job-admin:2.4.0` | 768m | SB2 应用默认堆，限额兜底 | 9090 |
| 8 | bc-minio | `quay.io/minio/minio:RELEASE.2025-09-06T17-38-46Z` | 512m | Go 进程，无需 JVM 参数 | 9000 / 9001 |
| 9 | bc-postgres | `pgvector/pgvector:0.8.7-pg17` | 512m | 原生 | 5432 |
| — | **默认组合计（1-7、9 共 8 容器）** | | **6236m ≈ 6.1 GiB** | | |
| — | **含 MinIO 共 9 容器（镜像可用后启用）** | | **6748m ≈ 6.6 GiB** | | |

说明：

- ⚠ **MinIO（第 8 行）为 compose profile 门控服务，默认组不含**：2026-11-25 实测官方镜像已停止匿名分发（Docker Hub 404、quay 匿名 401、ghcr 无公开镜像），技术选型按 spec §3.3 保留待主控裁决（详见 `deploy-local.md` §6）。默认组 = 上表除 MinIO 外 8 容器。
- 上表为**硬上限合计**；实际稳态占用远低于上限（各容器按需增长）。实测值以 `docker stats` 为准，压测类数字一律出自 `docs/testing/`（AGENTS.md R-6），本文不记录实测性能数字。
- RocketMQ NameServer/Broker 的官方默认堆为 4G/8G，必须用 `JAVA_OPT_EXT` 压小后才能在限额内安全运行（已在 compose 中配置）。
- Nacos 采用基座官方编排同款 JVM 参数（512m 堆）；v3 单机模式够用。

## 2. 16G 总预算推导

| 预算项 | 预估占用 | 依据 |
| --- | --- | --- |
| Windows 11 + 常驻（含杀软等） | ~4 GB | 系统底噪 |
| IDE（IntelliJ IDEA）+ 浏览器 | ~4 GB | 开发必备 |
| Docker Desktop VM（WSL2；本机实测 `docker info` MemTotal ≈ 7.5 GiB，即 8 GB 配额；建议在 Docker Desktop → Settings → Resources 上调至 10 GB） | ≤10 GB | 容器 + compose 自身都在 VM 内 |
| **其中：中间件容器（本文限额合计）** | **默认组 ≤6.1 GiB；含 MinIO ≤6.6 GiB** | 上表 |
| **其中：留给 Phase 2 的 8 个 Java 服务** | **~3.5 GB** | 每个服务 `-Xmx512m` 堆 + 元空间，基座网关/系统/基础设施 4 服务 + 自研骨架按需起 |
| Docker Desktop 本体（Windows 侧进程） | ~1-2 GB | VM 之外 |

结论：中间件全量（含 MinIO）硬上限 6.6 GiB、默认组 6.1 GiB，均小于 8 GB VM 配额（本机实测），全量启动可行；但配额内余量偏紧，**建议在 Phase 2 服务并跑前把 VM 内存上调至 10 GB**（Settings → Resources）。同机跑 IDE + 浏览器 + 8 个 Java 服务时按 §3 分批。

## 5. 实测记录（环境观测，2026-11-25，`docker stats --no-stream`）

默认组 8 容器全部 healthy 后的稳态占用（非压测数字，仅资源预算核对；压测数字一律出自 `docs/testing/`）：

| 容器 | 实测占用 / 限额 | 占比 | 备注 |
| --- | --- | --- | --- |
| bc-nacos | ~760 MiB / 1024m | ~74% | 初版限额 768m 时占比 99%（v3 堆外内存大），已上调 |
| bc-rmq-broker | ~762 MiB / 1024m | ~74% | 堆 512m + 堆外 128m + 元空间 |
| bc-seata | ~545 MiB / 768m | ~71% | |
| bc-xxl-job-admin | ~535 MiB / 768m | ~70% | SB2 应用，镜像自带 JVM 参数 |
| bc-mysql | ~442 MiB / 1500m | ~29% | 空库阶段 |
| bc-rmq-namesrv | ~187 MiB / 384m | ~49% | |
| bc-postgres | ~24 MiB / 512m | ~5% | 空库阶段 |
| bc-redis | ~8 MiB / 256m | ~3% | |
| **合计** | **~3.26 GiB 实际 / 6.1 GiB 上限** | | 实际占用远低于硬上限，预算成立 |

## 3. 分批启动建议

| 批次 | 内容 | 命令 | 适用场景 |
| --- | --- | --- | --- |
| 0 | 全量（默认组 8 容器；MinIO 镜像可用后用 `-WithMinio` 追加） | `docker/start-middleware.ps1` | 机器空闲、需要全部组件联调 |
| 1 | 基础层：mysql / redis / nacos | `docker/start-middleware.ps1 -CoreOnly` | 只做服务开发调试（Phase 2 主力场景，约 2.5 GiB 上限） |
| 2 | +MQ 层：rocketmq-namesrv / rocketmq-broker | `docker compose -f docker/middleware.yaml up -d rocketmq-namesrv rocketmq-broker` | 联调事务消息/延迟消息 |
| 3 | +辅助层：seata-server / xxl-job-admin / postgres | `docker compose -f docker/middleware.yaml up -d seata-server xxl-job-admin postgres` | 联调分布式事务、定时任务、AI 向量库 |

依赖关系已由 compose `depends_on` 保证（broker 等 namesrv healthy；xxl-job 等 mysql healthy），按批次追加启动不会因顺序报错。停止一律 `docker/stop-middleware.ps1`（保留数据卷）。

## 4. 调整指引

- **限额调整**：只改 `docker/middleware.yaml` 的 `mem_limit` 与对应 JVM 参数，并在本文表格同步登记；
- **端口调整**：改 `docker/.env` 的 `*_HOST_PORT`（不入库）；
- **镜像版本**：中间件版本必须与基座对齐（依据见 compose 头部注释与 `docs/ops/deploy-local.md` §版本依据），升级基座 BOM 时同步评估。
