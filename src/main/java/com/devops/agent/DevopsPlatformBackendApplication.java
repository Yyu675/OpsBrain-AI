package com.devops.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OpsBrain AI 后端启动类。
 * <p>
 * {@code @EnableScheduling} 启用定时任务，用于 P1-9 孤儿切片清理、
 * 6.23 规划的废弃文档归档等生命周期治理任务。
 * </p>
 *
 * @author OpsBrain AI
 * @since 2026-07-15
 */
@SpringBootApplication
@EnableScheduling
public class DevopsPlatformBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DevopsPlatformBackendApplication.class, args);
    }

}
