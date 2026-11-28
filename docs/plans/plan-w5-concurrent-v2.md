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
| 验收标准 | [x] 本地事务失败时 half 消息被丢弃（单测）；[x] 同一 req_no 重复投递只生效一次（单测）；[x] 消费后 Redis 预扣明细与 DB 流水对应 |
| 上下文包（必读） | spec §6.1 步骤④、幂等条款、防超卖三道防线；基座 RocketMQ producer/consumer 模式（只读） |
| 状态 | ✅ 2026-11-28 完成（56 个 MQ 相关用例全绿；ADR-005 修正 starter-mq 空壳依赖问题） |

### Phase 3：延迟关单 + 回滚冲正

| 项 | 内容 |
| --- | --- |
| 工作量 | 1.5d |
| 任务 | 提交时发 RocketMQ 延迟消息（30 分钟）；到期未审完→已关闭 + Lua 恢复库存 + 冲正流水（biz_type=冲正）；审批驳回路径复用同一回滚组件 |
| 交付物 | 延迟关单 + 统一回滚冲正组件 + 单测 |
| 验收标准 | [x] 超时关单后库存恢复且 Redis=DB（单测断言）；[x] 驳回与超时共用回滚组件（代码路径一致）；[x] 冲正流水可对账 |
| 上下文包（必读） | spec §5.2 状态机、§6.1 步骤⑤⑥；Phase 1/2 产出 |
| 状态 | ✅ 2026-11-28 完成（15 例 H2 关单单测 + 43 例审批单测 + 6 例真 Redis 集成 + 1 例真 Broker 延迟级别探针，全绿） |

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
| 验收标准 | [x] 100 并发冒烟**三断言全过**（0 超卖：V1 库存 50/10 线程各 10 → 成功 5 失败 5、期末 0；V2 Redis 100/20 线程各 10 → 成功 10 不足 10、可用 0；关单回滚：状态 1→8 库存恢复且 Redis==DB；重复消息幂等：重放回调零副作用 + 对账连跑两轮第二轮=0）；[x] V1/V2 开关切换互不污染（策略模式 + 独立 projectId 段隔离）；[x] 冒烟报告 `docs/testing/smoke-w5.md` 已登记；**⚠️ V2 吞吐数据未取得，移交 W6 正式压测（报告 §3.2 如实记录原因）** |
| 上下文包（必读） | spec §6.1 断言定义、§8.2；Phase 1-5 全部产出 |
| 状态 | ✅（2026-11-29，**性能对照部分移交 W6**） |

## 3. 计划级验收标准（出口条件，来自 spec §9 W5）

- [x] 100 并发冒烟：0 超卖、关单回滚正确、重复消息幂等（三断言留档于 `docs/testing/smoke-w5.md`）；
- [ ] **V1 vs V2 吞吐对照数据** —— 未完成，移交 W6 正式 JMeter 压测（W5 属功能级冒烟，spec §8.2 规定正式压测在 W6）；
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

- 2026-11-29（W5-P4 对账 Job 修复 4 个失败用例）：**修的是上一轮 worker 留下的 4 红**（`StockReconcileServiceTest` run=14 fail=4）。根因两条，均与 lead 已修的 `fixD3` 顺序无关：① **D2 的冲正流水符号搞反**——"删一条 -30 脏流水 + available 回补 30 + 写一笔 +30 冲正"，删掉**负数**流水等价于 SUM **加** 30，回补又加 30，两个 +30 已抵消，再写正数冲正就多一份 → `SUM=130 vs available=100`（`testHumanMade_dirtyLog` / `testFix_allTypes`）；改法是**去掉冲正**（逻辑删除已留证据，与 W5-P3「V2 在途只还 Redis 不写冲正」同一纪律）。② **被排除出 D3 判定的量差没有负责人**——检测期"有 D1/D2 的维度跳过 D3"对 D1 成立（DB 降、Redis 不变→自然相等），但 D2 回补是 DB 涨、Redis 不变，差一个回补量且被 D3 排除，只能等下一轮 → 幂等破产（`testFix_idempotent_secondRoundClean` 第二轮 D3=1）。改法：新增 `alignRedisAfterD2`，**D2 修完在同一轮内**把 Redis RESET 到新 DB 值并按明细重建在途预扣（用 RESET 绝对口径而非 `INCRBY`，避免"旧漂移被留下还看起来修好了"）；顺序固定 DB 先 Redis 后（L3-18 同源，失败停在"DB 对、Redis 落后"可自愈）。③ 第 4 个红是**断言写错不是实现错**：`testShard` 按"奇数→0 号片"的直觉写，实际 `Math.floorMod(1,2)==1` → projectId=1 属 **1 号片**，只改期望值并加注释。**测试护栏修正**：原用例断言 `countLog(REVERSAL)==1`（它保护的正是错误实现），改为断言 `==0` 并写明理由；`testHumanMade_dirtyLog` 里"第二轮再报一条 D3 才收敛"的预期属同一错误口径，改为"第二轮直接为 0"。**数字**：逐次验证 14 例 → 修 D2 的 Redis 收口后 **run=14 fail=3**（D3 红转绿，证明根因②）→ 修冲正后 **fail=1**（只剩分片断言错）→ 修断言后 **run=14 fail=0 skipped=0**；模块全量 **`Tests run: 275, Failures: 0, Errors: 0, Skipped: 22`**，22 个 skip 是 Redis 集成测试（`System.getenv` 探活），按 L3-16 方式 A `cmd /c "set BC_TEST_REDIS_PASSWORD=...&& mvn ..."` 注入后 **16+6 全部真跑 skipped=0**。**既有失败（与本次改动无关，已取证）**：`RequisitionTimeoutDelayLevelIntegrationTest` 在跑全量时报 level=18 消息 5 秒内到达；该文件自 `HEAD` 起未改动、与库存/对账无任何调用关系，`PullConsumer` 实测探针 topic 四个队列各积压 3~4 条（= 之前多次运行遗留的 level=18 消息到点后重投），**是探针 topic 的跨次污染，不是回归**；不在本 Phase 修（改它会动 W5-P3 的验收口径）。**评审（R-10）**：自查中发现并修掉 1 处真实缺陷——`alignRedisAfterD2` 的"期望值"按 `details.values()` 求和、重建循环却跳过非正数量，两处口径不一致会把正常修复判成失败；另修正 `reverseDirtyDeduction` 方法头的步骤列表仍写着第 3 步写冲正（与正文自相矛盾）。密钥扫描全仓 2599 文件通过；基座/V1 零改动。**新经验**：L3-20（对账修复的顺序耦合）、L3-21（`floorMod` 分片归属与直觉相反）。

