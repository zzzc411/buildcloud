package com.buildcloud.project.framework.security.config;

import cn.iocoder.yudao.framework.security.config.AuthorizeRequestsCustomizer;
import com.buildcloud.project.enums.ApiConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * 项目管理模块的 Security 配置：在基座「默认必须认证」的兜底规则之前，追加本模块的白名单
 */
@Configuration(proxyBeanMethods = false, value = "projectSecurityConfiguration")
public class SecurityConfiguration {

    @Bean("projectAuthorizeRequestsCustomizer")
    public AuthorizeRequestsCustomizer authorizeRequestsCustomizer() {
        return new AuthorizeRequestsCustomizer() {

            @Override
            public void customize(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry registry) {
                // Swagger 接口文档
                registry.requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers("/webjars/**").permitAll()
                        .requestMatchers("/swagger-ui").permitAll()
                        .requestMatchers("/swagger-ui/**").permitAll();
                // Druid 监控
                registry.requestMatchers("/druid/**").permitAll();
                // Spring Boot Actuator 的安全配置
                registry.requestMatchers("/actuator").permitAll()
                        .requestMatchers("/actuator/**").permitAll();
                // RPC 服务的安全配置（内网直连；网关已剔除外部伪造的 login-user 等内部头）
                registry.requestMatchers(ApiConstants.PREFIX + "/**").permitAll();
                // 健康检查：验证网关路由与 Nacos 注册链路
                registry.requestMatchers(buildAdminApi("/buildcloud-project/health")).permitAll();
            }

        };
    }

}
