package com.buildcloud.contract.controller.admin.contract.vo.change;

import cn.iocoder.yudao.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 管理后台 - 合同变更签证分页 Request VO
 *
 * @author buildcloud
 */
@Schema(description = "管理后台 - 合同变更签证分页 Request VO")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class ContractChangePageReqVO extends PageParam {

    @Schema(description = "所属合同（bc_contract.id）", example = "1")
    private Long contractId;

    @Schema(description = "变更类型：1-设计变更 2-现场签证 3-索赔 9-其他", example = "1")
    private Integer type;

    @Schema(description = "关联审批单号", example = "CHG-20261201-0001")
    private String approvalNo;

}
