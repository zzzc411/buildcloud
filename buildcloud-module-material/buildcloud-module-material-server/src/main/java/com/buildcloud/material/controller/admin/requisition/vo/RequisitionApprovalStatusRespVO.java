package com.buildcloud.material.controller.admin.requisition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 材料申领审批状态 Response VO
 *
 * 对应 {@code GET /buildcloud-material/requisition/approval/status}。
 *
 * ========== 为什么一次性返回「审批状态 + 业务状态 + 库存快照 + 明细退料进度」 ==========
 *
 * 前端用这一个接口就能决定所有按钮的可用性并刷新整页，不必再打 3-4 个接口：
 * <pre>
 * 审批状态 -1/2/3/4 且业务状态 0（草稿） → 「提交审批」
 * 审批状态 1（审批中）                   → 「审批中」+ 会签人列表（显示还差谁）
 * 业务状态 2（已扣减）                   → 「出库」
 * 业务状态 3/5（已出库/部分回退）         → 「退料」（每行显示可退量）
 * 业务状态 4（已完成）/9（已取消）        → 只读
 * </pre>
 * 与 W3 的 {@code ContractApprovalStatusRespVO} 同构：审批状态与业务状态正交，
 * **两个都要返回**，否则前端得自己按业务状态反推审批进度（两处口径必然漂移）。
 *
 * ========== 关于 items 里的 returnedQty / returnableQty ==========
 *
 * 退料是「按行退」的，用户填退料量时必须看到每行**还能退多少**。
 * 可退量由服务端算好返回（returnableQty = quantity - returnedQty），前端直接展示；
 * 但**最终裁决仍在服务端**（退料时用原子条件更新 returned_qty 做并发防线），
 * 前端显示的只是快照值，不参与校验决策——避免「前端算的与服务端算的不一致」这类经典争议。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领审批状态 Response VO")
@Data
public class RequisitionApprovalStatusRespVO {

    @Schema(description = "申领单编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "申领单号", example = "REQ-20261128-A3F2C1")
    private String reqNo;

    @Schema(description = "所属项目编号", example = "1")
    private Long projectId;

    @Schema(description = "流程实例编号（未发起过审批时为空）",
            example = "requisition-approve:1:8f1d2c3b-....")
    private String processInstanceId;

    @Schema(description = "审批状态：-1-未开始 1-审批中 2-审批通过 3-审批不通过 4-已取消，见 RequisitionApprovalStatusEnum",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer approvalStatus;

    @Schema(description = "审批状态名（如「审批中」）", example = "审批中")
    private String approvalStatusName;

    @Schema(description = "会签审批人用户编号列表（按提交时的顺序；未发起审批时为空）", example = "[1, 100]")
    private List<Long> approverUserIds;

    @Schema(description = "申领业务状态：0-草稿 1-已提交 2-已扣减 3-已出库 4-已完成 5-部分回退 8-已关闭 9-已取消",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
    private Integer status;

    @Schema(description = "申领业务状态名（如「已扣减」）", example = "已扣减")
    private String statusName;

    @Schema(description = "实际出库时间（已出库后才有值）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime actualOutTime;

    @Schema(description = "最近一次退料时间（发生过退料后才有值）")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime actualReturnTime;

    @Schema(description = "面向操作者的补充说明", example = "会签已通过，库存已扣减，可出库")
    private String reason;

    @Schema(description = "申领明细（含累计已退料量与可退量）")
    private List<Item> items;

    /**
     * 明细行（含退料进度）
     */
    @Schema(description = "申领明细行（含退料进度）")
    @Data
    public static class Item {

        @Schema(description = "明细编号", example = "1")
        private Long id;

        @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        private Long materialId;

        @Schema(description = "申领数量（正数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10.0000")
        private BigDecimal quantity;

        @Schema(description = "累计已退料量", example = "3.0000")
        private BigDecimal returnedQty;

        @Schema(description = "可退量（= quantity - returnedQty；仅已出库/部分回退时有意义）", example = "7.0000")
        private BigDecimal returnableQty;

    }

}
