# W5 高并发改造冒烟报告（smoke-w5）

| 项 | 内容 |
| --- | --- |
| 版本 | v1.0（2026-11-29） |
| 对应计划 | [plan-w5-concurrent-v2.md](../plans/plan-w5-concurrent-v2.md) Phase 6 |
| 上游 spec | §6.1（V1/V2 定义 + 防超卖三道防线）、§8.2（压测矩阵）、§1.5（成功标准） |
| **口径声明** | 本报告数字**全部来自真实执行**（JUnit 并发测试 + 真 Redis/真 H2 库），**不含任何估算或目标值冒充实测值**（AGENTS R-6）。
| **重要限制** | ① 本报告是**功能级并发冒烟**（Service 层，**不含网关/序列化开销**）；② **不是** §8.2 规定的正式 JMeter 压测（后者在 W6 出）；③ 单机单实例、16G 开发机。 |

---

## 1. 环境记录（R-6 要求）

| 项 | 值 |
| --- | --- |
| CPU / 内存 | 本机 Windows 11，16 GB（Docker Desktop VM 实际可用 ≈ 7.5 GiB） |
| JDK | Temurin 17.0.21.1（`D:\project\tools\jdk-17.0.21.1+2`） |
| Spring Boot | 3.5.x（master-jdk17 基线） |
| MySQL | 8.4（容器 `bc-mysql`，127.0.0.1:13306） |
| Redis | 7.4-alpine（容器 `bc-redis`，127.0.0.1:16379） |
| RocketMQ | 5.3.1（namesrv 9876 / broker 10911） |
| 测试库 | **H2 内存库**（`MODE=MySQL`，见 lessons L3-19）+ **真 Redis**（集成测试） |
| 服务实例 | 单实例，无集群 |
| 是否含网关 | **不含**（Service 层直调，见口径声明②） |

> 与 W4 的 V1 基线（`plan-w4-material-v1.md`：含网关的 12.6/19.0/13.6 rps）**口径不同，不可直接相比**。本报告统一用 Service 层口径。

---

## 2. 三断言结果（**全部真实执行、查库/查 Redis**）

### 断言 1：0 超卖

**定义**（spec §6.1）：`期初库存 == available_qty + Σ(成功流水的扣减量)`，且 `available_qty >= 0`。

| 场景 | 输入 | 期望 | 实测结果 | 判定 |
| --- | --- | --- | --- | --- |
| V1 并发 · 恰好需求 | 库存 50，10 线程各申领 10 | 成功 5 / 失败 5，期末 0 | `RequisitionServiceImplTest#testSubmitConcurrent_noOversell_underRowLock` 通过 | ✅ PASS |
| V1 并发 · 同单重复 | 同一单据 5 线程并发提交 | 只生效 1 次，库存只扣 1 次 | `testSubmitConcurrent_sameRequisition_onlyOneDeduct` 通过（100→90，流水 1 条） | ✅ PASS |
| V2 并发（Lua 预扣） | Redis 初始 100，20 线程各预扣 10 | 成功 10 / 不足 10，可用 0，明细 10 条 | `StockPredeductServiceIntegrationTest` 并发用例通过 | ✅ PASS |
| V2 幂等 | 同 reqNo 8 线程并发预扣 | 真扣 1 次 / 幂等 7 次 | 同上，`available=90` | ✅ PASS |

**账平公式验证**（V1，`testSubmitConcurrent_noOversell_underRowLock`）：
```
期初 50 = available(0) + Σ成功扣减(50)   ✅
SUM(stock_log.quantity) == available_qty  ✅（P6 `RequisitionP6BlindSpotTest` 持续断言）
```

### 断言 2：关单回滚正确

**定义**：超时关单 / 审批驳回后，库存恢复到申领前，且 Redis == DB。

| 检查项 | 实测结果 | 判定 |
| --- | --- | --- |
| 超时关单后库存恢复 | 状态 1→8，库存回到期初，冲正流水 1 条（正数） | ✅ PASS |
| Redis == DB（V2） | 每个阶段直读两值比对：预扣后 70/70、关单后 100/100、重复关单后 100/100 | ✅ PASS |
| 只关「审批中」的单 | 已扣减(2)/已完成(4) 收到超时消息 → 保持原状态、无冲正流水 | ✅ PASS |
| 驳回与超时**共用同一组件** | `@SpyBean` + Mockito `verify` 证明两者都调 `StockRollbackService.rollbackForRequisition` | ✅ PASS |

**证据来源**：`RequisitionTimeoutCloseServiceTest`（15 例）、`StockRollbackServiceIntegrationTest`（6 例，真 Redis）。

### 断言 3：重复消息幂等

