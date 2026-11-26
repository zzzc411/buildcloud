package com.buildcloud.project.framework.datapermission.core;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.test.core.ut.BaseMockitoUnitTest;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.framework.test.core.util.RandomUtils.randomPojo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectDataPermissionRule} 的单元测试
 *
 * 覆盖点（对齐 spec §5.1「数据权限：部门 + 项目成员白名单双维度」）：
 * 1. 无登录用户 / 非 ADMIN 用户 → 不拼条件（定时任务、内部调用不拦截）；
 * 2. all=true（超管/全部权限）→ 不拼条件；
 * 3. 三个维度各自单独出现时 SQL 片段正确；
 * 4. 维度组合为 OR（不是 AND）——这是本规则与基座 DeptDataPermissionRule 的**关键差异**；
 * 5. 成员白名单子查询包含 deleted = 0 与 tenant_id 条件（防已移除成员/跨租户越权）；
 * 6. 无任何维度可用时退化为 null = null（查不到数据，而不是放出全量）。
 *
 * @author buildcloud
 */
class ProjectDataPermissionRuleTest extends BaseMockitoUnitTest {

    /**
     * 被测规则：直接 new + 注入 mock 的 permissionApi（不用 @InjectMocks，
     * 因为构造器只有一个参数，Mockito 推断足够稳，但显式构造更易读）
     */
    private ProjectDataPermissionRule rule;

    private PermissionCommonApi permissionApi;

    private void initRule() {
        permissionApi = mock(PermissionCommonApi.class);
        rule = new ProjectDataPermissionRule(permissionApi);
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test
    void testGetTableNames_onlyProjectTable() {
        initRule();
        // 断言：只对 bc_project 生效（里程碑/成员表不设数据权限，一律经项目维度访问）
        assertEquals(1, rule.getTableNames().size());
        assertTrue(rule.getTableNames().contains("bc_project"));
    }

    @Test // 无 LoginUser：不拦截
    void testGetExpression_noLoginUser() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(null);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言：不拼条件
            assertNull(expression);
        }
    }

    @Test // 非 ADMIN 类型用户：不拦截
    void testGetExpression_notAdminUserType() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = randomPojo(LoginUser.class,
                    o -> o.setId(1L).setUserType(UserTypeEnum.MEMBER.getValue()));
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言
            assertNull(expression);
        }
    }

    @Test // all=true：全部数据权限，不拼条件
    void testGetExpression_allPermission() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(1L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            permission.setAll(true);
            when(permissionApi.getDeptDataPermission(same(1L))).thenReturn(success(permission));

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言
            assertNull(expression);
        }
    }

    @Test // 只有部门维度（deptIds 非空、self=false）
    void testGetExpression_deptOnly() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(1L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            permission.setDeptIds(CollUtil.newLinkedHashSet(100L, 101L));
            when(permissionApi.getDeptDataPermission(same(1L))).thenReturn(success(permission));
            TenantContextHolder.setTenantId(1L);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言：部门 IN + 成员白名单子查询（白名单维度恒生效，故这里应有两个维度）
            String sql = expression.toString();
            assertTrue(sql.contains("p.dept_id IN (100, 101)"), "实际 SQL：" + sql);
            assertTrue(sql.contains("p.id IN (SELECT project_id FROM bc_project_member"), "实际 SQL：" + sql);
            assertTrue(sql.contains("user_id = 1"), "实际 SQL：" + sql);
            // 组合必须是 OR（部门 ∪ 白名单），不能是 AND
            assertTrue(sql.contains(" OR "), "实际 SQL：" + sql);
        }
    }

    @Test // self=true：项目经理本人维度出现
    void testGetExpression_selfDimension() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(7L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            permission.setSelf(true);
            when(permissionApi.getDeptDataPermission(same(7L))).thenReturn(success(permission));
            TenantContextHolder.setTenantId(1L);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言：manager_user_id = 7 + 成员白名单，二者 OR
            String sql = expression.toString();
            assertTrue(sql.contains("p.manager_user_id = 7"), "实际 SQL：" + sql);
            assertTrue(sql.contains("p.id IN (SELECT project_id FROM bc_project_member"), "实际 SQL：" + sql);
            assertTrue(sql.contains(" OR "), "实际 SQL：" + sql);
        }
    }

    @Test // 成员白名单子查询必须带 deleted = 0 与 tenant_id
    void testGetExpression_memberSubQueryHasDeletedAndTenant() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(5L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            permission.setDeptIds(CollUtil.newLinkedHashSet(100L));
            when(permissionApi.getDeptDataPermission(same(5L))).thenReturn(success(permission));
            TenantContextHolder.setTenantId(3L);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言：子查询显式带逻辑删除与租户条件（这段 SQL 由我们手写，插件不会改写它）
            String sql = expression.toString();
            assertTrue(sql.contains("deleted = 0"), "实际 SQL：" + sql);
            assertTrue(sql.contains("tenant_id = 3"), "实际 SQL：" + sql);
            assertTrue(sql.contains("user_id = 5"), "实际 SQL：" + sql);
        }
    }

    @Test // 无部门、无 self：仍应有成员白名单维度（不能退化成 100% 无权限）
    void testGetExpression_memberOnly() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(9L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            // deptIds 空 + self=false
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            when(permissionApi.getDeptDataPermission(same(9L))).thenReturn(success(permission));
            TenantContextHolder.setTenantId(1L);

            // 调用
            Expression expression = rule.getExpression("bc_project", new Alias("p"));
            // 断言：只剩白名单维度，且**没有**退化成 null = null
            assertTrue(expression != null);
            String sql = expression.toString();
            assertTrue(sql.contains("p.id IN (SELECT project_id FROM bc_project_member"), "实际 SQL：" + sql);
            assertEquals(false, sql.contains("null = null"), "实际 SQL：" + sql);
        }
    }

    @Test // 无 alias 时列名用表名限定
    void testGetExpression_noAlias() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(1L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            DeptDataPermissionRespDTO permission = new DeptDataPermissionRespDTO();
            permission.setDeptIds(CollUtil.newLinkedHashSet(100L));
            when(permissionApi.getDeptDataPermission(same(1L))).thenReturn(success(permission));
            TenantContextHolder.setTenantId(1L);

            // 调用
            Expression expression = rule.getExpression("bc_project", null);
            // 断言：别名缺失时用表名限定，不会生成 "null.dept_id" 这种坏 SQL
            String sql = expression.toString();
            assertTrue(sql.contains("bc_project.dept_id IN (100)"), "实际 SQL：" + sql);
            assertTrue(sql.contains("bc_project.id IN (SELECT project_id"), "实际 SQL：" + sql);
        }
    }

    @Test // 数据权限接口返回空（null data）→ 抛 NPE，绝不静默放开全量
    void testGetExpression_permissionNull_throws() {
        initRule();
        try (MockedStatic<SecurityFrameworkUtils> mocked = mockStatic(SecurityFrameworkUtils.class)) {
            LoginUser loginUser = buildAdminLoginUser(1L);
            mocked.when(SecurityFrameworkUtils::getLoginUser).thenReturn(loginUser);
            when(permissionApi.getDeptDataPermission(same(1L))).thenReturn(success(null));

            // 调用，并断言异常
            assertThrows(NullPointerException.class,
                    () -> rule.getExpression("bc_project", new Alias("p")));
        }
    }

    // ========== 测试数据构造 ==========

    private LoginUser buildAdminLoginUser(Long userId) {
        return randomPojo(LoginUser.class, o -> o.setId(userId).setUserType(UserTypeEnum.ADMIN.getValue()));
    }

}
