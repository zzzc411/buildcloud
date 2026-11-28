package com.buildcloud.material.framework.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 材料域对账相关配置（`buildcloud.material.reconcile.*`）
 *
 * ========== 为什么不用 @Value ==========
 *
 * 一组配置有 4 个字段，散落的 `@Value("${...:默认值}")` 有三处问题：
 * 1. 默认值写在注解字符串里，改默认值要在多个类之间找；
 * 2. `@Value` 不支持宽松绑定（`alert-threshold` / `alertThreshold` 两种写法行为不同），
 *    而 YAML 里写连字符是基座约定；
 * 3. 阈值是**运行期会被调整的参数**（压测期间临时关告警、事故期间调高阈值），
 *    集中在一处才便于将来接 Nacos 动态刷新（本 Phase 只做静态绑定，见下）。
 *
 * 用 {@code @Component + @ConfigurationProperties} 而不是在启动类加
 * `@EnableConfigurationProperties`：material 服务的启动类只扫 `com.buildcloud.material`，
 * 本类就在这个包下，注解即生效，不必改启动类（比基座风格更保守：不动别人的入口）。
 *
 * ========== 与 Nacos 动态刷新的关系（如实说明，不夸大） ==========
 *
 * 本 Phase **不**加 `@RefreshScope`：加了会让整个 Service 变成作用域代理，
 * 而 Service 里持有 Mapper/Redisson 等长生命周期依赖，代理化会引入难排查的生命周期问题。
 * 阈值变更在 local 下靠重启生效；生产上若要热更新，正确做法是给本类加 `@RefreshScope`
 * （它是纯 POJO，没有依赖），而不是给 Service 加——这条留在 Javadoc 里，避免下一个人加错地方。
 *
 * @author buildcloud
 */
@Component
@ConfigurationProperties(prefix = "buildcloud.material.reconcile")
@Data
public class ReconcileProperties {

    /**
     * 差异条数告警阈值（**超过**该值才告警；默认 0 表示"有任何差异就告警"）
     *
     * 取「超过」而非「达到」的理由：默认 0 的语义必须是"有差异就报"，
     * 若写成 `>=`，默认值 0 会让 `diffCount = 0` 的干净轮次也告警——每天都报一次"没问题"，
     * 告警通道很快会被忽略（告警疲劳），真正的差异反而被淹掉。
     */
    private int alertThreshold = 0;

    /**
     * 修复开关（生产排障时的总闸）
     *
     * false = 只检测、只记录、只告警，**一动不动**（Redis 与 DB 都不写）。
     * 用于：怀疑对账修复本身造成偏差时立刻止血，而不必停掉整个 Job（停 Job 就看不到差异了）。
     */
    private boolean fixEnabled = true;

    /**
     * 告警接收人（站内信 userId；多个用逗号分隔）
     *
     * 为什么默认空而不是 1：写死任何 userId 都会在某天变成"发给一个不存在的人"，
     * 而空值时降级为 error 日志（见 {@code ReconcileAlertService}）——
     * **明确降级**比**发错人**安全。
     */
    private String alertUserIds = "";

    /**
     * 站内信模板编号（基座 `system_notify_template` 的 code）
     *
     * 默认值取基座自带的通用模板 `USER_SEND`（该模板由基座 `system_notify_template` 初始化 SQL 建立，
     * W5-P4 未新增模板——新增站内信模板属"改数据库初始化数据"，需单独评估，见计划进度日志的取舍说明）。
     */
    private String notifyTemplateCode = "USER_SEND";

}
