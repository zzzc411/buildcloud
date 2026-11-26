package com.buildcloud.contract.controller.admin.contract.vo;

import cn.iocoder.yudao.framework.common.validation.InEnum;
import com.buildcloud.contract.enums.contract.ContractStatusEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 管理后台 - 合同状态更新 Request VO
 *
 * 单独成接口（PUT /update-status）而不是并进 update：状态变更是**状态机迁转**，
 * 需要校验 from→to 合法性（CONTRACT_STATUS_INVALID），与字段编辑是两类操作。
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同状态更新 Request VO")
@Data
public class ContractUpdateStatusReqVO {

    @Schema(description = "合同编号（主键）", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "合同编号不能为空")
    private Long id;

    @Schema(description = "目标状态，见 ContractStatusEnum：0-草稿 1-审批中 2-履行中 3-已结算 9-已作废",
            requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "目标状态不能为空")
    @InEnum(value = ContractStatusEnum.class, message = "目标状态必须是 0/1/2/3/9 之一")
    private Integer status;

}
