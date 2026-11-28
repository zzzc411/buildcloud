# 开发计划：plan-w5-concurrent-v2（高并发改造 V2）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w5-concurrent-v2 |
| 对应 spec 章节 | §6.1（V2 全链路）、§6.2（缓存体系）、§9 W5 |
| 状态 | 进行中（P1 ✅ / P2-P6 待做） |
| 计划开始 | 2026-12-28（周一） |
| 计划完成 | 2027-01-03（周末，打 tag `w5`） |
| 前置依赖 | plan-w4-material-v1（✅ V1 与状态机就绪，开关已预留） |
| 分支约定 | `feature/w5-v2`（核心链路，建议按 Phase 小分支合流） |

## 1. 目标（一句话）

申领 V2 全链路上线：Lua 原子预扣 + RocketMQ 事务消息异步落库 + 延迟关单 + 对账 Job + Sentinel/布隆/两级缓存，100 并发冒烟通过（0 超卖、关单回滚正确、重复消息幂等），V1/V2 开关共存。

## 2. Phase 清单（Phase 5 可与 2-4 ∥ 并行；其余串行）

### Phase 1：Redis Lua 原子预扣

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | Lua 脚本：`pm:stock:{projectId}:{materialId}` 原子「校验+扣减+记预扣明细(reqNo→hash)」；Redisson 执行封装；入库时同步初始化缓存；失败路径友好降级（不打 DB）；Lua 脚本语义单测（含边界：不足/并发/重复 reqNo） |
| 交付物 | 预扣组件 + Lua 单测套件 |
| 验收标准 | [x] 脚本原子性单测通过（模拟并发不超卖）；[x] 失败返回明确错误码（material 段位）；[x] 预扣明细 hash 可查（对账用） |
| 上下文包（必读） | spec §6.1 步骤③、§6.2 缓存表；Redisson 文档/基座 Redis 用法（只读） |
| 状态 | ✅ 2026-11-28 完成（Lua 组件 + 单测全绿；入库接入留到 Phase 2） |

### Phase 2：RocketMQ 事务消息 + 幂等消费

| 项 | 内容 |
| --- | --- |
| 工作量 | 2d |
| 任务 | 半消息发送 + 本地事务（创建申领单已提交 + pending 流水）+ commit/rollback 回查；消费者：DB 真实扣减（乐观锁条件更新 `available >= qty`）+ stock_log 落库 + 状态→已扣减；幂等= req_no 唯一索引 + 状态机判断（重复投递直接 ACK）；**消费失败重试与死信**策略 |
| 交付物 | 事务消息链路 + 幂等消费单测（重复消息/回查/死信三路径） |
| 验收标准 | [ ] 本地事务失败时 half 消息被丢弃（单测）；[ ] 同一 req_no 重复投递只生效一次（单测）；[ ] 消费后 Redis 预扣明细与 DB 流水对应 |
| 上下文包（必读） | spec §6.1 步骤④、幂等条款、防超卖三道防线；基座 RocketMQ producer/consumer 模式（只读） |
| 状态 | ☐ |

### Phase 3：延迟关单 + 回滚冲正

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 提交时发 RocketMQ 延迟消息（30 分钟）；到期未审完→已关闭 + Lua 恢复库存 + 冲正流水（biz_type=冲正）；审批驳回路径复用同一回滚组件 |
| 交付物 | 延迟关单 + 统一回滚冲正组件 + 单测 |
| 验收标准 | [ ] 超时关单后库存恢复且 Redis=DB（单测断言）；[ ] 驳回与超时共用回滚组件（代码路径一致）；[ ] 冲正流水可对账 |
| 上下文包（必读） | spec §5.2 状态机、§6.1 步骤⑤⑥；Phase 1/2 产出 |
| 状态 | ☐ |

### Phase 4：对账 Job（XXL-Job）

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 定时比对 Redis 预扣 vs DB 流水（pending vs 已扣减）；差异→站内信告警 + 修复动作（幂等）；对账结果快照表/日志 |
| 交付物 | 对账 Job + 差异告警单测 |
| 验收标准 | [ ] 人造差异（删 Redis key / 改 DB）被检出并告警；[ ] 修复动作幂等可重跑 |
| 上下文包（必读） | spec §6.1 步骤⑦；基座 XXL-Job 任务接入模式（只读） |
| 状态 | ☐ |

### Phase 5：Sentinel + 布隆过滤器 + 两级缓存 ∥

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 网关/接口级限流 + 热点参数（materialId）；Redisson 布隆过滤器 `bf:material`（误判 1%，新增物料异步 rebuild）；物料/目录 Caffeine+Redis 两级缓存（TTL+随机、逻辑过期+锁重建、空值 60s） |
| 交付物 | 限流规则 + 防穿透组件 + 缓存命中单测 |
| 验收标准 | [ ] 限流触发返回友好响应；[ ] 不存在物料被 BF 拦截不打 DB；[ ] 两级缓存命中率单测可见（Caffeine stats） |
| 上下文包（必读） | spec §6.1 步骤①②、§6.2 缓存表全文；基座 Sentinel 接入（只读） |
| 状态 | ☐ |

