package com.buildcloud.project.framework.datapermission.core;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.datapermission.core.rule.DataPermissionRule;
import cn.iocoder.yudao.framework.mybatis.core.util.MyBatisUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 项目台账（bc_project）的数据权限规则：**部门 + 项目成员白名单双维度（OR 组合）**
 *
 * 背景（spec v1.2 §5.1）：项目台账的可见性 = 「我是这个部门的人（能看到本部门项目）」
 * ∪ 「我是这个项目的成员（即使跨部门也能看到）」。基座自带的 {@code DeptDataPermissionRule}
 * 只能表达单字段维度（dept_id / user_id），无法表达「id IN (成员表子查询)」这种跨表白名单，
 * 因此这里实现一个独立的 {@link DataPermissionRule}，由基座的
 * {@code DataPermissionRuleFactoryImpl} 自动收录（`List<DataPermissionRule>` 注入），
 * 仍然走基座统一的 SQL 改写链路（DataPermissionInterceptor），不另起一套过滤机制（AGENTS §6.3）。
 *
 * 生成的 WHERE 片段（非超管用户）：
 * <pre>
 * ( bc_project.dept_id IN (?, ?)                    -- 维度一：部门
 *   OR bc_project.manager_user_id = ?                -- 维度二：项目经理=自己（对应基座 dataScope=SELF）
 *   OR bc_project.id IN (SELECT project_id FROM bc_project_member
 *                        WHERE user_id = ? AND deleted = 0 AND tenant_id = ?) )  -- 维度三：成员白名单
 * </pre>
 *
 * 取舍说明：
 * 1. 三个维度之间用 **OR**（任一族可见即可见），维度内部才是 IN 的集合语义——与基座
 *    「部门 IN ? OR user_id = ?」的组合口径一致；
 * 2. `all=true`（超管 / 全部数据权限角色）时不拼任何条件，直接返回 null；
 * 3. 成员子查询里**显式**写上 `deleted = 0` 与 `tenant_id = ?`：这段 SQL 是我们手写的，
 *    逻辑删除与多租户插件不会改写它（它们只作用于 MyBatis-Plus 生成的 SQL），漏写会导致
 *    已移除的成员仍能看项目、或看到别家租户的成员关系；
 * 4. 登录用户为空（如定时任务、内部调用）或非 ADMIN 类型时返回 null，不参与过滤——与基座
 *    {@code DeptDataPermissionRule} 的口径保持一致。
 *
 * @author buildcloud
 */
@Slf4j
public class ProjectDataPermissionRule implements DataPermissionRule {

    /**
     * 本规则生效的表名：仅 bc_project（里程碑/成员表本身不设数据权限，一律经由项目维度访问）
     */
    private static final String PROJECT_TABLE_NAME = "bc_project";

    /**
     * 项目成员表名（白名单子查询的目标表）
     */
    private static final String MEMBER_TABLE_NAME = "bc_project_member";

    private static final String DEPT_COLUMN_NAME = "dept_id";
    private static final String ID_COLUMN_NAME = "id";
    private static final String MANAGER_COLUMN_NAME = "manager_user_id";
    private static final String PROJECT_ID_COLUMN_NAME = "project_id";
    private static final String USER_ID_COLUMN_NAME = "user_id";

    /**
     * LoginUser 的 Context 缓存 Key，避免一次请求内重复 RPC 取数据权限
     *
     * 与 {@code DeptDataPermissionRule} 的 Key 刻意区分：两者在同一个 LoginUser.context
     * 中并存，用不同 Key 才能各自缓存（同一个 DTO 类型，共用 Key 也可，但显式分开更易排查）。
     */
    protected static final String CONTEXT_KEY = ProjectDataPermissionRule.class.getSimpleName();

    private final PermissionCommonApi permissionApi;

    public ProjectDataPermissionRule(PermissionCommonApi permissionApi) {
        this.permissionApi = permissionApi;
    }

    @Override
    public Set<String> getTableNames() {
        return Set.of(PROJECT_TABLE_NAME);
    }