- 2026-11-28（W5-P3 延迟关单 + 回滚冲正）：交付 **延迟消息生产者** `RequisitionTimeoutMessageProducer`（默认固定 **delay level 16 = 30 分钟**，依据是本机 Broker 实测：`mqadmin getBrokerConfig` → `messageDelayLevel 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h`，第 16 项 = 30m；联调可切秒级，见下）、**延迟消费者** `RequisitionTimeoutCloseConsumer`（独立 topic/tag/消费组，CONCURRENTLY，maxReconsumeTimes=3）、**事务承载** `RequisitionTimeoutCloseService`（状态白名单 `status=1` + `timeout_time<=now()` 二次核对 + `closeTimeoutCas`，7 态 `TimeoutCloseOutcome`）、**统一回滚组件** `StockRollbackService(Impl)` + `RequisitionRollbackResult`（RESTORED / SETTLED_AT_REDIS_ONLY / SKIPPED_NO_STOCK_IMPACT / PARTIAL_RESTORED）。**驳回路径已改为调组件**（`handleApprovalResult` 驳回分支不再自己读明细锁行），`submitApproval` 末尾发延迟消息（**失败只记 error、不回滚业务**）。**Redis 与 DB 非事务一致性的处置**：组件的 DB 回补以「本行是否有 biz_type=2 扣减流水」为判据（V2 在途单据只还 Redis、**不写冲正流水**，否则对账多出一笔），且 **Redis 还原放在 DB 回补之前（fail-fast）**——集成测试实测"Redis 拒绝还原时 DB 已补"的中断态后改的。**测试**：`RequisitionTimeoutCloseServiceTest` 15 例（H2 真库，含「Redis==DB」替身断言、不误关已扣减/已完成/已出库/已取消/已关闭/草稿、重复投递 3 次只补一次、单据被改回已提交后靠冲正流水幂等、消息早到不关、多行明细、V2 在途只还 Redis、迟到审批回调不能把已关闭拉回已扣减）；`StockRollbackServiceIntegrationTest` 6 例（**真 Redis**，`@EnabledIf` 探活，实测 `[关单后] DB=100.0000 Redis=100` 等六条证据）；`RequisitionTimeoutDelayLevelIntegrationTest` 1 例（**真 Broker** 探针：level=18 的消息 5 秒内不投递、level=1 的消息 8.7 秒内到达 → 级别表真实生效）；驳回侧新增 `testHandleApprovalResult_reject_callsUnifiedRollbackComponent`（`@SpyBean` + `verify` 证明调的是组件同一个方法）。**全量**：`Tests run: 262, Failures: 0, Errors: 0, Skipped: 0`（含 P1 既有的 16 例 Redis 集成测试，用 `cmd /c "set BC_TEST_REDIS_PASSWORD=...&& mvn ..."` 真连，skip 归零）；`mvn install` 不带 repackage.skip，fat jar 183.5MB 且含 9 个新 class。**取舍留痕**：① 取消路径（`cancelRequisition`）**没有**并入组件——`handler.cancel` 契约里同时含「DB 回补」与「Redis 回滚」，组件再补一遍会导致同单补两次（实测库存补到 130），收敛需改 V1 语义，本 Phase 保持 W4 行为；② 关单后**不**自动终止 bpm 流程实例（需 bpm 新接口，R-2），靠 `approval_status=4` 让迟到回调在 `finishApprovalCas` 上幂等返回。联调：本机无运行中的 buildcloud 服务，material 服务按 `--spring.profiles.active=local` 启动失败于 **AGENTS 已记录的既有问题**（`RocketMQTemplate` bean 缺失，与 P2 一致，非本 Phase 引入），延迟消息的端到端验证改由真 Broker 探针 + 真 Redis 集成测试覆盖。