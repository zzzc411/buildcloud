package com.buildcloud.material.controller.admin.requisition;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionPageReqVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionRespVO;
import com.buildcloud.material.controller.admin.requisition.vo.RequisitionSaveReqVO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionDO;
import com.buildcloud.material.dal.dataobject.requisition.RequisitionItemDO;
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
 * 管理后台 - 材料申领
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-material}，第二段 = 域 {@code requisition}；
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外暴露：
 * http://127.0.0.1:48080/admin-api/buildcloud-material/requisition/**（经网关）
 * http://127.0.0.1:48085/admin-api/buildcloud-material/requisition/**（直连）
 *
 * 接口清单（本 Phase 4 个 + 1 个明细查询）：
 * <pre>
 * POST /create   建草稿（含明细，不动库存）
 * POST /submit   提交 → V1 行锁同步扣减（spec §6.1 对照组入口，W5 压测打这个接口）
 * POST /cancel   取消 → 回补库存 + 写冲正流水（biz_type=4）
 * GET  /get      详情（含明细）
 * GET  /page     分页
 * </pre>
 *
 * 权限标识 {@code material:requisition:*}（与 material 域菜单 SQL 一一对应）。
 *
 * ========== 为什么 submit 用 POST 且返回单据全文 ==========
 *
 * - POST 而非 PUT：submit 不是幂等更新（重复提交必须被拒，见 Service 的状态 CAS），
 *   语义上是「触发一个动作」而不是「设置一个状态」；
 * - 返回单据全文（状态 + reqNo + timeoutTime）：压测脚本与前端都靠返回体断言
 *   「status 是不是 2」「reqNo 拿到没有」，省一次 GET（压测时这就是一半的请求量）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 材料申领")
@RestController
@RequestMapping("/buildcloud-material/requisition")
@Validated
public class RequisitionController {

    @Resource
    private RequisitionService requisitionService;

    @PostMapping("/create")
    @Operation(summary = "创建材料申领单（草稿，含明细）",
            description = "新建单据固定为草稿态，不扣减库存；明细至少一行、同一物料不可重复")
    @PreAuthorize("@ss.hasPermission('material:requisition:create')")
    public CommonResult<Long> createRequisition(@Valid @RequestBody RequisitionSaveReqVO createReqVO) {
        return success(requisitionService.createRequisition(createReqVO));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交材料申领单（V1 行锁同步扣减）",
            description = "同一事务内 SELECT ... FOR UPDATE 锁库存行 → 校验可用量 → 扣减 → 写流水（biz_type=2, quantity 负）；"
                    + "库存不足整单回滚。链路模式由 buildcloud.material.requisition-mode 决定（V1 默认，V2 W5 交付）")
    @Parameter(name = "id", description = "申领单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('material:requisition:submit')")
    public CommonResult<RequisitionRespVO> submitRequisition(@RequestParam("id") Long id) {
        RequisitionDO requisition = requisitionService.submitRequisition(id);
        return success(buildRespVO(requisition));
    }

    @PostMapping("/cancel")
    @Operation(summary = "取消材料申领单",
            description = "只有已提交(1)/已扣减(2) 可取消；回补 available_qty 并写冲正流水（biz_type=4, quantity 正）")
    @Parameter(name = "id", description = "申领单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('material:requisition:cancel')")
    public CommonResult<RequisitionRespVO> cancelRequisition(@RequestParam("id") Long id) {
        RequisitionDO requisition = requisitionService.cancelRequisition(id);
        return success(buildRespVO(requisition));
    }

    @GetMapping("/get")
    @Operation(summary = "获得材料申领单（含明细）")
    @Parameter(name = "id", description = "申领单编号", required = true, example = "1")
    @PreAuthorize("@ss.hasPermission('material:requisition:query')")
    public CommonResult<RequisitionRespVO> getRequisition(@RequestParam("id") Long id) {
        RequisitionDO requisition = requisitionService.getRequisition(id);
        if (requisition == null) {
            // 与基座 getXxx 口径一致：查不到返回 data=null（code=0），由前端判断；
            // 不抛 REQUISITION_NOT_EXISTS 是为了让「详情页打开已删除单据」表现为空而非报错
            return success(null);
        }
        return success(buildRespVO(requisition));
    }

    @GetMapping("/page")
    @Operation(summary = "获得材料申领单分页",
            description = "按项目/申请人/状态/审批状态/单号/创建时间筛选；id 倒序（新建在前）")
    @PreAuthorize("@ss.hasPermission('material:requisition:query')")
    public CommonResult<PageResult<RequisitionRespVO>> getRequisitionPage(
            @Valid RequisitionPageReqVO pageReqVO) {
        PageResult<RequisitionDO> pageResult = requisitionService.getRequisitionPage(pageReqVO);
        // 分页不返回明细：列表页只需状态与单号，明细随详情接口取
        // （避免 N 张单 × M 行明细的笛卡尔返回，列表页的响应体应是恒定大小的）
        return success(BeanUtils.toBean(pageResult, RequisitionRespVO.class));
    }

    /**
     * 组装「单据 + 明细」的响应体
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
