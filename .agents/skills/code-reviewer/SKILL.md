---
name: code-reviewer
description: 对代码变更做专业评审（本地改动或远程 PR）。关注正确性、可维护性、可读性、效率、安全、边界与错误处理、可测试性。**本项目每次写完代码后，主控与 worker 都必须调用本 skill 走一遍评审再进入下一阶段。**
---

# Code Reviewer（筑云适配版）

> 源自 `google-gemini/gemini-cli` 的 `.gemini/skills/code-reviewer`，按本项目（Maven / Spring Boot 3.5 / PowerShell）适配。
> **强制时机**：每个 Phase 写完代码、提交前，必须执行一次；主控验收时再独立执行一次（复核 worker 的自评）。
> 与 `AGENTS.md` 的关系：AGENTS 是红线（必须满足），本 skill 是**评审方法**（怎么找出红线之外的问题）。

## 工作流

### 1. 确定评审目标

- **远程 PR**：用户给了 PR 号/URL → 评审该 PR；
- **本地改动**（默认）：评审当前工作区相对 `HEAD` 的全部改动（含未暂存与暂存）。

### 2. 准备（本项目版）

```powershell
# ① 看改了什么
git status --short
git diff --stat            # 总览（先看规模，有没有超范围）
git diff                   # 详细 diff（未暂存）
git diff --staged           # 详细 diff（已暂存）

# ② 确认改动范围是否与 Phase 声明一致（AGENTS §6.2）
#    超出声明的必须能解释清楚

# ③ 预检（本项目标准验证套件）
$env:JAVA_HOME='D:\project\tools\jdk-17.0.21.1+2'
cmd /c "mvn -B -ntp '-DskipTests' '-Dmaven.test.skip=true' compile"   # 编译必须过（R-5）

# 单测（注意 lessons L3-16：Redis/MQ 集成测试需 -DargLine 注入，否则静默跳过=假绿）
cmd /c "mvn -B -ntp -pl <受影响模块> test"
# 有集成测试时：
# cmd /c "mvn ... -Dtest=XxxIntegrationTest ""-DargLine=-DBC_TEST_REDIS_PASSWORD=$pw"""

# ④ 密钥扫描（R-1）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/scan-secrets.ps1 -Staged
```

### 3. 深度分析（七个维度）

| 维度 | 本项目关注点 |
| --- | --- |
| **正确性** | 业务逻辑与 spec 一致？状态机流转有没有绕过？事务边界对不对（`@Transactional` 覆盖了该覆盖的写操作）？并发下有没有超卖/丢更新？ |
| **可维护性** | 分层是否照基座（controller/service/dal/api）？有没有把逻辑塞进 Controller？魔法数字是否抽了枚举/常量？重复代码是否该抽 |
| **可读性** | 关键决策有没有注释说明「为什么」（尤其反直觉的实现，如 V1 故意不加 CAS）？命名是否符合基座习惯 |
| **效率** | 有没有 N+1 查询？有没有在循环里查库/发 RPC？分页是否真的分页？索引是否覆盖查询条件 |
| **安全** | 有没有密钥硬编码（R-1）？有没有 SQL 注入（拼接字符串）？越权（数据权限是否生效）？敏感信息是否进日志 |
| **边界与错误处理** | null/空集合/负数/超大值？外部调用失败（RPC/MQ/Redis）有没有兜底？幂等是否真的幂等（重复回调、重复投递） |
| **可测试性** | 关键路径有测试吗？断言是否**断言了返回值而不只是重查库**（lessons L3-10）？测试是否真的执行了（不是 skip） |

### 4. 输出格式

```markdown
## 评审摘要
（2-3 句：改了什么、整体质量、是否建议进入下一阶段）

## 发现

### Critical（必须修，否则不得提交/合并）
- [文件:行] 问题描述 → **为什么** → 建议修法

### Improvements（建议修）
- ...

### Nitpicks（可选）
- ...

## 结论
Approved / Request Changes
（Request Changes 必须列出阻塞项）
```

**语气**：建设性、专业；说明「为什么」要改，而不是只说「要改」；批准时点出做得好的地方。

### 5. 收尾

- 远程 PR 评审后，问用户是否切回默认分支；
- 本地评审后，把**阻塞项**写进对应 Phase 的进度日志，修完再提交。

## 本项目特有的高频检查项（按踩过的坑）

> 这些来自 `docs/ops/lessons-learned.md`，评审时优先扫：

1. **DO 是否 `extends TenantBaseDO`**（R-8，否则多租户失效）；
2. **Controller 首段路径是否 = 服务标识**（R-9，否则直连通、经网关 404）；
3. **唯一索引 vs 逻辑删除**（ADR-004：A 类不能有唯一索引、B 类必须有）；
4. **断言是否只查库**（L3-10：必须至少一条断言 Service 返回值）；
5. **测试是否静默跳过**（L3-16：看 `Tests run / Skipped / Errors` 三个数）；
6. **打包是否带 `repackage.skip`**（L1-1f：会静默把 fat jar 换成 thin jar）；
7. **注解 SQL 的可空时间参数是否带 `jdbcType`**（L1-1g）；
8. **改动是否碰了基座**（R-2：只允许「新增文件 + 纯追加 bean」，且必须有 ADR）。
