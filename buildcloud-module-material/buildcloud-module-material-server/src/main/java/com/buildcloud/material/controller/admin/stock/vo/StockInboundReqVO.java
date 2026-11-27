package com.buildcloud.material.controller.admin.stock.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 材料入库 Request VO
 *
 * 入参故意只给「项目 + 物料 + 数量 + 备注」四个字段（任务口径）：
 * 库存行的 available_qty / locked_qty / version 一律由服务端推导，不允许前端传入——
 * 否则前端可以「顺手」把 locked_qty 改掉，绕过预扣语义（spec §5.2）。
 *
 * 数量校验分两层：
 * 1. Bean Validation（本类）：必填、> 0、最多 4 位小数（与 decimal(18,4) 对齐）；
 * 2. Service 业务校验：同一条规则再查一次，防「内部代码直接调 Service 绕过 VO 校验」，
 *    并把错误码统一到 1-102-040-002，避免前端拿到框架校验异常与业务异常两种口径。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 材料入库 Request VO")
@Data
public class StockInboundReqVO {

    @Schema(description = "所属项目编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属项目不能为空")
    private Long projectId;

    @Schema(description = "物料编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "物料不能为空")
    private Long materialId;

    /**
     * 入库数量（必须 > 0）
     *
     * 只允许正向增量：减少库存必须走申领扣减/退回/冲正各自的业务链路（biz_type 2/3/4），
     * 否则流水的 biz_type 会失去意义、无法按类型分组对账。
     * scale 用 4 位对齐 decimal(18,4)，避免「0.00005」这类超精度值被数据库静默截断后
     * 与请求值不一致（截断后可能变成 0，等于一次「空入库」却写了一条流水）。
     */
    @Schema(description = "入库数量（必须大于 0，最多 4 位小数）", requiredMode = Schema.RequiredMode.REQUIRED, example = "100.0000")
    @NotNull(message = "入库数量不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "入库数量必须大于 0")
    @Digits(integer = 14, fraction = 4, message = "入库数量整数位不能超过 14 位、小数位不能超过 4 位")
    private BigDecimal quantity;

    @Schema(description = "备注", example = "首批钢筋进场")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
