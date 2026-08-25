package com.jun.aicodehelper.oss;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * OSS 操作门面：签名 PUT/GET、对象 CRUD、流式下载、本地临时下载。
 * 调用方需在 OssClientConfig 启用后才有 OSS bean；未启用时所有方法抛 IllegalStateException。
 */
@Slf4j
@Service
public class OssService {

    @Resource
    private OssProperties props;

    @Autowired(required = false)
    private OSS oss;

    // ---------- 上传链路 ----------

    /** 生成前端 PUT 直传用的签名 URL */
    public PresignResult presignPut(String key, String contentType) {
        ensureEnabled();
        Date expiry = new Date(System.currentTimeMillis() + props.getPresignExpireSeconds() * 1000L);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(props.getBucket(), key, HttpMethod.PUT);
        req.setExpiration(expiry);
        if (contentType != null) req.setContentType(contentType);
        String signedUrl = oss.generatePresignedUrl(req).toString();
        return new PresignResult(signedUrl, key, resolvePublicUrl(key), props.getPresignExpireSeconds());
    }

    /** 直接上传 bytes（如后端抽帧后回写 OSS） */
    public void putBytes(String key, byte[] bytes, String contentType) {
        ensureEnabled();
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(bytes.length);
        if (contentType != null) meta.setContentType(contentType);
        oss.putObject(props.getBucket(), key, new ByteArrayInputStream(bytes), meta);
    }

    /** 从本地文件上传（如 ffmpeg 抽出的帧） */
    public void putFromFile(String key, File file) {
        ensureEnabled();
        oss.putObject(props.getBucket(), key, file);
    }

    // ---------- 下载链路 ----------

    /** 打开对象流（调用方负责关闭） */
    public InputStream openStream(String key) {
        ensureEnabled();
        OSSObject obj = oss.getObject(props.getBucket(), key);
        return obj.getObjectContent();
    }

    /** 一次性读到 bytes（小文件用） */
    public byte[] downloadBytes(String key) {
        try (InputStream in = openStream(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("OSS 下载失败: key=" + key, e);
        }
    }

    /** 下载到本地临时文件（ffmpeg 等本地工具用），返回 Path；调用方负责删除 */
    public Path downloadToTemp(String key, String suffix) {
        ensureEnabled();
        try {
            Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"), "ai-oss");
            Files.createDirectories(tmpDir);
            String name = key.replace('/', '_');
            if (!name.endsWith("." + suffix)) name = name + "." + suffix;
            Path target = tmpDir.resolve(name);
            oss.getObject(new GetObjectRequest(props.getBucket(), key), target.toFile());
            return target;
        } catch (IOException e) {
            throw new RuntimeException("OSS 下载到 temp 失败: key=" + key, e);
        }
    }

    // ---------- 对象管理 ----------

    /** 静默删除：对象不存在不抛错 */
    public void delete(String key) {
        ensureEnabled();
        try {
            oss.deleteObject(props.getBucket(), key);
        } catch (Exception e) {
            // OSS SDK 对不存在的对象可能抛错，吞掉以保证幂等
            log.debug("OSS delete 忽略: key={} err={}", key, e.getMessage());
        }
    }

    /** 按前缀列出对象（不分页，按 OSS 默认 1000 截断） */
    public List<OssObject> listByPrefix(String prefix) {
        ensureEnabled();
        if (prefix == null || prefix.isEmpty()) return Collections.emptyList();
        ListObjectsRequest req = new ListObjectsRequest(props.getBucket());
        req.setPrefix(prefix);
        req.setMaxKeys(1000);
        List<OssObject> out = new ArrayList<>();
        for (OSSObjectSummary s : oss.listObjects(req).getObjectSummaries()) {
            out.add(new OssObject(s.getKey(), s.getSize(), s.getLastModified()));
        }
        return out;
    }

    // ---------- URL 构造 ----------

    /** 公共读对象直接拼公网 URL；私有对象返回签名 GET（按 downloadExpireSeconds 有效期） */
    public String resolvePublicUrl(String key) {
        ensureEnabled();
        if (props.isPublicKey(key)) {
            return "https://" + props.getBucket() + "." + props.getEndpoint() + "/" + key;
        }
        return presignGet(key, props.getDownloadExpireSeconds());
    }

    /** 生成签名 GET URL（带 expires query） */
    public String presignGet(String key, long expireSeconds) {
        ensureEnabled();
        Date expiry = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(props.getBucket(), key, HttpMethod.GET);
        req.setExpiration(expiry);
        return oss.generatePresignedUrl(req).toString();
    }

    // ---------- 工具 ----------

    private void ensureEnabled() {
        if (!props.isEnabled() || oss == null) {
            throw new IllegalStateException("OSS 未启用（aliyun.oss.enabled=false），请在 application-local.yml 配置阿里云凭证后启用");
        }
    }

    @Data
    @AllArgsConstructor
    public static class PresignResult {
        /** 签名 PUT URL（前端直接 PUT 到这） */
        private String uploadUrl;
        /** OSS 对象 key */
        private String key;
        /** 前端可直接显示的 URL（public 对象直接拼公网，私有对象返回签名 GET） */
        private String publicUrl;
        /** 上传 URL 有效期（秒） */
        private int expiresIn;
    }

    @Data
    @AllArgsConstructor
    public static class OssObject {
        private String key;
        private long size;
        private Date lastModified;
    }
}