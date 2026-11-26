package com.buildcloud.contract.controller.admin.contract.vo.paymentrecord;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import com.buildcloud.contract.enums.contract.PaymentRecordTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 合同实际收付款流水新增/修改 Request VO
 *
 * approval_no 可空：历史数据与手工补录的收付款没有审批流（ER 图 §3.1 设计取舍）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同实际收付款流水新增/修改 Request VO")
@Data
public class ContractPaymentRecordSaveReqVO {

    @Schema(description = "流水编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属合同不能为空")
    private Long contractId;

    @Schema(description = "关联审批单号（BPM 流程业务单号，手工补录可为空）", example = "PAY-20261127-0001")
    @Size(max = 64, message = "审批单号长度不能超过 64 个字符")
    private String approvalNo;

    @Schema(description = "实际收付款金额（元）", requiredMode = Schema.RequiredMode.REQUIRED, example = "5000000.00")
    @NotNull(message = "收付款金额不能为空")
    @DecimalMin(value = "0.01", message = "收付款金额必须大于 0")
    private BigDecimal amount;

    @Schema(description = "实际收付款日期", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-12-15")
    @NotNull(message = "收付款日期不能为空")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate payDate;

    @Schema(description = "类型，见 PaymentRecordTypeEnum：1-收款 2-付款", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "收付款类型不能为空")
    @InEnum(value = PaymentRecordTypeEnum.class, message = "收付款类型必须是 1-收款 或 2-付款")
    private Integer type;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
