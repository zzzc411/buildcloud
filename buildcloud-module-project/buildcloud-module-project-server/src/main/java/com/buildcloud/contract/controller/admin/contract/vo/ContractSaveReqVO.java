package com.buildcloud.contract.controller.admin.contract.vo;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import com.buildcloud.contract.enums.contract.ContractDirectionEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

import static cn.iocoder.yudao.framework.common.util.date.DateUtils.FORMAT_YEAR_MONTH_DAY;

/**
 * 管理后台 - 合同新增/修改 Request VO
 *
 * 精修点：
 * 1. id 为空 = 新增、非空 = 修改（与基座 SaveReqVO 约定一致）；
 * 2. amount 用 @DecimalMin 拦掉 0/负数（服务端另有 CONTRACT_AMOUNT_INVALID 兜底，双层校验）；
 * 3. status 不在本 VO 出现——合同状态只能经 `PUT /update-status` 走状态机，避免 update 接口绕过状态机
 *    把草稿直接改成已结算。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同新增/修改 Request VO")
@Data
public class ContractSaveReqVO {

    @Schema(description = "合同编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "合同编号（业务编码，租户内唯一）", requiredMode = Schema.RequiredMode.REQUIRED, example = "HT-2026-0001")
    @NotEmpty(message = "合同编号不能为空")
    @Size(max = 64, message = "合同编号长度不能超过 64 个字符")
    private String contractNo;

    @Schema(description = "合同名称", requiredMode = Schema.RequiredMode.REQUIRED, example = "滨江商务中心总包合同")
    @NotEmpty(message = "合同名称不能为空")
    @Size(max = 200, message = "合同名称长度不能超过 200 个字符")
    private String contractName;

    @Schema(description = "关联项目（bc_project.id，可空）", example = "1")
    private Long projectId;

    @Schema(description = "合同方向，见 ContractDirectionEnum：1-收入 2-支出",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "合同方向不能为空")
    @InEnum(value = ContractDirectionEnum.class, message = "合同方向必须是 1-收入 或 2-支出")
    private Integer direction;

    @Schema(description = "合同金额（元）", requiredMode = Schema.RequiredMode.REQUIRED, example = "120000000.00")
    @NotNull(message = "合同金额不能为空")
    @DecimalMin(value = "0.01", message = "合同金额必须大于 0")
    private BigDecimal amount;

    @Schema(description = "签订日期", example = "2026-11-27")
    @DateTimeFormat(pattern = FORMAT_YEAR_MONTH_DAY)
    private LocalDate signDate;

    @Schema(description = "甲方（发包方/付款方）", example = "杭州城投")
    @Size(max = 200, message = "甲方长度不能超过 200 个字符")
    private String partyA;

    @Schema(description = "乙方（承包方/收款方）", example = "筑云建设集团")
    @Size(max = 200, message = "乙方长度不能超过 200 个字符")
    private String partyB;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
