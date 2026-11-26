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
 * 项目里程碑 DO
 *
 * 表 bc_project_milestone 对应 spec v1.2 §5.1「项目子域」里程碑实体：名称/计划与实际日期/权重/状态；
 * 另含工程补充字段 sort（同项目内展示顺序，甘特图/进度看板主路径）。
 *
 * 继承 {@link TenantBaseDO}（AGENTS R-8）：表含 tenant_id 列，基座多租户插件只对该基类的子类
 * 自动追加 tenant_id 条件；审计字段（creator/create_time/updater/update_time/deleted）仍由 BaseDO 提供。
 *
 * @author buildcloud
 */
@TableName("bc_project_milestone")
@KeySequence("bc_project_milestone_seq") // 用于 Oracle、PostgreSQL、Kingbase、DB2、H2 数据库的主键自增；MySQL 走 AUTO_INCREMENT
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMilestoneDO extends TenantBaseDO {

    /**
     * 里程碑编号（主键）
     */
    @TableId
    private Long id;

    /**
     * 所属项目（bc_project.id）
     *
     * 里程碑列表一律按项目维度查询（idx_project_plan_date 主路径），不提供跨项目的全量列表
     */
    private Long projectId;

    /**
     * 里程碑名称
     */
    private String milestoneName;

    /**
     * 计划完成日期
     */
    private LocalDate planDate;

    /**
     * 实际完成日期
     */
    private LocalDate actualDate;

    /**
     * 权重（百分比 0.00~100.00）
     *
     * 用 decimal(5,2) 而非整数：进度百分比 = SUM(已完成里程碑 weight) / SUM(全部 weight)，
     * 需要小数精度才能表达 12.5% 这类权重；同项目内建议合计 100。
     */
    private BigDecimal weight;

    /**
     * 里程碑状态：0-未开始 1-进行中 2-已完成 3-已延期
     *
     * 枚举 {@link com.buildcloud.project.enums.project.ProjectMilestoneStatusEnum}
     */
    private Integer status;

    /**
     * 排序号（同项目内升序展示）
     */
    private Integer sort;

    /**
     * 备注
     */
    private String remark;

}
