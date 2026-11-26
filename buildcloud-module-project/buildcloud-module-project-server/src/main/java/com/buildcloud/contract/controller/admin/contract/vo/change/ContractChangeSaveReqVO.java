package com.buildcloud.contract.controller.admin.contract.vo.change;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import com.buildcloud.contract.enums.contract.ContractChangeTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理后台 - 合同变更签证新增/修改 Request VO
 *
 * changeAmount 允许负数（减项），但不允许 0；这里只做 @NotNull，
 * 0 值的拒绝放在 Service（错误码 CONTRACT_CHANGE_AMOUNT_ZERO），
 * 保证「业务规则」只有一处实现。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同变更签证新增/修改 Request VO")
@Data
public class ContractChangeSaveReqVO {

    @Schema(description = "变更编号（主键，新增时为空）", example = "1")
    private Long id;

    @Schema(description = "所属合同（bc_contract.id）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "所属合同不能为空")
    private Long contractId;

    @Schema(description = "变更类型，见 ContractChangeTypeEnum：1-设计变更 2-现场签证 3-索赔 9-其他",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "变更类型不能为空")
    @InEnum(value = ContractChangeTypeEnum.class, message = "变更类型必须是 1/2/3/9 之一")
    private Integer type;

    @Schema(description = "增减金额（元）：正数=增项，负数=减项，不可为 0",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "5000000.00")
    @NotNull(message = "变更金额不能为空")
    private BigDecimal changeAmount;

    @Schema(description = "变更原因", example = "业主新增地下室一层")
    @Size(max = 500, message = "变更原因长度不能超过 500 个字符")
    private String changeReason;

    @Schema(description = "关联审批单号（BPM 流程业务单号）", example = "CHG-20261201-0001")
    @Size(max = 64, message = "审批单号长度不能超过 64 个字符")
    private String approvalNo;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500 个字符")
    private String remark;

}
