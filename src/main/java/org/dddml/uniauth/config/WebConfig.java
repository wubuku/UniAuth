package org.dddml.uniauth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.frontend.type:thymeleaf}")
    private String frontendType;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 处理静态资源
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/", "classpath:/public/", "classpath:/resources/")
                .setCachePeriod(0);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 只有在Thymeleaf模式下才使用视图控制器
        // React模式下由静态资源处理器处理
        if ("thymeleaf".equals(frontendType)) {
            registry.addViewController("/login").setViewName("login");
            registry.addViewController("/").setViewName("home");
        }
    }
}
