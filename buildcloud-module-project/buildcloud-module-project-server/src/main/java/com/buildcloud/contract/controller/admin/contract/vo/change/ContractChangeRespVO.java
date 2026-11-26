package com.buildcloud.contract.controller.admin.contract.vo.change;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 合同变更签证 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同变更签证 Response VO")
@Data
public class ContractChangeRespVO {

    @Schema(description = "变更编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long contractId;

    @Schema(description = "变更类型：1-设计变更 2-现场签证 3-索赔 9-其他", example = "1")
    private Integer type;

    @Schema(description = "增减金额（元）：正数=增项，负数=减项", example = "5000000.00")
    private BigDecimal changeAmount;

    @Schema(description = "变更原因")
    private String changeReason;

    @Schema(description = "关联审批单号")
    private String approvalNo;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
