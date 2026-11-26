package com.buildcloud.material.enums;

import cn.iocoder.yudao.framework.common.enums.RpcConstants;

/**
 * 物料管理 API 相关的枚举
 */
public class ApiConstants {

    /**
     * 服务名
     *
     * 注意，需要保证和 spring.application.name 保持一致
     */
    public static final String NAME = "buildcloud-material-server";

    /**
     * RPC 接口的统一前缀，基座约定为 /rpc-api 前缀（见 RpcConstants）
     */
    public static final String PREFIX = RpcConstants.RPC_API_PREFIX + "/buildcloud-material";

    public static final String VERSION = "1.0.0";

}
