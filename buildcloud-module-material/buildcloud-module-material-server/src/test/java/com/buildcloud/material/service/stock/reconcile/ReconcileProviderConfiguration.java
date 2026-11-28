package com.buildcloud.material.service.stock.reconcile;

import com.buildcloud.material.framework.job.config.ReconcileProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 对账单测的**依赖替身配置**
 *
 * ========== 为什么需要它（不能直接 @MockBean ReconcileAlertService 吗） ==========
 *
 * `ReconcileAlertService` 依赖 `NotifyMessageSendApi`（system-server 的 Feign 客户端）。
 * 单测里既不想连 system-server，也不想为了一个测试去注册 Feign 代理
 * （那会把 `@EnableFeignClients` 的启动约束带进 H2 单测）。
 * 这里用一个**行为真实的替身**（记录调用、可断言"有没有告警"）替代它，
 * 而不是 `@MockBean` 一个空 mock：本类的用例要断言"差异超阈值时确实调用了告警"，
 * 空 mock 只能 verify 调用次数，记录式替身能同时断言**告警内容**（差异数、批次号）。
 *
 * `@Primary` 的作用：如果被测上下文里也扫到了真正的 `ReconcileAlertService`（例如将来
 * `@Import` 列表变了），注入点仍然拿到替身，不会出现"这条用例悄悄连了 Feign"。
 *
 * @author buildcloud
 */
@TestConfiguration
public class ReconcileProviderConfiguration {

    /**
     * 记录式告警替身（bean 名带 Test 前缀，避免与真实 {@code reconcileAlertService} 抢同名 bean）
     *
     * @return 替身 bean
     */
    @Bean
    @Primary
    public RecordingAlertService testRecordingAlertService() {
        return new RecordingAlertService();
    }

    /**
     * Feign 客户端的空替身：让 {@code ReconcileAlertService} 的 `@Resource` 注入点能解析
     *
     * 为什么不能省：`@Resource` 默认 required=true，容器里没有该类型的 bean 时
     * 整个测试上下文起不来（本类的第一次运行就是被这颗挡住的）。
     * 本替身**永远不会被调用**——被测的是 {@link RecordingAlertService}，它的 `alert` 被完全覆盖。
     *
     * @return 一个返回成功的桩
     */
    @Bean
    @Primary
    public cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi notifyMessageSendApiStub() {
        // 接口有两个方法（admin / member），用一个匿名实现把两者都给出：
        // 不用 lambda——NotifyMessageSendApi 不是函数式接口（两个抽象方法）
        return new cn.iocoder.yudao.module.system.api.notify.NotifyMessageSendApi() {
            @Override
            public cn.iocoder.yudao.framework.common.pojo.CommonResult<Long>
                    sendSingleMessageToAdmin(cn.iocoder.yudao.module.system.api.notify.dto
                                                     .NotifySendSingleToUserReqDTO reqDTO) {
                return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(0L);
            }

            @Override
            public cn.iocoder.yudao.framework.common.pojo.CommonResult<Long>
                    sendSingleMessageToMember(cn.iocoder.yudao.module.system.api.notify.dto
                                                      .NotifySendSingleToUserReqDTO reqDTO) {
                return cn.iocoder.yudao.framework.common.pojo.CommonResult.success(0L);
            }
        };
    }

    /**
     * 测试用的对账配置（真实例，字段为业务默认值）
     *
     * @return 配置 bean
     */
    @Bean
    @Primary
    public ReconcileProperties reconcileProperties() {
        ReconcileProperties properties = new ReconcileProperties();
        properties.setAlertThreshold(0);
        properties.setFixEnabled(true);
        properties.setAlertUserIds("");
        return properties;
    }

    /**
     * 记录式告警替身：只记录，不真的发站内信
     *
     * 所有字段（`reconcileProperties` / `notifyMessageSendApi`）在父类里都是私有 `@Resource` 注入的，
     * 本类**不覆盖 alert 之外的任何行为**，因此父类的 `@Resource` 依然要能满足。
     * 这里用「构造时不注入、调用时不使用」的方式绕开：父类的 `alert` 被完全覆盖，
     * `notifyMessageSendApi` 在测试里恒为 null 也不会被读到——但 Spring 的 `@Resource`
     * 是**强制注入**（required=true），字段类型存在而容器里没有对应 bean 时会抛
     * `NoSuchBeanDefinitionException`。因此本替身改用**独立的 bean 名 + 显式实例化**，
     * 并在 {@link ReconcileProviderConfiguration} 里额外提供一个空的 Feign 替身，
     * 让父类的注入点能成功解析（见下方 notifyMessageSendApiStub）。
     */
    public static class RecordingAlertService extends ReconcileAlertService {

        /**
         * 收到的告警（按调用顺序）
         */
        private final java.util.List<String> alerts = new java.util.ArrayList<>();

        /**
         * 阈值判定沿用真实实现（`diffCount > threshold`），保证"阈值语义"仍被覆盖
         *
         * @param diffCount 检出数
         * @return 是否告警
         */
        @Override
        public boolean shouldAlert(int diffCount) {
            return diffCount > 0;
        }

        @Override
        public void alert(String batchNo, String summary, int diffCount) {
            alerts.add(batchNo + "|" + diffCount + "|" + summary);
        }

        /**
         * 已收到的告警条数
         *
         * @return 条数
         */
        public int size() {
            return alerts.size();
        }

    }

}