| 检查项 | 实测结果 | 判定 |
| --- | --- | --- |
| 同一消息重复投递 | 重放 APPROVE/REJECT 回调 → HTTP 200、**零副作用**（库存不变、流水不增、状态不变） | ✅ PASS |
| 消费幂等 | 三重闸：`req_no` 唯一 + 状态机判断 + 行级流水计数 | ✅ PASS |
| 对账修复幂等 | 连跑两轮：第一轮修完 → **第二轮 diff=0** | ✅ PASS |

---

## 3. V1 vs V2 对照（**如实说明差距**）

### 3.1 本次能给出的真实数据

| 维度 | V1（行锁同步） | V2（Lua 预扣 + MQ 异步） |
| --- | --- | --- |
| 扣减时机 | 请求线程内同步，持行锁至事务结束 | Redis Lua 原子预扣（无 DB 锁），DB 由 MQ 消费者异步扣 |
| 并发正确性 | ✅ 0 超卖（行锁串行化） | ✅ 0 超卖（Lua 原子 + DB 条件更新兜底） |
| 热点行为 | 同库存行请求**全部排队** | 请求只碰 Redis 单 key，DB 侧由消费者排队 |
| 吞吐实测 | **12.6 / 19.0 / 13.6 rps**（1/5/20 路，**含网关**，W4 粗测） | **本阶段未取得有效吞吐数据**（见 3.2） |

### 3.2 **未达成的部分（如实记录，不粉饰）**

**V2 的吞吐数字本阶段没有拿到**，原因链如下：

1. 首次尝试 PowerShell 并发 HTTP 脚本 → 踩到 `.ps1` 需 UTF-8 BOM（L1-3）与 `Task.Run` 传参重载问题；
2. 改用 JUnit 并发测试（Service 层）→ 冒烟测试类需手动 `@Import` 一长串 bean（`RequisitionServiceImpl` → `SubmitHandlerFactory` → `V1/V2SubmitHandler` → `StockPredeductService` → `RequisitionDeductMessageProducer`），依赖链过深；且 H2 的 `uk_project_material` 唯一键在多次 `prepareProject` 间冲突；
3. 时间盒耗尽 → **决定不在本阶段硬凑**，因为：
   - **压测报告是简历数字的出处（R-6），宁可缺，不可假**；
   - spec §8.2 本就规定**正式压测在 W6**（JMeter 矩阵 + 完整环境记录），W5 只要求「100 并发冒烟」；
   - 冒烟的核心价值（**并发正确性三断言**）**已经全部拿到**（见 §2）。

**因此：本报告不给出 V2 的 QPS/P99 数字。** W6 会用 JMeter 跑正式矩阵（V1/V2 各 100→500→1000 阶梯），届时出 `perf-report-first-round.md`。

---

## 4. 结论

| 项 | 结论 |
| --- | --- |
| **并发正确性** | ✅ **全部达标**：V1/V2 均 0 超卖；关单回滚正确；重复消息幂等；对账修复幂等 |
| **V2 功能完整性** | ✅ 可用：Lua 预扣 → 事务消息 → 异步扣减 → 延迟关单 → 对账兜底，全链路已实现并测试 |
| **V1/V2 开关** | ✅ 策略模式，`buildcloud.material.requisition-mode` 切换，V2 未实现时明确报错不静默回退 |
| **性能对照** | ⚠️ **未完成**（V2 吞吐数据缺），移交 W6 正式压测 |
| **是否达到 spec §1.5「V2 QPS ≥ V1×5」** | ❓ **无法判定**——缺 V2 实测数据。**W6 压测前不得在任何文档/简历中声称该指标** |

---

## 5. 下一步（W6 压测前置）

1. **补齐 V2 吞吐测量**：方案 A（推荐）用 JMeter 走真实 HTTP（含网关），方案 B 用轻量 Java 并发 harness（注意绕开本阶段踩的 `@Import` 依赖链问题——建议直接 `@SpringBootTest` 全量加载而非 `BaseDbUnitTest`）；
2. **测试数据隔离**：冒烟前重建 stock/Redis/流水，避免 H2 唯一键冲突；
3. **环境固定**：记录 CPU/内存/JDK/容器限额，同一环境下跑 V1 与 V2（否则数字不可比）；
4. **阶梯策略**：100 → 500 → 1000，每档 5 分钟，与 spec §8.2 一致。

---

## 6. 附：本阶段产出清单

| Phase | 交付 | 测试 |
| --- | --- | --- |
| P1 | Redis Lua 原子预扣（3 脚本 + Service） | 3 单测 + 16 集成 |
| P2 | RocketMQ 事务消息 + 幂等消费 + V2 处理器 | 56 |
| P3 | 延迟关单 + 统一回滚组件 | 15 + 6 集成 + 1 探针 |
| P4 | 对账 Job（三类差异 + 幂等修复） | 14 |
| **合计** | **material 模块 276 例全绿，Skipped=0** | |
