package com.hengce.aps.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * APS 平台启动入口。
 */
@SpringBootApplication(scanBasePackages = "com.hengce.aps")
public class ApsAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApsAdminApplication.class, args);
    }
}
