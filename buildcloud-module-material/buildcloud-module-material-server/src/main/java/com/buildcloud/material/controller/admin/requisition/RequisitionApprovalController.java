package com.buildcloud.material.controller.admin.requisition;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalStatusRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionApprovalSubmitReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionReturnReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
import com.buildcloud.material.service.requisition.RequisitionApprovalService;
import com.buildcloud.material.service.requisition.RequisitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 材料申领审批 / 出库 / 退料
 *
 * URL 遵循 R-9：首段为服务标识 {@code buildcloud-material}，第二段为域 {@code requisition}；
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外为：
 * <pre>
 * http://127.0.0.1:48080/admin-api/buildcloud-material/requisition/approval/submit    （经网关）
 * http://127.0.0.1:48080/admin-api/buildcloud-material/requisition/approval/status
 * http://127.0.0.1:48080/admin-api/buildcloud-material/requisition/outbound
 * http://127.0.0.1:48080/admin-api/buildcloud-material/requisition/return
 * </pre>
 *
 * ========== 为什么单独建 Controller，而不并进 {@link RequisitionController} ==========
 *
 * 审批/出库/退料是**跨服务协作 + 有账务副作用**的入口（前者依赖 bpm-server，后两者动库存），
 * 与 {@link RequisitionController} 的「建单/提交/取消/查询」（V1 同步链路，W5 压测对照组）生命周期不同：
 * 1. 分开后单测只需 mock BPM 依赖，不必让压测对照组相关的测试都背着 Feign 客户端；
 * 2. V1 压测对照组的接口面（create/submit/cancel）保持**一字未动**，W5 复测基线时不会因为
 *    「多挂了几个接口」而改变压测口径。
 * 与 W3 的 {@code ProjectApprovalController} / {@code ContractApprovalController} 保持同构。
 *
 * ========== 真正的审批动作不在本服务 ==========
 *
 * 「通过/驳回」由基座 bpm 的 {@code /admin-api/bpm/task/approve}、{@code /admin-api/bpm/task/reject}
 * 处理（读码笔记 §3.3），本服务只负责**发起**与**接收结果**。
 * 会签时基座会为每个审批人各建一个任务，**全部通过**才把流程判为 APPROVE 并回调本服务。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 材料申领审批/出库/退料")
@RestController
@RequestMapping("/buildcloud-material/requisition")
@Validated
public class RequisitionApprovalController {

    @Resource
    private RequisitionApprovalService requisitionApprovalService;

    @Resource
    private RequisitionService requisitionService;

    @PostMapping("/approval/submit")
    @Operation(summary = "提交材料申领会签审批",
            description = "校验单据为草稿、无在途审批、会签人合法 → 同一事务内扣减库存（复用 V1 行锁扣减）"
                    + "→ 状态推进到已提交(1) → 发起 requisition-approve 会签流程 → 回写实例编号；"
                    + "任一失败整体回滚（库存不减少、单据仍是草稿）。失败返回 1_102_050_006/008/009/017 等")
    @PreAuthorize("@ss.hasPermission('material:requisition:submit')")
    public CommonResult<String> submitApproval(@Valid @RequestBody RequisitionApprovalSubmitReqVO reqVO) {
        return success(requisitionApprovalService.submitApproval(
                reqVO.getRequisitionId(), reqVO.getApproverUserIds()));
    }

    @GetMapping("/approval/status")
    @Operation(summary = "获得材料申领审批状态",
            description = "返回流程实例编号、审批状态（-1未开始/1审批中/2通过/3不通过/4已取消）、会签人列表、"
                    + "申领业务状态、出库/退料时间、逐行退料进度（已退量 / 可退量）")
    @Parameter(name = "requisitionId", description = "申领单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('material:requisition:query')")
    public CommonResult<RequisitionApprovalStatusRespVO> getApprovalStatus(
            @RequestParam("requisitionId") Long requisitionId) {
        return success(requisitionApprovalService.getApprovalStatus(requisitionId));
    }

    @PostMapping("/outbound")
    @Operation(summary = "材料申领出库",
            description = "已扣减(2) → 已出库(3)，写 actual_out_time，**不再变动库存**（V1 在提交审批时已扣减）；"
                    + "重复出库返回 1_102_050_010")
    @Parameter(name = "id", description = "申领单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('material:requisition:submit')")
    public CommonResult<RequisitionRespVO> outbound(@RequestParam("id") Long id) {
        return success(buildRespVO(requisitionApprovalService.outbound(id)));
    }

    @PostMapping("/return")
    @Operation(summary = "材料申领退料",
            description = "已出库(3)/部分回退(5) 可退料；退料量 ≤ quantity - returned_qty；"
                    + "回补库存并写 biz_type=3 退回流水（quantity 正数）；全部退完 → 已完成(4)，否则 → 部分回退(5)")
    @PreAuthorize("@ss.hasPermission('material:requisition:submit')")
    public CommonResult<RequisitionRespVO> returnMaterials(@Valid @RequestBody RequisitionReturnReqVO reqVO) {
        return success(buildRespVO(requisitionApprovalService.returnMaterials(reqVO)));
    }

    /**
     * 组装「单据 + 明细」的响应体
     *
     * 与 {@link RequisitionController#buildRespVO} 同一形态（Controller 内的私有方法，不跨类复用：
     * 两处各自组装各自的响应，避免为了复用把一个 build 方法抬到 Service 层去）。
     * 明细必须带上 returnedQty，前端退料对话框要显示「可退量」。
     *
     * @param requisition 单据
     * @return 含明细的 VO
     */
    private RequisitionRespVO buildRespVO(RequisitionDO requisition) {
        RequisitionRespVO respVO = BeanUtils.toBean(requisition, RequisitionRespVO.class);
        List<RequisitionItemDO> items = requisitionService.getRequisitionItemList(requisition.getId());
        respVO.setItems(BeanUtils.toBean(items, RequisitionRespVO.Item.class));
        return respVO;
    }

}
