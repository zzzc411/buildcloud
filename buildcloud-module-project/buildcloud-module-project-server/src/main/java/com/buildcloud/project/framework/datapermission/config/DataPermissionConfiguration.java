package com.buildcloud.project.framework.datapermission.config;

import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import com.buildcloud.project.framework.datapermission.core.ProjectDataPermissionRule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * project 模块的数据权限 Configuration
 *
 * 做两件事：
 * 1. 注册项目台账的**双维度**规则 {@link ProjectDataPermissionRule}（部门 ∪ 成员白名单）；
 *    基座 {@code DataPermissionRuleFactoryImpl} 通过 {@code List<DataPermissionRule>} 注入自动收录，
 *    因此只要它是一个 Spring Bean 就生效（无需改基座）。
 * 2. 给基座 {@code DeptDataPermissionRule} 补「bc_project 按 dept_id、bc_project_member 按 user_id」
 *    的表字段配置——但**只配 dept_id 维度**会让台账退化成纯部门过滤，故这里**不**给 bc_project 配
 *    addDeptColumn：部门维度由上面的自定义规则一并处理（避免同一维度被两条规则 AND 起来，
 *    变成「必须既在部门又在白名单」，与 spec 要求的 OR 语义相反）。
 *
 * 只给 bc_project_member 配 dept 维度没有意义（成员表没有 dept_id 列），故本模块
 * 不实现 {@code DeptDataPermissionRuleCustomizer}，仅注册自定义规则。
 *
 * @author buildcloud
 */
@Configuration(proxyBeanMethods = false, value = "projectDataPermissionConfiguration")
public class DataPermissionConfiguration {

    /**
     * 项目台账数据权限规则（部门 + 项目经理本人 + 成员白名单，三者 OR）
     *
     * @param permissionApi 基座权限 RPC（取得用户的部门数据权限）
     * @return 规则
     */
    @Bean
    public ProjectDataPermissionRule projectDataPermissionRule(PermissionCommonApi permissionApi) {
        return new ProjectDataPermissionRule(permissionApi);
    }

}
