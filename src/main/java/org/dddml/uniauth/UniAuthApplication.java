package org.dddml.uniauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

/**
 * UniAuth 应用入口
 * 
 * 启用 Spring Session JDBC：
 * - 将 HttpSession 持久化到数据库（SPRING_SESSION 表）
 * - 支持多服务器部署，session 自动共享
 * - 应用重启后 session 仍然保留
 */
@SpringBootApplication
@EnableSpringHttpSession
public class UniAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(UniAuthApplication.class, args);
    }
}
