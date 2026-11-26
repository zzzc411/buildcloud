package com.buildcloud.material;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 筑云 BuildCloud — 物料管理服务启动类（服务名 buildcloud-material-server，端口 48085）
 */
@SpringBootApplication
public class MaterialServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MaterialServerApplication.class, args);
    }

}
