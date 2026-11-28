# 子智能体派发指南（_dispatch）

> **用途**：把 `docs/plans/` 里的计划派给子智能体（worker）执行时，主控（人类或 lead 智能体）按本指南准备与派发。
> **定位**：[AGENTS.md](../../AGENTS.md) 是约束（红线，人人适用），本指南是派发操作手册（怎么派、怎么收）。计划文件（plan-*.md）定义**做什么**，本指南定义**怎么交出去**。

## 1. 派发模型

```
计划 plan-w{N}-*.md ──切分──▶ Phase 1..n（每个 Phase = 一次派发的最小单元）
                                  │
                  主控选一个 Phase ──▶ 组装上下文包 ──▶ 派发给 worker 子智能体
                                  │
worker 完成并自查 ──▶ 交付（代码+文档+进度日志） ──▶ 主控验收 ──▶ 合入/返工
```

- **主控**（人 / lead）：持有全局视野，负责切 Phase、备上下文、验收、合并、更新状态。不亲自写大块业务代码。
- **worker**（子智能体）：只对自己那个 Phase 负责，遵守 AGENTS.md（自动生效于工作区），不跨 Phase 决策、不改其他 Phase 的范围。
- **一个 Phase = 一次会话**：不要在一次派发里塞两个 Phase；也不要把一个 Phase 拆给两个 worker 并行（除非 Phase 内任务天然无交集且文件集不相交）。

## 2. Phase 切分标准（计划里已按此写好，新计划照做）

1. 工作量 **0.5d ~ 2d**（子智能体一次会话能消化）；
2. **出口可验证**：编译通过 / 单测通过 / 指定文档产出，验收标准逐条可勾；
3. **文件集封闭**：Phase 尽量只碰一个模块；跨服务改动按服务拆 Phase；
4. **依赖单向**：Phase 的前置只能是已完成的 Phase，禁止双向依赖。

## 3. 上下文包（派发前必须准备）

派发提示词**必须**包含以下六项，缺一不派：

| # | 项 | 内容来源 |
| --- | --- | --- |
| 1 | 身份与约束 | 「先读 AGENTS.md」一句话（红线自动生效，不必复制全文） |
| 2 | 任务定位 | 计划文件路径 + Phase 编号 + 一句话目标 |
| 3 | 必读文件清单 | spec 相关**章节号**（只给章节，不给全文）+ 基座参考代码路径 + 前置 Phase 的产出物路径 |
| 4 | 环境事实 | 工作区 `D:\project\buildcloud`；分支名；JDK17/Windows/PowerShell 注意事项 |
| 5 | **经验条目** | 从 `docs/ops/lessons-learned.md` 挑与本 Phase 相关的条目（如改配置→L2-10 字符集；写 DO→L2-2；改脚本→L1-1/L1-3/L1-4），原文贴入 |
| 6 | 交付物与验收 | 从计划复制该 Phase 的「交付物」与「验收标准」，**验收写成可执行命令** |
| 7 | 完成动作 | 自查清单（编译/测试/敏感信息/`git clone` 验证）+ 在计划「进度日志」追加一行 + 提交规范 |

**禁止**：把整个 spec 全文塞进提示词（用章节引用）；让 worker「顺手」做 Phase 外的任何事。

## 4. 派发提示词模板（复制即用）

