package com.buildcloud.contract.controller.admin.contract.vo.paymentplan;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 合同收付款计划 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同收付款计划 Response VO")
@Data
public class ContractPaymentPlanRespVO {

    @Schema(description = "计划编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long contractId;

    @Schema(description = "期次", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer period;

    @Schema(description = "计划收付款金额（元）", example = "30000000.00")
    private BigDecimal planAmount;

    @Schema(description = "计划收付款日期", example = "2027-01-31")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planDate;

    @Schema(description = "计划状态：0-未到期 1-待到账 2-已收付 3-已逾期", example = "0")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
