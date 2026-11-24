# 开发计划：plan-w0-repo-bootstrap（仓库与文档体系初始化）

| 项 | 内容 |
| --- | --- |
| 计划编号 | plan-w0-repo-bootstrap |
| 对应 spec 章节 | §4.1（基座获取与仓库落地）/ §7.4（文档与仓库管理规范）/ D20/D21（仓库公开、本地工作区） |
| 状态 | 进行中 |
| 计划开始 | 2026-11-24 |
| 计划完成 | 2026-11-30（W1 开工前） |
| 前置依赖 | 无（spec v1.0 已定稿） |

## 1. 目标（一句话）

公开仓库 `github.com/zzzc411/buildcloud` 建成「文档驱动」的空仓库：spec v1.0 + docs 管理体系 + README/AGENTS 就位，任何人（人或智能体）克隆后即可按文档开工。

## 2. 任务清单

| # | 任务 | 预估 | 产出 | 状态 |
| --- | --- | --- | --- | --- |
| 1 | spec v0.9 → v1.0 收敛（吸收 D15-D21） | 0.5d | `docs/spec/buildcloud-spec-v1.0.md` | ✅ |
| 2 | docs 目录规范与索引 | 0.5d | `docs/00-index.md` + plans/decisions 等子目录 | ✅ |
| 3 | 计划拆分体系 | 0.5d | `plans/00-overview.md` + `_template.md` | ✅ |
| 4 | README（项目骨架） | 0.5d | 根 `README.md` | ✅ |
| 5 | AGENTS（智能体共同约束） | 0.5d | 根 `AGENTS.md` | ✅ |
| 6 | git init + 首次提交 + 推送公开仓库 | 0.5d | 远程 main 分支 | ☐ |
| 7 | .gitignore / 分支保护自检 | 0.2d | 忽略规则（IDE/日志/本地配置） | ☐ |

## 3. 验收标准（出口条件）

- [x] spec v1.0 定稿，Q1-Q7 全部确认并记录（§12）；
- [x] docs 索引/计划模板/总览/ADR 模板就位并互相链接；
- [x] README 写明项目定位、目录骨架、技术栈、快速开始占位、路线图；
- [x] AGENTS.md 写明人与智能体共同约束（提交规范、密钥红线、文档纪律、测试要求）；
- [ ] `git push origin main` 成功，GitHub 页面可见完整文档树；
- [ ] W2 前置：基座裁剪的 W2 计划可直接从模板创建（依赖已就绪）。

## 4. 涉及范围

- 代码模块：无（纯文档与仓库初始化，不引入基座代码——基座在 W2 裁剪时进入）；
- 数据库/SQL：无；
- 配置/Nacos：无；
- 文档：本计划全部产出 + `docs/00-index.md` 登记表。

## 5. 风险与回退

| 风险 | 对策 |
| --- | --- |
| 公开仓库含敏感信息 | 本计划不提交任何密钥/服务器信息；后续 plan-sp-key-management 专门治理（spec §4.3.8） |
| 历史含大文件/误提交 | 首次提交前自查 `git status`；误提交未推送前 `git commit --amend` |

## 6. 进度日志

- 2026-11-24：spec v1.0 收敛完成；docs 体系（00-index / plans 总览+模板 / ADR 模板）就绪；README、AGENTS.md 编写中。
