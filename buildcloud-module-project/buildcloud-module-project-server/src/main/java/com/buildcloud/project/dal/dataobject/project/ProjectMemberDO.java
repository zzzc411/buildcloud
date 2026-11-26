package com.buildcloud.project.dal.dataobject.project;

import cn.iocoder.yudao.framework.tenant.core.db.TenantBaseDO;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 项目成员 DO
 *
 * 表 bc_project_member 对应 spec v1.2 §5.1「项目子域」成员实体：项目/用户/项目内角色。
 *
 * 它是**数据权限第二维度（成员白名单）**的载体：唯一键 uk_project_user(tenant_id, project_id, user_id)
 * 保证「同一用户在同一项目内只有一条记录」，角色变更走 UPDATE 不新增行——否则白名单会出现同一项目重复行。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id 列。
 *
 * @author buildcloud
 */
@TableName("bc_project_member")
@KeySequence("bc_project_member_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMemberDO extends TenantBaseDO {

    /**
     * 成员编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属项目（bc_project.id）
     */
    private Long projectId;

    /**
     * 用户编号（system_users.id）
     *
     * 反向索引 idx_user_id 服务「某用户参与哪些项目」的白名单判定
     */
    private Long userId;

    /**
     * 项目内角色：1-项目经理 2-技术负责人 3-施工员 4-安全员 5-材料员 9-其他
     *
     * 枚举 {@link com.buildcloud.project.enums.project.ProjectRoleEnum}
     */
    private Integer projectRole;

    /**
     * 备注
     */
    private String remark;

}
