package com.buildcloud.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 筑云 BuildCloud — 报表中心服务启动类（服务名 buildcloud-report-server，端口 48086）
 */
@SpringBootApplication
public class ReportServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReportServerApplication.class, args);
    }

}
