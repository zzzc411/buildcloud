package com.buildcloud.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 筑云 BuildCloud — AI 知识库服务启动类（服务名 buildcloud-ai-server，端口 48087）
 */
@SpringBootApplication
public class AiServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiServerApplication.class, args);
    }

}
