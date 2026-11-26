package com.buildcloud.report.controller.admin.health;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 报表中心服务健康检查接口：验证服务是否就绪、/admin-api 链路与安全放行是否生效
 */
@Tag(name = "管理后台 - 健康检查")
@RestController
@RequestMapping("/buildcloud-report/health")
public class HealthController {

    @GetMapping
    @Operation(summary = "健康检查", description = "返回 ok 表示服务已就绪，且 /admin-api 前缀与网关路由可达")
    public CommonResult<String> health() {
        return success("ok");
    }

}
