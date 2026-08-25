package com.jun.aicodehelper.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * 把本地 uploads/ 目录映射到 /api/uploads/**，让前端能直接拿到上传后的文件 URL。
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String absolute = Paths.get(uploadDir).toAbsolutePath().toString();
        // location 要带 file:/// 前缀 + 末尾分隔符
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:///" + absolute.replace('\\', '/') + "/");
    }
}
