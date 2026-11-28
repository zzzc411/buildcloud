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
 * 管理后台 - 材料申领退料 Request VO
 *
 * 对应 {@code POST /buildcloud-material/requisition/return}。
 * 请求体形如：
 * <pre>
 * POST /admin-api/buildcloud-material/requisition/return
 * { "requisitionId": 17, "items": [ { "materialId": 1, "quantity": 5.0000 }, { "materialId": 2, "quantity": 1.5 } ] }
 * </pre>
 *
 * ========== 为什么退料只传 materialId，不传明细行 id ==========
 *
 * 明细行的 id 是内部标识，现场人员（或扫码枪）只认「料号」。传 materialId 后由 Service
 * 在**本单的明细**里定位那一行（{@code REQUISITION_RETURN_ITEM_NOT_IN_REQUISITION} 兜住「退没领过的料」），
 * 既对使用者友好，也天然杜绝「A 单的明细行 id 传给 B 单」这类越权改数的可能。
 *
 * ========== 为什么必须有 items（不允许「一键全退」） ==========
 *
 * 「全退」看起来更省事，但实际场景里「部分退回」才是主流（用剩几包水泥退回仓库），
 * 而且「一键全退」与「全部行的可退量」两种交互在前端容易给出不一致的结果。
 * 这里要求调用方显式列出每一行与数量，**全退 = 把所有行按可退量列一遍**——
 * 数学上与「部分退」是同一条路径，不需要两套逻辑（少写一套就少一类缺陷）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料申领退料 Request VO")
@Data
public class RequisitionReturnReqVO {

    @Schema(description = "申领单编号（bc_requisition.id，必须处于已出库/部分回退）",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "17")
    @NotNull(message = "申领单编号不能为空")
    private Long requisitionId;

    @Schema(description = "退料明细（至少一行；同一物料不可重复）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "退料明细不能为空，请至少填写一行退料数量")
    @Valid
    private List<Item> items;

    /**
     * 退料明细行
     *
     * 只暴露 materialId / quantity / remark：明细行 id 不允许前端指定（见类注释），
     * 行定位完全由 Service 在本单明细内完成。
     */
    @Schema(description = "退料明细行")
    @Data
    public static class Item {

        @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        @NotNull(message = "退料明细中的物料不能为空")
        private Long materialId;

        /**
         * 本次退料数量（必须 > 0，最多 4 位小数）
         *
         * 精度与申领数量、库存 available_qty 统一为 decimal(18,4)：
         * 若这里允许 2 位、库存 4 位，「退完还有 0.0001 没退、单据永远停在部分回退」的幽灵差异就会出现。
         */
        @Schema(description = "本次退料数量（必须大于 0，最多 4 位小数）",
                requiredMode = Schema.RequiredMode.REQUIRED, example = "5.0000")
        @NotNull(message = "退料数量不能为空")
        @DecimalMin(value = "0", inclusive = false, message = "退料数量必须大于 0")
        @Digits(integer = 14, fraction = 4, message = "退料数量整数位不能超过 14 位、小数位不能超过 4 位")
        private BigDecimal quantity;

        @Schema(description = "备注", example = "现场用剩退回仓库")
        @Size(max = 500, message = "备注长度不能超过 500 个字符")
        private String remark;

    }

}
