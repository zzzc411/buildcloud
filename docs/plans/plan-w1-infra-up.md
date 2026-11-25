# 开发计划：plan-w1-infra-up（环境与基座跑通）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w1-infra-up |
| 对应 spec 章节 | §9 W1、§3.3（技术栈基线）、§3.2（服务拆分） |
| 状态 | 进行中 |
| 计划开始 | 2026-11-30（周一，spec Q7） |
| 计划完成 | 2026-12-06（周末，打 tag `w1`） |
| 前置依赖 | plan-w0-repo-bootstrap（✅ 已完成） |
| 分支约定 | `feature/w1-infra-up` |

## 1. 目标（一句话）

一键 docker-compose 拉起全部中间件（限额），基座四服务 + 管理端本地可登录，并产出读码地图（网关鉴权 / RPC / BPM 集成 / starter 体系四条链路笔记 + 架构图 v0）。

## 2. Phase 清单（每个 Phase = 一次子智能体派发单元）

### Phase 1：中间件 docker-compose 编排

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 编写 `docker/middleware.yaml`：Nacos（注册+配置）、MySQL 8（含后续 4 schema 预留）、Redis、RocketMQ（NameServer+Broker）、Seata-Server、XXL-Job-Admin、MinIO、PostgreSQL+pgvector；每个容器设内存限额（16G 总量内做预算表）；健康检查与启动顺序；一键起停脚本（PowerShell） |
| 交付物 | `docker/middleware.yaml` + `docker/.env.template`（占位值）+ `docs/ops/middleware-limits.md`（限额表）+ `docs/ops/deploy-local.md` v1 |
| 验收标准 | [x] `docker compose -f docker/middleware.yaml up -d` 一键全绿（healthy，8/8 实测 2026-11-25）；[x] 总内存占用符合限额表（实测约 3.1GB）；[x] 环境配置零真实密码（全部 `${env}` 占位，走 `.env`（不入库））；[x] 附加：建库/账号/探针全验证（ruoyi-vue-pro + pm_log_0..3 + xxl_job；Redis/Nacos/RocketMQ/PG） |
| 上下文包（必读） | spec §3.3、§4.3.8；yudao-cloud 官方 quick-start（cloud.iocoder.cn/quick-start/）与仓库内 docker 目录参考 |
| 状态 | ✅ |

### Phase 2：基座四服务 + 管理端跑通

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 本地起 yudao-gateway / system / infra / bpm（基座原样，**尚未裁剪**，从 yudao-cloud master-jdk17 源码目录起服务）；导入官方 SQL 到 MySQL；前端 yudao-ui-admin-vue3 裁剪安装并登录 |
| 交付物 | 可登录的管理端（本地）+ `docs/ops/deploy-local.md` 补充「服务启动顺序与分批策略」 |
| 验收标准 | [ ] 管理端登录成功；[ ] 四服务注册进 Nacos；[ ] 分批启动策略可行（不起无关服务也能用 system 基础功能）；[ ] 端口规划表定稿（写入 deploy-local.md，新增服务占位一段连续端口） |
| 上下文包（必读） | spec §3.2/§3.3；`docs/ops/middleware-limits.md`（Phase 1 产出）；基座 README 分支说明（master-jdk17） |
| 状态 | ☐ |

### Phase 3：读码地图（四条链路笔记 + 架构图 v0）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 精读并成文：① 网关鉴权链路（token 校验→路由→下游传递用户上下文）；② 服务间 RPC 方式（基座默认，定稿写进 ADR）；③ BPM 集成模式（OA 请假示例的发起/回调/驳回/撤回全流程，供 W3-W4 复刻）；④ starter 体系（哪些框架 starter 被 system/infra/bpm 引用，自研模块要引哪些）；产出架构图 v0（Mermaid） |
| 交付物 | `docs/architecture/reading-notes-{gateway,rpc,bpm-integration,starter}.md` + `docs/architecture/arch-overview-v0.md`；RPC 方式结论记 ADR-001 |
| 验收标准 | [x] 每篇笔记含「一条请求的完整链路」追踪；[x] BPM 笔记列出 W4 复刻所需的类/表/配置清单；[x] 能口头讲清（自我检验，笔记可走查）；[x] 文档已登记 `00-index.md`（本次提交同步登记） |
| 上下文包（必读） | spec §3.1/§5.3；基座 yudao-gateway / yudao-module-bpm 源码（只读）；官方文档 cloud.iocoder.cn 与 doc.iocoder.cn/bpm/ |
| 状态 | ✅ |

## 3. 计划级验收标准（出口条件，来自 spec §9 W1）

- [x] 一键起环境（Phase 1 完成：8 容器实起全绿；**MinIO 官方镜像停止匿名分发，暂以 profile 门控，待裁决**）；
- [ ] 管理端登录成功（Phase 2 待做）；
- [x] 能讲清一条请求的完整链路（Phase 3 四篇笔记成文，含真实类名/路径）；
- [x] 交付物齐：中间件编排 + 部署文档 v1 + 架构图 v0 + 读码笔记（P1/P3 完成；P2 补服务启动策略）；
- [ ] 进度日志收尾 + `00-overview.md` 状态同步 + tag `w1`（待 Phase 2 完成后打 tag）。

## 4. 涉及范围（改动声明）

- 代码模块：无 Java 代码（基座原样运行，**不引入仓库**——基座代码 W2 才进来）；
- 数据库/SQL：本地导入官方 SQL（不产生仓库提交）；
- 配置/Nacos：本地实例初始化（零密钥提交）；
- 文档：`docs/ops/`×2、`docs/architecture/`×5、ADR-001、本计划。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| master-jdk17 分支与文档（SB2.7 线）不一致 | R2 对策：对照基座自带单测与 Spring 官方迁移指南；卡壳超半天记进度日志求助 |
| 16G 内存起 9 容器吃紧 | 限额表 + 分批启动（SkyWaling 等 W6 再启） |
| Docker Desktop 异常 | 快速回退：单容器逐个排查；仍不通标记阻塞 |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W1 拆分，W1 未开工）。
- 2026-11-25：Phase1（worker w1p1-infra）— 中间件 docker-compose 编排交付（8 容器 + 初始化脚本 + 起停脚本 + docs/ops 两篇）；实测 8/8 healthy、建库/账号/Redis/Nacos/RocketMQ/PG 探针全过、内存约 3.1GB 在限额内；遗留：① MinIO 官方镜像停止匿名分发（Docker Hub 404/quay 401），按 R-4 以 profile 门控待裁决；② 首启数据卷旧密码与 .env 不一致已重置 mysql 卷（无业务数据）；③ .env 本地 UTF-8 不入库。
- 2026-11-25：Phase3（worker w1p3-notes）— 四篇读码笔记 + 架构图 v0 + ADR-001（RPC=OpenFeign 定稿）交付；发现 spec §3.1 两处与基座现状差异（网关未内置 Sentinel、审批回调走 Spring 事件+HTTP 而非 MQ），已在笔记/架构图标注，待裁决是否升 spec。
- 2026-11-25（lead）：一阶段验收通过（范围/密钥扫描 0 命中），已提交并推送；Phase 2（基座四服务 + 管理端登录）待用户验收后开工。
