package com.buildcloud.material.controller.admin.requisition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 材料申领单 Response VO（含明细）
 *
 * 明细随单据一起返回（详情页一次取全），不做「先查单再查明细」的两跳：
 * 申领明细行数很少（一单通常 1-10 行），一次多查一次 SQL 的收益远大于多一次往返。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领单 Response VO")
@Data
public class RequisitionRespVO {

    @Schema(description = "申领单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "申领单号", requiredMode = Schema.RequiredMode.REQUIRED, example = "REQ-20261127-000123")
    private String reqNo;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

    @Schema(description = "申请人编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long applicantUserId;

    @Schema(description = "申领状态：0-草稿 1-已提交 2-已扣减 3-已出库 4-已完成 5-部分回退 8-已关闭 9-已取消",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer status;

    @Schema(description = "超时关闭时间（提交时 = 提交时间 + 30 分钟，W5 延迟消息据此关单）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime timeoutTime;

    @Schema(description = "审批状态：-1-未开始 1-审批中 2-通过 3-不通过 4-已取消", example = "-1")
    private Integer approvalStatus;

    @Schema(description = "审批流程实例编号")
    private String processInstanceId;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间", requiredMode = Schema.RequiredMode.REQUIRED)
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

    @Schema(description = "申领明细")
    private List<Item> items;

    /**
     * 明细行
     */
    @Schema(description = "申领明细行")
    @Data
    public static class Item {

        @Schema(description = "明细编号", example = "1")
        private Long id;

        @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        private Long materialId;

        @Schema(description = "申领数量（正数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10.0000")
        private BigDecimal quantity;

        @Schema(description = "备注")
        private String remark;

    }

}
