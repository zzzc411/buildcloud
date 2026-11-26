package com.buildcloud.project;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 筑云 BuildCloud — 项目管理服务启动类（服务名 buildcloud-project-server，端口 48084）
 */
@SpringBootApplication(scanBasePackages = {"com.buildcloud.project", "com.buildcloud.contract"})
public class ProjectServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectServerApplication.class, args);
    }

}
