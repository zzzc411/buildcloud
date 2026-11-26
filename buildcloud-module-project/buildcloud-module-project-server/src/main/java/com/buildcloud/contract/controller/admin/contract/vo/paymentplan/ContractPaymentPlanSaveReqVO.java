package com.buildcloud.contract.controller.admin.contract.vo.paymentplan;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import com.buildcloud.contract.enums.contract.PaymentPlanStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 合同收付款计划新增/修改 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同收付款计划新增/修改 Request VO")
@Data
public class ContractPaymentPlanSaveReqVO {

    @Schema(description = "计划编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属合同不能为空")
    private Long contractId;

    @Schema(description = "期次（从 1 起，同合同内唯一）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "期次不能为空")
    @Min(value = 1, message = "期次最小值为 1")
    private Integer period;

    @Schema(description = "计划收付款金额（元）", requiredMode = Schema.RequiredMode.REQUIRED, example = "30000000.00")
    @NotNull(message = "计划金额不能为空")
    @DecimalMin(value = "0.01", message = "计划金额必须大于 0")
    private BigDecimal planAmount;

    @Schema(description = "计划收付款日期", example = "2027-01-31")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate planDate;

    @Schema(description = "计划状态，见 PaymentPlanStatusEnum：0-未到期 1-待到账 2-已收付 3-已逾期", example = "0")
    @InEnum(value = PaymentPlanStatusEnum.class, message = "计划状态必须是 0/1/2/3 之一")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
