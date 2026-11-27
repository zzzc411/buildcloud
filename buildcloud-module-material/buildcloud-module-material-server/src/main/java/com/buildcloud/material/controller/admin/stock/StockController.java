package com.buildcloud.material.controller.admin.stock;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import com.buildcloud.material.controller.admin.stock.vo.StockInboundReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockLogPageReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockLogRespVO;
import com.buildcloud.material.controller.admin.stock.vo.StockPageReqVO;
import com.buildcloud.material.controller.admin.stock.vo.StockRespVO;
import com.buildcloud.material.dal.dataobject.stock.MaterialStockDO;
import com.buildcloud.material.dal.dataobject.stock.StockLogDO;
import com.buildcloud.material.service.stock.StockService;
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

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 管理后台 - 项目库存（含库存流水查询）
 *
 * URL 段遵循 AGENTS R-9：首段 = 服务标识 {@code buildcloud-material}，第二段 = 域 {@code stock}；
 * 经框架 adminApi 前缀（`**.controller.admin.**` → `/admin-api`）对外暴露：
 * http://127.0.0.1:48085/admin-api/buildcloud-material/stock/**
 *
 * 与流水接口的关系：流水是本域的**从属资源**（一定挂在某项目某物料下），故放在同一 Controller 下
 * （`/stock-log/page`）而不是另起 `/buildcloud-material/stock-log` —— 避免出现「流水」在没有库存
 * 语境时被误调用的入口，也让权限标识统一收在 `material:stock:*`。
 *
 * 权限标识沿用 {@code material:stock:*}（与 material 域菜单 SQL 一一对应）。
 *
 * @author buildcloud
 */
@Tag(name = "管理后台 - 项目库存")
@RestController
@RequestMapping("/buildcloud-material/stock")
@Validated
public class StockController {

    @Resource
    private StockService stockService;

    @PostMapping("/inbound")
    @Operation(summary = "材料入库", description = "同一事务内 upsert 库存（乐观锁重试）+ 写入库流水（biz_type=1）")
    @PreAuthorize("@ss.hasPermission('material:stock:inbound')")
    public CommonResult<StockLogRespVO> inbound(@Valid @RequestBody StockInboundReqVO reqVO) {
        StockLogDO stockLog = stockService.inbound(reqVO);
        return success(BeanUtils.toBean(stockLog, StockLogRespVO.class));
    }

    @GetMapping("/get")
    @Operation(summary = "获得项目库存")
    @Parameter(name = "id", description = "库存编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('material:stock:query')")
    public CommonResult<StockRespVO> getStock(@RequestParam("id") Long id) {
        MaterialStockDO stock = stockService.getStock(id);
        return success(BeanUtils.toBean(stock, StockRespVO.class));
    }

    @GetMapping("/page")
    @Operation(summary = "获得项目库存分页", description = "按项目/物料筛选：只传 projectId 看项目库存台账，只传 materialId 看跨项目分布")
    @PreAuthorize("@ss.hasPermission('material:stock:query')")
    public CommonResult<PageResult<StockRespVO>> getStockPage(@Valid StockPageReqVO pageReqVO) {
        PageResult<MaterialStockDO> pageResult = stockService.getStockPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, StockRespVO.class));
    }

    @GetMapping("/stock-log/page")
    @Operation(summary = "获得库存流水分页",
            description = "按 projectId + materialId + bizType + 时间范围筛选；createTime 倒序（最新在前）")
    @PreAuthorize("@ss.hasPermission('material:stock:query')")
    public CommonResult<PageResult<StockLogRespVO>> getStockLogPage(@Valid StockLogPageReqVO pageReqVO) {
        PageResult<StockLogDO> pageResult = stockService.getStockLogPage(pageReqVO);
        return success(BeanUtils.toBean(pageResult, StockLogRespVO.class));
    }

}
