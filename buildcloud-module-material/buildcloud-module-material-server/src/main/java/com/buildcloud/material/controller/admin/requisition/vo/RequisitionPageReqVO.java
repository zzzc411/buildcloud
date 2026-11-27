package com.buildcloud.material.controller.admin.requisition.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 材料申领单分页 Request VO
 *
 * 检索维度对齐 bc_requisition 的索引（07-material.sql 第 5 节）：
 * - projectId → idx_project_id（项目详情主路径，也是数据权限圈定维度）；
 * - status → idx_status / idx_status_timeout（待办与超时扫描）；
 * - reqNo → uk_req_no（精确查单）。
 *
 * createTime 区间没有建复合索引（只有 idx_create_time 单列），属可接受的回表过滤：
 * 申领单量级远小于流水，且通常已按 projectId 圈定。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领单分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class RequisitionPageReqVO extends PageParam {

    @Schema(description = "申领单号（模糊匹配）", example = "REQ-20261127")
    private String reqNo;

    @Schema(description = "所属项目编号", example = "1")
    private Long projectId;

    @Schema(description = "申请人编号", example = "1")
    private Long applicantUserId;

    @Schema(description = "申领状态：0-草稿 1-已提交 2-已扣减 3-已出库 4-已完成 5-部分回退 8-已关闭 9-已取消", example = "2")
    private Integer status;

    @Schema(description = "审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消", example = "-1")
    private Integer approvalStatus;

    @Schema(description = "创建时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
