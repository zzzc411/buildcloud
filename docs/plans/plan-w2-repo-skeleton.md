# 开发计划：plan-w2-repo-skeleton（基座裁剪 + 自研模块骨架）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w2-repo-skeleton |
| 对应 spec 章节 | §4（裁剪方案全部）、§9 W2、D16/D17 |
| 状态 | 未开始 |
| 计划开始 | 2026-12-07（周一） |
| 计划完成 | 2026-12-13（周末，打 tag `w2`） |
| 前置依赖 | plan-w1-infra-up（✅ 环境、登录、读码地图就位）；plan-sp-key-management（∥ 并行推进，P2 裁剪时必须落地其规范） |
| 分支约定 | `feature/w2-skeleton`（引入基座后主干重，单独分支保护 main） |

## 1. 目标（一句话）

公开仓库落地裁剪后的基座（删 15 模块零残留），4 个 `buildcloud-module-*` 空模块可注册 Nacos、经网关路由返回健康检查，代码生成器跑通「Java+Vue+SQL+单测」四件套，任何人克隆即可按 README 复现启动。

## 2. Phase 清单（每个 Phase = 一次子智能体派发单元）

### Phase 1：基座源码入仓库（裁剪起点）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | clone yudao-cloud master-jdk17 → 拷入本仓库根（保留 LICENSE 与来源注明）；`yudao-module-ai` 保留源码但从构建摘除（D12，设计参考）；提交历史从本次拷入开始（全新历史，D1） |
| 交付物 | 仓库含完整基座源码（未删模块前）+ 根 README「基座来源」注明；`mvn clean package -DskipTests` 全量编译通过（基座原样） |
| 验收标准 | [ ] 编译通过；[ ] LICENSE 与来源注明就位；[ ] 提交不含任何密钥（基座默认配置先行模板化标记，细做在 Phase 2） |
| 上下文包（必读） | spec §4.1/§4.2/§11 D12-D14；基座根 pom 的 modules 列表 |
| 状态 | ☐ |

### Phase 2：删 15 模块及连带引用（含密钥模板化）

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 删 member/pay/report/mp/mall/erp/wms/crm/iot/mes/im/hrm/fms/pms/oa 十五个模块；连带：根 pom modules、Nacos 配置清理、`sql/` 只留 system/infra/bpm、网关路由删除无效项、system 菜单初始化 SQL 清死链；**同步执行 plan-sp-key-management 的模板化规范**（所有 application yaml 密钥位改 `${env}`） |
| 交付物 | 裁剪后可编译仓库 + `sql/` 清单 + 网关路由表（docs/architecture 记录） |
| 验收标准 | [ ] `mvn clean package -DskipTests` 通过；[ ] 全局搜索无被删模块编译期引用、菜单无死链；[ ] 密钥 grep 扫描 0 命中（对照 sp-key-management 清单） |
| 上下文包（必读） | spec §4.2/§4.3（8 条连带改动点全做）；`docs/plans/plan-sp-key-management.md`；Phase 1 产出 |
| 状态 | ☐ |

### Phase 3：建 4 个 buildcloud 模块骨架

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 建 `buildcloud-module-project / -material / -report / -ai`：groupId `com.buildcloud`（D16）；结构照基座模块（framework starter 引用按读码笔记 ④）；project 模块内预建 `com.buildcloud.project.*` / `com.buildcloud.contract.*` 分包（D17）；注册 Nacos + 网关路由 + 一个健康检查/空控制器接口；预留端口接入 Phase 规划表 |
| 交付物 | 4 模块可启动；网关 `/admin-api/buildcloud-{x}/health` 可路由 |
| 验收标准 | [ ] 4 模块注册 Nacos 可见；[ ] 网关路由返回健康检查；[ ] 包结构与 starter 引用符合基座规范（对照读码笔记） |
| 上下文包（必读） | spec §4.2（新增 4 模块行）；读码笔记 starter 篇（W1 产出）；基座任一模块做模板参考（只读） |
| 状态 | ☐ |

### Phase 4：代码生成器四件套 + 复现启动验收

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 用基座 infra 代码生成器对一张业务表（建议 `bc_project`）跑通 Java+Vue+SQL+单测四件套并精修入库；README「快速开始」从占位变实操（克隆→模板配置→compose→起服务→登录）；菜单树初始化 SQL（五大菜单组骨架） |
| 交付物 | 生成器四件套示例 + README 快速开始实操版 + `sql/buildcloud/` 目录规范落地 |
| 验收标准 | [ ] 生成器四件套完整产出且精修后编译/单测通过；[ ] **全新克隆目录按 README 可复现启动**（§4.4 硬条款）；[ ] 密钥扫描 0 命中复验 |
| 上下文包（必读） | spec §4.4（验收清单全文）；基座代码生成器文档；Phase 2/3 产出 |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §4.4，逐条）

- [ ] `mvn clean package -DskipTests` 全量编译通过；
- [ ] 一键 docker-compose 起中间件 + 依次起服务，管理端可登录；
- [ ] 被删模块无任何残留引用（编译期依赖 + 菜单死链双查）；
- [ ] 新增空模块注册 Nacos、经网关路由返回健康检查；
- [ ] 代码生成器四件套跑通；
- [ ] 公开克隆按 README 复现启动；
- [ ] 密钥泄漏扫描 0 命中；
- [ ] tag `w2` + `00-overview.md` 状态同步。

## 4. 涉及范围（改动声明）

- 代码模块：仓库引入基座全量 → 删 15 模块 → 增 4 空模块（首次大规模仓库变更）；
- 数据库/SQL：`sql/` 清理 + 菜单树初始化 + `sql/buildcloud/` 目录；
- 配置/Nacos：全部 application yaml 密钥模板化（联动 sp-key-management）；
- 文档：README 重写（实操版）、架构文档路由表、本计划。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 裁剪连带引用漏删导致编译失败 | R1 对策：分批删（先 pom 后目录），每批编译验证；卡壳切 mini 路线（spec §4.1 备选，须 ADR） |
| 基座配置含硬编码密码 | Phase 2 强制模板化 + 扫描；漏网即返工 |
| 生成器产物风格漂移 | 精修对照基座示例（AGENTS §6.5） |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W2 / §4 拆分）。
