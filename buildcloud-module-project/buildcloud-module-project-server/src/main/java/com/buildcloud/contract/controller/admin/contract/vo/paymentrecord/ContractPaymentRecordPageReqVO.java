package com.buildcloud.contract.controller.admin.contract.vo.paymentrecord;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 合同实际收付款流水分页 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同实际收付款流水分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ContractPaymentRecordPageReqVO extends PageParam {

    @Schema(description = "所属合同（bc_contract.id）", example = "1")
    private Long contractId;

    @Schema(description = "类型：1-收款 2-付款", example = "1")
    private Integer type;

    @Schema(description = "关联审批单号", example = "PAY-20261127-0001")
    private String approvalNo;

    @Schema(description = "收付款日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate[] payDate;

}
