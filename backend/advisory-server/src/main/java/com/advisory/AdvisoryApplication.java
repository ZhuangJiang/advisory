package com.advisory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 内部智能咨询平台的后端启动类。
 *
 * <p>只负责引导 Spring 容器：组件扫描、数据源、Flyway 迁移、安全链与各业务模块的装配都在各自的配置类里，
 * 这里刻意不写任何业务逻辑，避免启动入口随功能演进而膨胀。</p>
 */
@SpringBootApplication
public class AdvisoryApplication {

    /**
     * 进程入口。
     *
     * @param args 由 {@code SpringApplication} 解析的启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AdvisoryApplication.class, args);
    }
}
