package com.buildcloud.contract.controller.admin.contract.vo.paymentrecord;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 合同实际收付款流水 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同实际收付款流水 Response VO")
@Data
public class ContractPaymentRecordRespVO {

    @Schema(description = "流水编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long contractId;

    @Schema(description = "关联审批单号", example = "PAY-20261127-0001")
    private String approvalNo;

    @Schema(description = "实际收付款金额（元）", example = "5000000.00")
    private BigDecimal amount;

    @Schema(description = "实际收付款日期", example = "2026-12-15")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate payDate;

    @Schema(description = "类型：1-收款 2-付款", example = "1")
    private Integer type;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
