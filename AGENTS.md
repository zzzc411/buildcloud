# AGENTS.md — 人与智能体共同开发约束

> 本文件对**所有协作者一视同仁**：无论是人类开发者，还是参与本项目的任何 AI 智能体（编码助手/子代理/自动化脚本）。接受任务即视为接受本约束。
> 违反「红线」条款的产出不得合入 `main`。冲突裁决顺序：本文件 < [docs/spec/buildcloud-spec-v1.2.md](docs/spec/buildcloud-spec-v1.2.md) < 当前生效的开发计划。

## 1. 项目事实卡（先读这个再动手）

| 项 | 值 |
| --- | --- |
| 项目 | 筑云 BuildCloud — 建筑工程项目管理平台（微服务） |
| 本地开发工作区 | `D:\project\buildcloud`（路径不含空格；引用其他含空格路径时仍需引号包裹） |
| 远程仓库 | https://github.com/zzzc411/buildcloud.git （公开） |
| 基线 | yudao-cloud **master-jdk17** 分支（JDK 17 + Spring Boot 3.5），W2 裁剪引入 |
| 命名 | 包名 `com.buildcloud`（仅自研模块）；模块前缀 `buildcloud-`；业务表前缀 `bc_` |
| 服务拓扑 | 4 基座（gateway/system/infra/bpm）+ 4 自研（project(含合同)/material/report/ai） |
| 主分支 | `main` 必须始终**可编译、可启动、可演示** |
| 排期 | 8 周 + 1 缓冲；W1 = 2026-11-30；每周日打 tag `w{N}` |

## 2. 单一事实来源（文档纪律）

1. **范围与排期只看 spec**（`docs/spec/buildcloud-spec-v1.2.md`）。spec 变更必须升版本号 + §14 变更记录 + 旧版归档 `docs/archive/`，禁止原地静默修改；
2. 开工一个工作包前，先从 `docs/plans/_template.md` 创建计划并在 `docs/plans/00-overview.md` 登记；**计划只能细化、不能扩权**——发现需要新增/变更范围，停下来：记 ADR（`docs/decisions/`）或回 spec 升版本，经确认后再继续；以子智能体派发执行时，遵循 `docs/plans/_dispatch.md` 的派发规则与提示词模板；
3. 新建任何文档必须登记进 `docs/00-index.md`；文档命名 kebab-case；被取代的文档移入 `docs/archive/`，不删除；
4. 完成工作包 = 验收清单全勾 + 状态更新 + 进度日志最后一行写明结果。

## 3. 红线（违反即返工）

| # | 红线 |
| --- | --- |
| R-1 | **密钥/密码/内网 IP/真实云主机信息绝不提交**。仓库只允许模板配置（`*-template.yaml`、`${env:XXX}` 占位）与 `.example` 文件；真实值走本地未跟踪文件或环境变量。提交前对 diff 做密钥模式扫描。 |
| R-2 | **不改基座包名/坐标**（`cn.iocoder.yudao.*` 原样保留）。自研代码只进 `buildcloud-module-*`，包名 `com.buildcloud.*`。基座代码原则上只读；确需修基座，先记 ADR 说明理由与影响面。 |
| R-3 | **`main` 禁止 `push --force`、禁止改写已推送历史**；工作分支 rebase 自己的未推送分支除外。 |
| R-4 | **不可逆操作先问人**：删目录/删模块、改数据库结构、改 Nacos 公共配置、引入新重量级依赖（须 ADR）、公开新文档口径（README/简历表述）。 |
| R-5 | **编译不过、相关测试不过，不提交**。每次提交前：`mvn -q compile`（或对应模块）+ 受影响单测通过。 |
| R-6 | **测试数据/性能数字只能出自 `docs/testing/` 的报告**（含环境记录），禁止拍脑袋数字进入文档与 README。 |

## 4. 工程规范（与基座对齐）

- 分层与代码风格照基座：`controller / service / dal(mysql+dataobject) / api`（RPC 接口）、统一响应 `CommonResult`、分页 `PageResult`、BaseDO 六字段（`tenant_id/creator/create_time/updater/update_time/deleted`）；
- 错误码：自研模块用 `1-10x-xxx` 独立段位（project 1-100 / contract 1-101 / material 1-102 / report 1-103 / ai 1-104，见 spec §7.2），错误码常量集中管理，禁止魔法数字；
- 单测随代码走：核心链路（Lua 预扣、幂等消费、状态机、分片路由）必须有单测，放各模块 `src/test`，风格照基座；
- SQL 变更进 `sql/buildcloud/`（按域拆文件，可重复执行），菜单/字典初始化 SQL 同步维护；
- 日志/参数校验/Swagger 注解按基座约定，不另起炉灶。

## 5. Git 工作流

- 分支：`main`（可演示）+ `feature/{plan-id或主题}`（如 `feature/w5-lua-prededuct`）；
- 提交信息：`{type}: {一句话}`，type ∈ `feat / fix / refactor / test / docs / perf / chore`；一个提交只做一件事；
- 合入 `main` 前自检：编译 ✓ 测试 ✓ diff 无敏感信息 ✓ 文档已更新（若涉及）✓；
- 每周日（里程碑口径）打 tag `w{N}`，tag message 引用该周计划验收结论。

## 6. 智能体专项约定（人类可豁免，智能体必须遵守）

1. **先计划后动手**：接到的任务若尚无对应 `docs/plans/` 计划，先建计划（哪怕是半天的小计划）再写代码；
2. **改动范围声明**：开工时在计划/进度日志写明将触碰的模块与文件集合；实际触碰超出声明范围时，先更新声明再继续；
3. **不猜接口**：涉及基座能力（权限/工作流/文件/RPC）时，先读基座对应模块源码或官方文档，复用其模式；禁止凭记忆臆造基座 API；
4. **依赖保守**：不主动新增依赖；确需新增（含版本冲突解决）记 ADR，说明为何不用现有依赖；
5. **生成代码必须自查**：编译 + 相邻测试 + 与基座风格一致性；代码生成器产物要人工精修后才算交付；
6. **长任务留痕**：跨多轮的工作，每轮结束在当前计划的「进度日志」追加一行（做了什么/卡在哪/下一步），保证任何人接手不丢上下文；
7. **环境注意**：Windows + PowerShell（工作区 `D:\project\buildcloud` 路径无空格，引用其他含空格路径仍要引号）；**构建/运行基座需显式 JDK 17**（本机 PATH 默认为 JDK 21，Temurin 17 位于 `D:\project\tools\jdk-17.0.21.1+2`，Maven 构建前设 `JAVA_HOME` 指向它）；Docker Desktop 可用但 16G 内存——起全栈前先看 `docs/ops/middleware-limits.md`（W1 产出）中的资源限额，必要时分批启动；
8. **遇到阻塞**：连试两种合理方案仍不通，写清现象与尝试记录，标记计划状态为「阻塞」并求助，不要静默绕过或降低验收标准。

## 7. 沟通与口径（对外表述）

- 对外（README/简历/演示）口径遵循 spec §1.2：**基于开源基座二次开发，通用能力来自基座，业务域/高并发/AI/部署上线自研**；不装纯自研；
- 仓库是公开的：任何写进仓库的文字都可能被面试官看到——文档写专业话，草稿和 TODO 留在计划文件里，不留在代码注释里。
