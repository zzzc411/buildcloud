package com.buildcloud.material.service;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * material 域单元测试的断言补充工具
 *
 * 为什么需要它：基座 {@code AssertUtils.assertServiceException(executable, ErrorCode, params...)}
 * 只能断言「有专属 ErrorCode 常量的业务异常」。但本模块有两类校验**没有专属错误码**：
 * 1. 编码重复（010 段位只有 NAME_DUPLICATE / HAS_CHILDREN，没有 CODE_DUPLICATE）；
 * 2. 父子成环（属参数不合法）。
 * 二者当前都用 {@code invalidParamException}（框架 400 参数异常）表达。
 *
 * 不能为了写测试就凭空造一个 ErrorCode 常量——那等于先改错误码体系再"验证"它，
 * 是自证循环。故单独放在这里断言「400 + 提示含关键信息」，
 * 等 spec 该段位扩容出专属码时，把这些断言替换回 assertServiceException 即可。
 *
 * @author buildcloud
 */
public final class ServiceExceptionAssert {

    private ServiceExceptionAssert() {
    }

    /**
     * 断言抛出「参数异常」且提示包含指定关键字
     *
     * 参数异常 = 基座 {@link GlobalErrorCodeConstants#BAD_REQUEST}（code=400），
     * 由 {@code ServiceExceptionUtil.invalidParamException} 抛出。
     *
     * @param executable      被测调用
     * @param messageContains 期望提示中包含的关键字（用于确认是"该条校验"而非别的校验拦下的）
     */
    public static void assertInvalidParam(Executable executable, String messageContains) {
        ServiceException exception = assertThrows(ServiceException.class, executable);
        assertEquals(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getCode(),
                "期望参数异常（400），实际错误码：" + exception.getCode());
        assertTrue(exception.getMessage() != null && exception.getMessage().contains(messageContains),
                "期望错误提示包含【" + messageContains + "】，实际提示：" + exception.getMessage());
    }

}
