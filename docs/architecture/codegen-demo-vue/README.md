# 代码生成器四件套示例（bc_project）

本目录存放 W2 阶段用**基座 infra 代码生成器**对业务表 `bc_project`（项目主表）生成后、**人工精修**过的
前端产物，作为自研模块后续建表/建页面的风格基线（AGENTS.md §6.5：生成代码必须精修后才算交付）。

## 为什么单独放这里

`yudao-ui-admin-vue3` 前端工程当前**未入仓**（W2 的 `docker/` 与后端裁剪不包含前端源码；管理端暂用
yudao-demo 预编译版）。为了让"生成器四件套"这一验收项在仓库内**可查、可对比、可复用**，把
前端产物随文档归档在此，而不散落到仓库根。

> W3 起前端工程正式入仓时，把本目录按下面的目标路径拷过去即可，无需改动内容结构。

## 文件与目标路径

| 本目录文件 | 前端工程内目标路径 | 说明 |
| --- | --- | --- |
| `index.vue` | `src/views/project/project/index.vue` | 项目台账列表页（搜索表单 + 表格 + 分页 + 增删改入口） |
| `ProjectForm.vue` | `src/views/project/project/ProjectForm.vue` | 新增/编辑弹窗表单 |
| `index.ts` | `src/api/project/project/index.ts` | 接口定义（`request` 封装，基座 yudao-ui-admin-vue3 风格） |

## 四个产物与各自落点（完整四件套）

| 件 | 落点 | 生成方式 |
| --- | --- | --- |
| Java（DO/Mapper/Service/Controller/VO/错误码） | `buildcloud-module-project/buildcloud-module-project-server/src/main/java/com/buildcloud/project/**` | 生成器产出后精修（改包名 `cn.iocoder.yudao` → `com.buildcloud`、补唯一校验、收敛查询条件） |
| SQL（建表） | `sql/buildcloud/01-project.sql` | 手写（生成器的 `sql.sql` 只含菜单+按钮 INSERT，见下） |
| SQL（菜单/按钮） | 由 `sql/buildcloud/02-menu.sql` 预置父菜单 9000，页面挂载后按需补按钮 | 生成器产出，父菜单 id 已对齐 |
| 单测 | `buildcloud-module-project/buildcloud-module-project-server/src/test/java/com/buildcloud/project/service/project/ProjectServiceImplTest.java` | 生成器模板产出后精修（补编号唯一用例、去 `@Disabled`） |
| Vue | 本目录 | 生成器产出后精修 |

## 相对生成器原始产物的精修点

生成器原始 zip（`CodegenController#download`）与仓库内最终版差异，逐条列明以便复审：

### Java

1. **包名**：`cn.iocoder.yudao.module.project.*` → `com.buildcloud.project.*`（R-2：基座包名不改，自研代码进 `com.buildcloud`）；
2. **DO 基类**：`extends BaseDO` → `extends TenantBaseDO`。`bc_project` 含 `tenant_id`，基座多租户插件只有在 DO 继承
   `TenantBaseDO` 时才自动拼 `tenant_id` 条件——这是生成器不会替你判断的点，必须人工改；
3. **错误码**：生成器给的是 `ErrorCodeConstants_手动操作.java` 片段 + `TODO 补充编号`，改为
   `com.buildcloud.project.enums.ErrorCodeConstants` 的实值（段位 `1-100-000-xxx`，spec §7.2）；
4. **Service**：补 `project_no` 唯一性校验（对应唯一键 `uk_project_no`），生成器只做裸 CRUD；
5. **Mapper**：收敛查询条件——生成器把每一列都拼成筛选条件（`address`/`remark` 等无索引列等值查询会全表扫），
   精修后只保留台账真实检索维度；
6. **去掉未使用的导入**（生成器模板固定输出的 `CollUtil`、`CollectionUtils.diffList` 等）；
7. **Controller**：去掉本次未启用的 `export-excel`（W6 报表域统一实现，避免 project 服务强依赖 excel starter）。

### Vue

1. 去掉生成器占位的 `<el-option label="请选择字典生成" />`，改为真实枚举选项（与 Java 端 `ProjectTypeEnum` / `ProjectStatusEnum` 对齐）；
2. 搜索栏去掉无索引字段（省/市名称、详细地址、备注等）的模糊输入，只留台账有用条件；
3. 日期列 `value-format` 从 `YYYY-MM-DD HH:mm:ss` 改为 `YYYY-MM-DD`（`bc_project` 的四个日期列是 `date` 类型）；
4. 表格列补齐格式化（金额千分位、状态下标颜色、日期格式化），并给"操作"列最小宽度避免换行。

## 如何重跑生成器（W3 起建新表照做）

```powershell
# 1) 中间件 + 基座 infra/system 已起
powershell -ExecutionPolicy Bypass -File scripts\start-base.ps1 -Services system,infra

# 2) 管理端登录拿 token（注意必须带 tenant-id，否则报"请求的租户标识未传递"）
#    浏览器：http://127.0.0.1:48080  admin / <本地密码>

# 3) 基础设施 → 代码生成 → 导入表 → 选表 → 编辑生成配置（模块名/业务名/类名/上级菜单）
#    → 生成代码 → 下载 zip → 解压

# 4) 按上节"精修点"逐条人工过一遍，再编译 + 跑单测
mvn -B -ntp -pl buildcloud-module-project/buildcloud-module-project-server -am test
```

> 代码生成器基座配置：`yudao.codegen.base-package: cn.iocoder.yudao`（在 infra 的 `application.yaml`）。
> **不要**改成 `com.buildcloud` 去"省掉改名"——那会让 `sql/mysql/` 等基座产物的包路径与配置不一致；
> 自研模块的包名差异在生成后精修时统一处理，这也正是 §6.5 要求的"人工精修"环节。