```markdown
你是筑云项目的 worker 子智能体，负责执行一个 Phase。

【0. 先读约束】
读 D:\project\buildcloud\AGENTS.md（红线：密钥零提交 / 不改基座包名 / 编译不过不提交…），全部适用。
读 D:\project\buildcloud\docs\ops\lessons-learned.md 中与本 Phase 相关的条目（环境/基座/流程三类）。

【1. 任务】
计划：D:\project\buildcloud\docs\plans\plan-w{N}-{scope}.md
执行 Phase {编号}（{Phase 标题}）。目标：{一句话}。
只做本 Phase 声明范围内的事；发现需要扩范围，停下来在结果里说明，不要自行扩。

【2. 必读（按序）】
- spec v1.2：D:\project\buildcloud\docs\spec\buildcloud-spec-v1.2.md 的 §{xx}、§{yy}
- 前置产出：{路径列表}
- 基座参考（只读，不改）：{基座模块路径}
- 计划文件本身（重点看 Phase 表与验收标准）

【3. 环境】
工作区 D:\project\buildcloud（Windows + PowerShell，JDK 17 在 D:\project\tools\jdk-17.0.21.1+2）。在分支 {branch} 上工作。
{分批启动/内存限制等专项提示，如适用}

【4. 交付物（来自计划，逐项）】
- {交付物 1}
- {交付物 2}

【5. 验收（完成前逐条自检）】
- [ ] {验收 1}
- [ ] {验收 2}

【6. 完成动作】
1. 自查：编译/相关单测通过；git diff 无密钥与敏感信息。汇报须写清 `Tests run / Skipped / Errors` 三个数字（lessons L3-16：跳过的测试要能解释，否则视为假绿）。
2. **代码评审（强制）**：调用 `code-reviewer` skill 对本 Phase 全部改动走一遍评审（正确性/可维护性/可读性/效率/安全/边界与错误处理/可测试性），
   把 Critical 项修完；Improvements/Nitpicks 在处理后说明取舍。评审结论写进汇报。
3. 在计划文件「进度日志」末尾追加一行：日期 + Phase{N} + 做了什么/结果 + 评审结论。
4. 按 AGENTS.md §5 提交（`{type}: 一句话`），一个 Phase 一个或少量提交。
5. 汇报：列出触碰的文件、提交哈希、评审摘要、遗留问题（若有）。

遇到阻塞（两种合理方案仍不通）：停下，写清现象与尝试，报告主控，不要静默绕过或降低验收标准。
```

> **评审是强制步骤，不是可选项**：不完成 `code-reviewer` 评审的 Phase 视为未交付，主控验收时直接退回。
> 主控在合并前**独立再跑一次** `code-reviewer`（复核 worker 的自评是否到位），两次结论不一致时以主控为准。

## 5. 验收与合并（主控职责）

1. **收货检查**：worker 汇报的文件集 ⊆ Phase 声明范围；`mvn -q compile`（或对应模块）+ 受影响单测；文档已更新（计划进度日志、涉及 docs 登记）；
2. **红线检查**：diff 无密钥/内网信息；基座包名未被改动（`git diff --stat` 里 `cn.iocoder.yudao` 相关只读）；
3. **达标**：合入（worker 已在 feature 分支提交的，主控 fast-forward 合并 main；直写 main 的需主控复核提交）；更新计划状态与 `00-overview.md`；
4. **未达标**：明确返工指令（引用验收条目），不接受「差不多」；两次返工仍不过 → 该 Phase 标记阻塞，人工介入；
5. **周末**：按 spec §7.3 打 tag `w{N}`，tag message 引用该周计划验收结论。

## 6. 并行派发规则

- 依赖图见 `00-overview.md`；**只有无依赖关系的 Phase 才可并行**（如 W3 的项目域/合同域 CRUD）；
- 并行 Phase 的**文件集必须不相交**（同模块同包的改动禁止并行）；
- 并行 worker 各自在独立 feature 分支工作，主控按完成顺序合并，后者先 rebase；
- **并行 worker 一律不执行 git 写操作**（不 commit/push）：共享工作区下并发 git 操作会互相锁 `.git/index.lock`，由主控验收后统一提交（2026-11-25 一阶段实践验证）。

## 7. 常见坑（提前规避）

| 坑 | 规避 |
| --- | --- |
| worker 幻觉基座 API | 上下文包里明确「先读基座源码再写」（AGENTS §6.3），并给出具体参考路径 |
| worker 顺手重构基座 | 提示词写明「基座只读」；主控验收看 diff 范围 |
| 16G 内存起全栈 OOM | 提示词里带上「先看 docs/ops/middleware-limits.md，分批启动」 |
| worker 提交了密钥 | 验收红线检查 + `.gitignore` 兜底 + 发现即返工重写 |
| Phase 间上下文断裂 | 前置 Phase 产出物路径写进上下文包；进度日志留痕 |
| **测试「假绿」：依赖 Redis/MQ 的集成测试静默跳过** | 通报要求：**汇报必须写清 `Tests run / Skipped / Errors` 三个数字**，且 skip 数要能解释（lessons L3-16）；主控复核看 `target/surefire-reports/*.txt`，不只看 `BUILD SUCCESS` |
| **worker 把「不确定是否生效」写成「已完成」** | 要求关键结论附**可复现的原始证据**（SQL 输出 / curl 响应体 / surefire 数字），而非结论性描述 |
