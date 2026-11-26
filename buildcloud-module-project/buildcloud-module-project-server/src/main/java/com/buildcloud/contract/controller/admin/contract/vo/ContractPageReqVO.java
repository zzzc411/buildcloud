package com.buildcloud.contract.controller.admin.contract.vo;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;
import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND;

/**
 * 管理后台 - 合同分页 Request VO
 *
 * 台账检索维度只保留走索引的列：contract_no / project_id / status / direction / sign_date
 * （对应 bc_contract 的 uk_contract_no、idx_project_id、idx_status、idx_direction_sign_date）。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ContractPageReqVO extends PageParam {

    @Schema(description = "合同编号", example = "HT-2026-0001")
    private String contractNo;

    @Schema(description = "合同名称（模糊匹配）", example = "总包合同")
    private String contractName;

    @Schema(description = "关联项目（bc_project.id）", example = "1")
    private Long projectId;

    @Schema(description = "合同方向：1-收入 2-支出", example = "1")
    private Integer direction;

    @Schema(description = "合同状态：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废", example = "2")
    private Integer status;

    @Schema(description = "签订日期区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate[] signDate;

    @Schema(description = "创建时间区间")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

}
