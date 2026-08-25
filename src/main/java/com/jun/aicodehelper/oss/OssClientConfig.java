package com.jun.aicodehelper.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 阿里云 OSS 客户端 bean。仅 enabled=true 时创建；否则容器里没有 OSS 实例，
 * OssService 注入会拿到 null，运行时调用方走 ensureEnabled() 抛清晰错误。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(OssProperties.class)
public class OssClientConfig {

    @Bean
    @ConditionalOnProperty(name = "aliyun.oss.enabled", havingValue = "true")
    public OSS oss(OssProperties p) {
        if (isBlank(p.getEndpoint()) || isBlank(p.getBucket())
                || isBlank(p.getAccessKey()) || isBlank(p.getSecretKey())) {
            throw new IllegalStateException("OSS enabled 但缺少 endpoint/bucket/access-key/secret-key 配置");
        }
        OSS client = new OSSClientBuilder().build(p.getEndpoint(), p.getAccessKey(), p.getSecretKey());
        log.info("OSS client ready: endpoint={} bucket={}", p.getEndpoint(), p.getBucket());
        return client;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}