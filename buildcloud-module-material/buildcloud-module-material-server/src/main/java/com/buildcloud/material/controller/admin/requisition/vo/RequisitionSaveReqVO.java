package com.buildcloud.material.controller.admin.requisition.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 管理后台 - 材料申领单新增/修改 Request VO
 *
 * ========== 为什么只做「新建草稿」，不开放明细编辑接口 ==========
 *
 * 本 Phase（W4-P4，V1 同步链路）的验收范围是「建单 → 提交扣减 → 取消回补」，
 * 明细编辑（已提交后改数量）在 P5 由「取消重建」表达——因为单据一旦扣减过库存，
 * 直接改明细就必须做「差异回补」，那是比取消重建复杂得多的账务动作，且 P5 的翻工风险高。
 * 因此本 VO 只在 create 时全量写入明细。
 *
 * ========== 校验分两层（与 P2/P3 同口径） ==========
 *
 * 1. Bean Validation（本类注解）：必填/正数/精度/非空明细——能在进 Service 前拦的就不查库；
 * 2. Service 业务校验：项目/物料存在性、库存行存在性、可用量是否足够（需查库且在事务内）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领单新增 Request VO")
@Data
public class RequisitionSaveReqVO {

    @Schema(description = "申领单编号（主键，新增时必须为空）", example = "1")
    private Long id;

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属项目不能为空")
    private Long projectId;

    /**
     * 申请人（可选）
     *
     * 生产链路取登录态（{@code SecurityFrameworkUtils#getLoginUserId()}），本字段是**兜底与测试入口**：
     * - 管理端代录（管理员替现场人员建单，申请人不是当前登录用户）；
     * - 单测/压测脚本无登录态时显式指定。
     * 两者都为空时 Service 抛参数异常，不允许落一条「无申请人」的单据（追责口径要求）。
     */
    @Schema(description = "申请人编号（不传则取登录用户）", example = "1")
    private Long applicantUserId;

    @Schema(description = "备注", example = "3 号楼主体结构用钢")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

    @Schema(description = "申领明细（至少一行）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "申领明细不能为空")
    @Valid
    private List<Item> items;

    /**
     * 明细行（嵌套 VO）
     *
     * 只暴露 materialId / quantity / remark 三个字段：明细的 id / requisitionId 由服务端填，
     * 不允许前端指定（否则可以跨单篡改别人的明细行）。
     */
    @Schema(description = "申领明细行")
    @Data
    public static class Item {

        @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "明细中的物料不能为空")
        private Long materialId;

        /**
         * 申领数量（必须 > 0）
         *
         * 只允许正数：明细描述「申请多少」，正负号是流水（bc_stock_log.quantity）的口径，
         * 两者不要混用（DDL 注释同口径）。scale 用 4 位对齐 decimal(18,4)。
         */
        @Schema(description = "申领数量（必须大于 0，最多 4 位小数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "10.0000")
        @NotNull(message = "申领数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "申领数量必须大于 0")
        @Digits(integer = 14, fraction = 4, message = "申领数量整数位不能超过 14 位、小数位不能超过 4 位")
        private BigDecimal quantity;

        @Schema(description = "备注", example = "急用")
        @Size(max = 500, message = "备注长度不能超过 500 个字符")
        private String remark;

    }

}