### Phase 6：V1/V2 开关联动 + 100 并发冒烟

| 项 | 内容 |
| --- | --- |
| 工作量 | 1d |
| 任务 | 配置开关切 V2 全链路（同一接口）；100 并发冒烟脚本（JMeter 或单测并发）：0 超卖断言（期初==available+成功流水）、关单回滚、重复消息幂等；冒烟结果入 `docs/testing/` |
| 交付物 | V2 可用 + `docs/testing/smoke-w5.md`（含断言结果） |
| 验收标准 | [ ] 100 并发冒烟三断言全过（0 超卖/回滚正确/幂等）；[ ] V1/V2 开关切换互不污染数据；[ ] 冒烟报告登记 |
| 上下文包（必读） | spec §6.1 断言定义、§8.2；Phase 1-5 全部产出 |
| 状态 | ☐ |

## 3. 计划级验收标准（出口条件，来自 spec §9 W5）

- [ ] 100 并发冒烟：0 超卖、关单回滚正确、重复消息幂等（三断言留档）；
- [ ] V2 链路 + V1/V2 开关交付；
- [ ] 防超卖三道防线各自有单测（Lua/乐观锁/对账）；
- [ ] tag `w5` + 状态同步 + 进度日志收尾。

## 4. 涉及范围（改动声明）

- 代码模块：`buildcloud-module-material`（申领链路重构区）+ 网关 Sentinel 规则；
- 数据库/SQL：pending 流水/消费状态相关 DDL（如需）；
- 配置/Nacos：V1/V2 开关、Sentinel 规则、MQ topic/tag；
- 文档：`docs/testing/smoke-w5.md`、高并发设计文档初稿（并入 W6 定稿）。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 事务消息回查与本地事务时序坑 | 严格按基座/官方示例；单测先覆盖回查路径再联调 |
| Redis 与 DB 不一致 | 三道防线兜底 + 对账 Job；冒烟不过不收口（AGENTS 红线） |
| 16G 起全链路压测吃力 | 冒烟仅 100 并发；正式压测 W6 并用分批启动 |

## 6. 进度日志（追加式，一行/次）

- 2026-11-24：计划创建（自 spec §9 W5 / §6.1 / §6.2 拆分；Phase 5 可并行）。
- 2026-11-28（W5-P1 Lua 原子预扣）：交付 `scripts/lua/stock-prededuct.lua`（预扣：成功1/不足0/需初始化-1/幂等2）、`stock-prededuct-rollback.lua`（回滚：1/0/-1/-2）、`stock-init.lua`（初始化 reset/accumulate）+ `StockPredeductService(Impl)` + `StockCacheKeys` + 3 个 1-102-080-xxx 错误码；**未接入入库与申领链路**（W5-P2 一并接，避免半接入双写不一致），V1 代码零改动、基座零改动。单测：`StockLuaScriptSyncTest` 3 例（两份脚本副本逐字节一致守护 + key 格式 + 返回码钉死，不依赖 Redis），`StockPredeductServiceIntegrationTest` 16 例（**连真 Redis 127.0.0.1:16379**，`@EnabledIf` 探活，实测 run=16 skipped=0）。**并发证据（核心）**：初始 100 / 20 线程各预扣 10 → 成功 10、不足 10、最终 available=0、明细 10 条、明细合计=100（无超卖，证明 Lua 原子性）；同一 reqNo 8 线程并发 → 真扣 1、幂等 7、available=90。模块全量测试 177 例全绿（含既有 158 例无回归），`mvn install` 不带 repackage.skip，jar 143.4MB 且内含三个 lua 脚本，本地 `--spring.profiles.active=local` 启动成功、`/admin-api/buildcloud-material/health` 返回 `code=0`、启动日志 `[init][Lua 预扣脚本加载成功，探活返回 code=-1]`（jar 内脚本真跑通）。Redis 手工 EVAL 八个返回码全部实测（T1..T8 见 worker 汇报），并定位到两条坑：① `RScript.ReturnType` 无 `INTEGER`（用 `LONG`）；② **PowerShell 会把 redis-cli `--eval` 的 KEYS/ARGV 分隔符 `,` 当数组运算符吞掉**，必须写成 `sh -c "redis-cli ... --eval f k1 k2 , a1 a2"` 或改用 `EVAL "$(...)" <numkeys>`。下一步：Phase 2 事务消息 + 幂等消费，并把 `initAvailable` 接入入库流程。
