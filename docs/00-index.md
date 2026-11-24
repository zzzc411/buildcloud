# 筑云 · 文档中心索引

> 本文件是 `docs/` 的唯一入口。新文档必须在此登记；未登记的文档视为不存在。

## 目录结构

```
docs/
├── 00-index.md               # 本文件：文档总入口与登记表
├── spec/                     # 总规格（范围/架构/里程碑的唯一依据）
│   └── buildcloud-spec-v1.0.md
├── plans/                    # 从 spec 拆分出的开发计划（按周/按专项）
│   ├── 00-overview.md        # 拆分总览与状态跟踪
│   ├── _template.md          # 计划模板（所有计划从这里复制）
│   └── plan-w{N}-{scope}.md  # 具体计划
├── decisions/                # ADR 决策记录（spec §11 之后的新决策）
├── architecture/             # 架构图、ER 图、读码笔记（W1 起产出）
├── testing/                  # 压测报告、评测集与测试方案
├── ops/                      # 部署手册、中间件编排、云服务器上线记录
└── archive/                  # 历史版本归档（只进不改）
```

## 核心文档（必读顺序）

| 顺序 | 文档 | 说明 |
| --- | --- | --- |
| 1 | [spec v1.0](spec/buildcloud-spec-v1.0.md) | 项目宪法：范围、架构、里程碑、决策记录 |
| 2 | [plans/00-overview.md](plans/00-overview.md) | spec 拆分出的计划清单与进度 |
| 3 | [plans/_template.md](plans/_template.md) | 开发计划的标准结构 |
| 4 | 根目录 `README.md` | 项目骨架与快速开始 |
| 5 | 根目录 `AGENTS.md` | 人与智能体共同遵守的开发约束 |

## 文档规则

1. **单一事实来源**：范围/排期/里程碑只看 spec；spec 变更须升版本号并在 §14 变更记录留痕，旧版归档进 `archive/`；
2. **计划拆分**：进入某周/某专项开发前，先从 `_template.md` 复制出 `plan-w{N}-{scope}.md`，在其中把 spec 对应章节细化为可执行任务；计划只细化不扩权——**范围变更必须回到 spec**（升版本或记 ADR）；
3. **登记制**：新建文档后在本文末尾登记表追加一行（路径、日期、状态）；
4. **命名**：全小写-连字符（kebab-case）；日期用 `YYYY-MM-DD`；版本号 `v{major}.{minor}`；
5. **归档**：被取代的文档移入 `archive/`，命名追加 `-raw` 或 `-v{N}` 后缀，不删除；
6. **语言**：面向自己的工作文档用中文即可，最终对外交付物（README、压测报告）中文为主。

## 文档登记表

| 路径 | 日期 | 状态 | 说明 |
| --- | --- | --- | --- |
| `spec/buildcloud-spec-v1.0.md` | 2026-11-24 | 生效 | 总规格 v1.0（已确认开工） |
| `plans/00-overview.md` | 2026-11-24 | 生效 | 计划拆分总览 |
| `plans/_template.md` | 2026-11-24 | 生效 | 计划模板 |
| `plans/plan-w0-repo-bootstrap.md` | 2026-11-24 | 已完成 | W0：仓库与文档体系初始化（远程 main = 35a8915） |
| `decisions/000-template.md` | 2026-11-24 | 生效 | ADR 模板 |
| `archive/spec-v0.9-raw.md` | 2026-11-24 | 归档 | spec v0.9 原稿 |
