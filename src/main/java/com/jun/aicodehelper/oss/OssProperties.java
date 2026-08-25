package com.jun.aicodehelper.oss;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 阿里云 OSS 配置。aliyun.oss.* 前缀；enabled=false 时 OssService 拒绝所有调用（开发降级用）。
 */
@Data
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssProperties {

    private boolean enabled = false;

    /** 公网 endpoint（如 oss-cn-hangzhou.aliyuncs.com），用于生成前端可见的 URL */
    private String endpoint;

    /** 内网 endpoint（可选）；后端→OSS 走内网时填写，留空则与 endpoint 相同 */
    private String internalEndpoint;

    private String bucket;

    private String accessKey;

    private String secretKey;

    /** 所有写入对象的根前缀（私有） */
    private String keyPrefix = "uploads";

    /** 该前缀下对象公共读（图片/视频帧），前端 <img src> 直接可用 */
    private String publicPrefix = "public";

    /** 签名 PUT 有效期（前端上传） */
    private int presignExpireSeconds = 900;

    /** 签名 GET 有效期（私有对象下载） */
    private int downloadExpireSeconds = 3600;

    /** 解析后用于服务端访问 OSS 的 endpoint（内网优先） */
    public String effectiveInternalEndpoint() {
        return (internalEndpoint != null && !internalEndpoint.isBlank()) ? internalEndpoint : endpoint;
    }

    public boolean isPublicKey(String key) {
        return publicPrefix != null && !publicPrefix.isBlank()
                && key != null && key.startsWith(publicPrefix + "/");
    }
}