    @Override
    public Expression getExpression(String tableName, Alias tableAlias) {
        // 只有有登陆用户的情况下，才进行数据权限的处理（定时任务/内部调用不拦截）
        LoginUser loginUser = SecurityFrameworkUtils.getLoginUser();
        if (loginUser == null) {
            return null;
        }
        // 只有管理员类型的用户，才进行数据权限的处理
        if (ObjectUtil.notEqual(loginUser.getUserType(), UserTypeEnum.ADMIN.getValue())) {
            return null;
        }

        // 获得数据权限（形如 部门集合 / 是否全部 / 是否仅自己）
        DeptDataPermissionRespDTO deptDataPermission = getDeptDataPermission(loginUser);
        if (deptDataPermission == null) {
            log.error("[getExpression][LoginUser({}) 获取数据权限为 null]", JsonUtils.toJsonString(loginUser));
            throw new NullPointerException(String.format("LoginUser(%d) Table(%s) 未返回数据权限",
                    loginUser.getId(), tableName));
        }
        // 情况一：全部数据权限（含超管），无需拼接条件
        if (Boolean.TRUE.equals(deptDataPermission.getAll())) {
            return null;
        }

        // 情况二：组合三个维度
        List<Expression> expressions = new ArrayList<>(3);
        // 维度 1：部门（本部门 / 自定义部门 / 部门及子部门，由基座 PermissionService 计算好后给集合）
        Expression deptExpression = buildDeptExpression(tableName, tableAlias, deptDataPermission.getDeptIds());
        if (deptExpression != null) {
            expressions.add(deptExpression);
        }
        // 维度 2：项目经理 = 自己（基座 dataScope=SELF 在项目域的语义落点）
        Expression selfExpression = buildSelfExpression(tableName, tableAlias,
                deptDataPermission.getSelf(), loginUser.getId());
        if (selfExpression != null) {
            expressions.add(selfExpression);
        }
        // 维度 3：项目成员白名单（跨部门可见的核心：id IN (成员表子查询)）
        Expression memberExpression = buildMemberExpression(tableName, tableAlias, loginUser.getId());
        if (memberExpression != null) {
            expressions.add(memberExpression);
        }

        // 情况三：三个维度都没落到条件上 → 100% 无权限，用 null = null 保证查不到任何数据
        if (CollUtil.isEmpty(expressions)) {
            return new EqualsTo(null, null);
        }
        // 单维度直接返回（不加多余括号，SQL 更易读）
        if (expressions.size() == 1) {
            return expressions.get(0);
        }
        // 多维度：OR 组合后整体加括号，避免与外层 AND 条件（deleted=0 等）优先级打架
        Expression result = expressions.get(0);
        for (int i = 1; i < expressions.size(); i++) {
            result = new OrExpression(result, expressions.get(i));
        }
        return new ParenthesedExpressionList(result);
    }

    // ========== 各维度条件构建 ==========

    private Expression buildDeptExpression(String tableName, Alias tableAlias, Set<Long> deptIds) {
        if (CollUtil.isEmpty(deptIds)) {
            return null;
        }
        List<LongValue> values = deptIds.stream().map(LongValue::new).toList();
        return new InExpression(MyBatisUtils.buildColumn(tableName, tableAlias, DEPT_COLUMN_NAME),
                new ParenthesedExpressionList<>(values));
    }

    private Expression buildSelfExpression(String tableName, Alias tableAlias, Boolean self, Long userId) {
        if (!Boolean.TRUE.equals(self) || userId == null) {
            return null;
        }
        return new EqualsTo(MyBatisUtils.buildColumn(tableName, tableAlias, MANAGER_COLUMN_NAME),
                new LongValue(userId));
    }

    /**
     * 构建成员白名单条件：id IN (SELECT project_id FROM bc_project_member WHERE user_id = ? AND deleted = 0 AND tenant_id = ?)
     *
     * 用子查询而非「先查成员项目集合再拼 IN (1,2,3)」，理由：
     * 1. 避免「先查一次、再拼超长 IN」的两次往返，且成员量大时 IN 列表会失控；
     * 2. 白名单判定与主查询在同一事务快照内，避免并发加成员后本次查询看不到（不影响正确性，
     *    但会让「刚加完成员却看不到项目」的体验问题变短）；
     * 3. 成员表 user_id 有 idx_user_id 索引，子查询是索引定位后再回 bc_project 主键 IN，
     *    执行计划与手工拼 IN 基本一致（MySQL 会做半连接优化）。
     */
    private Expression buildMemberExpression(String tableName, Alias tableAlias, Long userId) {
        if (userId == null) {
            return null;
        }
        Long tenantId = TenantContextHolder.getTenantId();
        String leftColumn = tableAlias != null ? tableAlias.getName() : tableName;
        StringBuilder sql = new StringBuilder(160);
        sql.append(leftColumn).append('.').append(ID_COLUMN_NAME)
                .append(" IN (SELECT ").append(PROJECT_ID_COLUMN_NAME)
                .append(" FROM ").append(MEMBER_TABLE_NAME)
                .append(" WHERE ").append(USER_ID_COLUMN_NAME).append(" = ").append(userId)
                .append(" AND deleted = 0");
        if (tenantId != null) {
            sql.append(" AND tenant_id = ").append(tenantId);
        }
        sql.append(")");
        // 用 jsqlparser 解析成 Expression，而不是手搓 AST：子查询嵌套层级较深，解析器保证括号与
        // 子树结构正确；userId/tenantId 均为 Long（非外部字符串），无注入面
        return parseExpression(sql.toString());
    }

    private Expression parseExpression(String sql) {
        try {
            return CCJSqlParserUtil.parseCondExpression(sql);
        } catch (JSQLParserException e) {
            // 走到这里说明拼接的 SQL 有语法错误，属编码缺陷：直接抛出，避免静默放出全量数据
            throw new IllegalStateException("构建 bc_project 成员白名单条件失败：" + sql, e);
        }
    }

    // ========== 数据权限获取 ==========

    private DeptDataPermissionRespDTO getDeptDataPermission(LoginUser loginUser) {
        DeptDataPermissionRespDTO cached = loginUser.getContext(CONTEXT_KEY, DeptDataPermissionRespDTO.class);
        if (cached != null) {
            return cached;
        }
        DeptDataPermissionRespDTO permission = permissionApi.getDeptDataPermission(loginUser.getId()).getCheckedData();
        if (permission != null) {
            loginUser.setContext(CONTEXT_KEY, permission);
        }
        return permission;
    }

}
