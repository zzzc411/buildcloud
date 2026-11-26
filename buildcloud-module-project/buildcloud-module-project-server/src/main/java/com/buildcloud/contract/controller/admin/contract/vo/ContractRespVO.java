package com.buildcloud.contract.controller.admin.contract.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 合同 Response VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同 Response VO")
@Data
public class ContractRespVO {

    @Schema(description = "合同编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long id;

    @Schema(description = "合同编号（业务编码）", requiredMode = Schema.RequiredMode.REQUIRED, example = "HT-2026-0001")
    private String contractNo;

    @Schema(description = "合同名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "滨江商务中心总包合同")
    private String contractName;

    @Schema(description = "关联项目（bc_project.id）", example = "1")
    private Long projectId;

    @Schema(description = "合同方向：1-收入 2-支出", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Integer direction;

    @Schema(description = "合同金额（元）", example = "120000000.00")
    private BigDecimal amount;

    @Schema(description = "签订日期", example = "2026-11-27")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate signDate;

    @Schema(description = "合同状态：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废", example = "0")
    private Integer status;

    @Schema(description = "甲方", example = "杭州城投")
    private String partyA;

    @Schema(description = "乙方", example = "筑云建设集团")
    private String partyB;

    @Schema(description = "累计已收/已付金额（元）", example = "0.00")
    private BigDecimal paidAmount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime createTime;

}
