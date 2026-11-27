package com.buildcloud.material.service.requisition.strategy;

import com.buildcloud.material.enums.requisition.RequisitionModeEnum;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.buildcloud.material.enums.ErrorCodeConstants.REQUISITION_MODE_NOT_IMPLEMENTED;

/**
 * 申领提交策略工厂：按配置 {@code buildcloud.material.requisition-mode} 分发到 V1/V2 实现
 *
 * 配置来源优先级（Spring 标准）：启动参数 `--buildcloud.material.requisition-mode=V2` >
 * 环境变量 `BUILDCLOUD_MATERIAL_REQUISITION_MODE` > Nacos > application.yaml > 本类默认值 `V1`。
 * 故压测时**不必改仓库文件**：加一个启动参数即可切换，保证「同一份代码、一个开关」的可比性
 * （spec §6.1 最后一句）。
 *
 * ========== 为什么每次调用都读 Environment，而不是构造器 @Value 缓存一份 ==========
 *
 * `@Value` 在 bean 构造时把值固化进字段，之后 Nacos 动态刷新**不会**让它变化（除非再加
 * `@RefreshScope` 代理，那会让本 bean 变成懒代理、构造日志与单测都更绕）。
 * 而 {@link Environment#getProperty} 每次都走 PropertySource 链，Spring Cloud Alibaba 把 Nacos
 * 配置变更写回 Environment 后，下一次请求就能读到新值——这正是 W5 压测要的
 * 「切模式不用重启」。代价是每次提交多一次属性查找（纳秒级，相对行锁扣减可忽略）。
 *
 * ========== 为什么配置值非法时是「运行期业务错误」而不是「启动崩溃」 ==========
 *
 * 见 {@link RequisitionSubmitHandler} 类注释：压测现场改配置写错一个字母就整机不可用，
 * 属于把配置错误升级成可用性事故。取舍是：启动照常（V1 仍可用），只有在真正提交申领时
 * 才抛 1-102-070-000 并带上非法值，错误信息可读且能被前端展示。
 *
 * 唯一例外是「配置为空/未配置」——按 `V1` 处理，这样删掉这行配置也能跑，
 * 符合「V1 是当前唯一可用链路」的事实。
 *
 * @author buildcloud
 */
@Component
@Slf4j
public class RequisitionSubmitHandlerFactory {

    /**
     * 配置项键名（与 application.yaml / 启动参数一致，写在这里避免多处字面量漂移）
     */
    public static final String CONFIG_KEY = "buildcloud.material.requisition-mode";

    /**
     * 默认模式：V1（当前唯一完整实现的链路）
     */
    public static final String DEFAULT_MODE = "V1";

    private final Map<RequisitionModeEnum, RequisitionSubmitHandler> handlers = new HashMap<>();

    private final Environment environment;

    public RequisitionSubmitHandlerFactory(List<RequisitionSubmitHandler> handlerList, Environment environment) {
        // 用 List 注入收集所有实现：W5 之后实现类可能更多（V2 灰度版等），新增实现无需改本类
        handlerList.forEach(handler -> handlers.put(handler.getMode(), handler));
        this.environment = environment;
    }

    /**
     * 启动时把「当前生效模式」打进日志
     *
     * 为什么必须是 INFO：压测报告要能证明「这一轮压的是 V1」，日志是最直接的证据链
     * （配合单测的 V1/V2 开关用例，形成代码级 + 运行级双重证据）。
     */
    @PostConstruct
    public void logActiveMode() {
        RequisitionModeEnum mode = getActiveMode();
        log.info("[RequisitionSubmitHandlerFactory][申领链路模式 {}，已注册实现 {}]",
                mode == null ? "非法配置[" + readConfiguredMode() + "]" : mode.getMode(), handlers.keySet());
    }

    /**
     * 获得当前配置对应的实现
     *
     * @return 策略实现；配置非法或该模式无实现时抛 {@code REQUISITION_MODE_NOT_IMPLEMENTED}
     */
    public RequisitionSubmitHandler getHandler() {
        String configuredMode = readConfiguredMode();
        RequisitionModeEnum mode = resolveModeOrNull(configuredMode);
        if (mode == null) {
            // 配置值拼错（如 `v3`、`V1-` 之类）→ 可读错误码，带上原始值便于现场定位
            throw exception(REQUISITION_MODE_NOT_IMPLEMENTED, configuredMode);
        }
        RequisitionSubmitHandler handler = handlers.get(mode);
        if (handler == null) {
            // 配置对但该模式没有实现类（当前不会发生：V1/V2 都常驻；留给将来拆分部署的防御）
            throw exception(REQUISITION_MODE_NOT_IMPLEMENTED, mode.getMode());
        }
        return handler;
    }

    /**
     * 获得当前生效模式（用于日志/查询展示，不参与业务判断）
     *
     * @return 模式；配置非法时返回 {@code null}
     */
    public RequisitionModeEnum getActiveMode() {
        return resolveModeOrNull(readConfiguredMode());
    }

    /**
     * 读取配置原始值（未 trim、未默认）
     *
     * @return 配置值；未配置返回 null
     */
    private String readConfiguredMode() {
        return environment.getProperty(CONFIG_KEY);
    }

    /**
     * 解析配置值：空 → 默认 V1，否则按枚举名匹配（大小写不敏感、容忍首尾空格）
     *
     * @param configuredMode 配置值
     * @return 模式；无法匹配返回 null
     */
    private RequisitionModeEnum resolveModeOrNull(String configuredMode) {
        if (configuredMode == null || configuredMode.isBlank()) {
            return RequisitionModeEnum.parse(DEFAULT_MODE);
        }
        return RequisitionModeEnum.parse(configuredMode);
    }

}
