# ADR-005: 自研模块显式声明 rocketmq-spring-boot-starter（基座 starter-mq 的 optional 链）

- 日期：2026-11-28
- 状态：接受
- 关联：AGENTS R-2 / §6.4；lessons-learned L2-4、L2-15；spec §5.2、§6.1 步骤④；`docs/plans/plan-w5-concurrent-v2.md` Phase 2

## 背景

W5-P2 要给 material 域接 RocketMQ 事务消息（spec §6.1 步骤④：half 消息 + 本地事务 + commit/rollback 回查）。
动手前按 AGENTS §6.3「不猜接口」先读基座 MQ 封装，发现 **`yudao-spring-boot-starter-mq` 是个"空壳"**：

1. `yudao-spring-boot-starter-mq/pom.xml` 里 `rocketmq-spring-boot-starter` 标了 `<optional>true</optional>`；
2. 该 starter 在 `yudao-spring-boot-starter-mq` 的依赖里**自身也是 optional**（`<optional>true</optional>` 写在 starter-mq 这一层）；
3. 于是 `buildcloud-module-material-server` 即使显式声明了 `cn.iocoder.cloud:yudao-spring-boot-starter-mq`，
   **编译 classpath 里没有任何 `org.apache.rocketmq.*` 的类**。

实测证据（2026-11-28，本机 ali_repo `E:\JAVA\apache-maven-3.9.1\ali_repo`）：

| 证据 | 命令 / 位置 | 结果 |
| --- | --- | --- |
| 编译 classpath 里没有 rocketmq | `mvn -X compile` 打印的 `(f) compilePath` | 只有 `yudao-spring-boot-starter-mq-2026.10-SNAPSHOT.jar`（27KB），**无** `rocketmq-spring-boot-2.3.6.jar`、**无** `rocketmq-client-5.3.1.jar` |
| fat jar 里也没有 | `BOOT-INF/lib` 解包计数 | 228 个 jar，`grep rocket` 命中 0；只有 `yudao-spring-boot-starter-mq-2026.10-SNAPSHOT.jar` |
| 新装机器连诊断都不印 | `mvn dependency:tree` | material-server 的 `starter-mq` 节点下只显示 `yudao-spring-boot-starter-redis`，rocketmq 一行都没有 |
| 基座同一模块自身却有 | `mvn -f yudao-framework/yudao-spring-boot-starter-mq/pom.xml dependency:tree` | `rocketmq-spring-boot-starter:2.3.6` → `rocketmq-spring-boot:2.3.6` → `rocketmq-client:5.3.1` 全都在 |
| 依赖版本本来就有托管 | `yudao-dependencies/pom.xml` | `rocketmq-spring.version = 2.3.6`，`dependencyManagement` 已登记 |

**结论：W5-P1 及其之前，RocketMQ 从未真正上过任何自研服务的 classpath**——"编译通过"只证明了 starter-mq 那个空 jar 能加载，没证明 MQ 可用。

顺带记录一条诊断噪音（避免下一个人误判）：本项目自己的 SNAPSHOT 依赖（`2026.10-SNAPSHOT`）在 `dependency:tree` 里**子树打印不全**，`starter-mq` 节点下常常只剩 redis 一条；**不能拿 `dependency:tree` 判断"依赖在不在"**，要看 `mvn -X compile` 的 `compilePath` 或 fat jar 的 `BOOT-INF/lib`。

## 决策

**自研模块在使用 MQ 能力时，显式声明 `org.apache.rocketmq:rocketmq-spring-boot-starter`，版本由 `yudao-dependencies` 托管（不写死版本号）。**

```xml
<!-- buildcloud-module-material-server/pom.xml -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
</dependency>
```

不新增坐标、不新增版本管理、不改任何 `yudao-*` 模块的 pom。

## 理由与替代方案

| 方案 | 判断 |
| --- | --- |
| **显式声明（采纳）** | ✅ 与基座自身的做法完全一致：基座 4 个 server pom 都在"为 optional 依赖补声明"（lessons L2-4 的 `yudao-spring-boot-starter-rpc`、L2-15 的 `yudao-module-bpm-api` 都是同一类问题）。不改基座 pom（R-2），不新增依赖版本（AGENTS §6.4） |
| 改 `yudao-spring-boot-starter-mq` 去掉 optional | ❌ 否决：改基座 pom（R-2），且会把 rocketmq/kafka/rabbit 三条消息栈强行塞进所有引 MQ starter 的模块（system/infra 根本不用 RocketMQ），等于替所有人加依赖 |
| 绕开 MQ 用「本地消息表 + 定时扫描」 | ❌ 否决：spec §6.1 步骤④明确要求 half 消息 + 事务回查，这是 W5 的核心亮点；换方案等于改 spec 范围（R-4 + 需升版本） |
| 只在测试期加依赖 | ❌ 否决：运行时同样要 `RocketMQTemplate`/`@RocketMQMessageListener`，只加 test scope 会在启动时 `NoClassDefFoundError`（比编译失败更难查） |

## 影响

1. **口子必须一致**：material 已补；将来 report/ai 等要用 MQ 的自研模块，一律照此显式声明，不要指望传递；
2. **启动前置**：`yudao-spring-boot-starter-mq` 启动即建 Producer，**缺 namesrv 会直接启动失败**（lessons L1-7 实测）。因此 material 本地/联调必须有 `bc-rmq-namesrv`（127.0.0.1:9876），`application-local.yaml` 的 `rocketmq.name-server` 已就位；
3. **迁移要改 Nacos**：部分地区的测试环境若没有 MQ，需要评估「事务消息降级为同步落库」的开关，本 ADR 不含该范围；
4. **不要再用 `dependency:tree` 判断依赖是否存在**（SNAPSHOT 子树不全），统一口径：`mvn -X compile` 看 `compilePath`，或解 fat jar 看 `BOOT-INF/lib`；
5. **不改基座**：本 ADR 只约束 `buildcloud-module-*` 的 pom。

## 变更记录

- 2026-11-28 创建（W5-P2 开工实测发现；主控批准显式补依赖 + 立 ADR）。
