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

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 项目 DO
 *
 * 表 bc_project 对应 spec v1.2 §5.1「项目子域」实体：编号/名称/类型/状态/省市/建设单位/合同额/
 * 计划与实际开竣工/项目经理/所属部门。
 *
 * 继承 {@link TenantBaseDO}（而非直接继承 BaseDO）的原因：bc_project 含 tenant_id 列，
 * 基座多租户插件（starter-biz-tenant）在 SQL 层自动追加 tenant_id 条件的前提是 DO 继承 TenantBaseDO，
 * 这样审计字段（creator/create_time/updater/update_time/deleted）仍由 BaseDO 提供，
 * tenant_id 由 TenantBaseDO 提供，避免在 DO 里重复声明。
 *
 * 说明：由基座 infra 代码生成器对 bc_project 生成后人工精修——包名改为 com.buildcloud（R-2：基座包名不动），
 * 去掉生成器默认的冗余 import，补齐字段语义注释，状态/类型取值对齐 ProjectStatusEnum / ProjectTypeEnum。
 *
 * @author buildcloud
 */
@TableName("bc_project")
@KeySequence("bc_project_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT，可不生效
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectDO extends TenantBaseDO {

    /**
     * 项目编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 项目编号（业务编码，租户内唯一，见唯一键 uk_project_no）
     */
    private String projectNo;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目类型
     *
     * 枚举 {@link com.buildcloud.project.enums.project.ProjectTypeEnum}
     */
    private Integer projectType;

    /**
     * 项目状态：筹备 → 在建 ⇄ 停工 → 竣工（spec §5.1 状态机）
     *
     * 枚举 {@link com.buildcloud.project.enums.project.ProjectStatusEnum}
     */
    private Integer status;

    /**
     * 省份编码
     */
    private String provinceCode;

    /**
     * 省份名称
     */
    private String provinceName;

    /**
     * 城市编码
     */
    private String cityCode;

    /**
     * 城市名称
     */
    private String cityName;

    /**
     * 详细地址
     */
    private String address;

    /**
     * 建设单位（业主）
     */
    private String buildUnit;

    /**
     * 合同额（元）
     */
    private BigDecimal contractAmount;

    /**
     * 计划开工日期
     */
    private LocalDate planStartDate;

    /**
     * 计划竣工日期
     */
    private LocalDate planEndDate;

    /**
     * 实际开工日期
     */
    private LocalDate actualStartDate;

    /**
     * 实际竣工日期
     */
    private LocalDate actualEndDate;

    /**
     * 项目经理（system_users.id）
     */
    private Long managerUserId;

    /**
     * 所属部门（system_dept.id）
     *
     * 数据权限按部门过滤（基座 biz-data-permission），spec §5.1
     */
    private Long deptId;

    /**
     * 备注
     */
    private String remark;

}
