package com.buildcloud.contract.controller.admin.contract.vo.paymentplan;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 合同收付款计划分页 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同收付款计划分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ContractPaymentPlanPageReqVO extends PageParam {

    @Schema(description = "所属合同（bc_contract.id）", example = "1")
    private Long contractId;

    @Schema(description = "期次", example = "1")
    private Integer period;

    @Schema(description = "计划状态：0-未到期 1-待到账 2-已收付 3-已逾期", example = "0")
    private Integer status;

    @Schema(description = "计划日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate[] planDate;

}
