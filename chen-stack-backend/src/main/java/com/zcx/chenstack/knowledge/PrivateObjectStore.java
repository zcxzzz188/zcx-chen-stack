package com.zcx.chenstack.knowledge;

import com.zcx.chenstack.config.KnowledgeDocProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 知识原件的私有对象存储。
 *
 * <p>使用独立私有 Bucket，且**不设置任何桶策略**：默认私有即拒绝匿名访问。
 * 与既有 {@code FileUploadUtils} 的公开图片桶完全隔离，禁止复用同一个桶。</p>
 *
 * <p>对象键为内容寻址：同一文件多次上传只存一份；删除前由调用方确认无其它引用。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Component
public class PrivateObjectStore {
    private final MinioClient client;
    private final KnowledgeDocProperties properties;

    public PrivateObjectStore(MinioClient client, KnowledgeDocProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public String bucket() {
        return properties.getBucket();
    }

    /** 校验私有桶存在性；不存在时明确告警但不自动创建（避免策略误配）。 */
    public void verifyBucket() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket()).build());
            if (!exists) {
                throw KnowledgeDocException.unavailable("KNOWLEDGE_BUCKET_MISSING",
                        "知识文件私有存储桶不存在：" + bucket());
            }
        } catch (KnowledgeDocException error) {
            throw error;
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_BUCKET_UNAVAILABLE", "无法访问知识文件私有存储桶");
        }
    }

    /** 首次部署时创建私有桶（无任何策略）。仅在显式开启的功能开关下调用。 */
    public void createBucketIfAbsent() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket()).build());
            }
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_BUCKET_UNAVAILABLE", "知识文件私有存储桶不可用");
        }
    }

    /** 内容寻址对象键；不包含原文件名，避免路径穿越与信息泄漏。 */
    public static String objectKey(String fileSha256, String extension) {
        String prefix = fileSha256.substring(0, 2);
        return "kb/objects/" + prefix + "/" + fileSha256 + "." + extension;
    }

    public void put(String objectKey, byte[] bytes, String contentType) {
        try (InputStream input = new java.io.ByteArrayInputStream(bytes)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket())
                    .object(objectKey)
                    .headers(java.util.Map.of("Content-Type", contentType))
                    .stream(input, bytes.length, -1)
                    .build());
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_STORE_UNAVAILABLE", "知识原件写入失败");
        }
    }

    public boolean exists(String objectKey) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket()).object(objectKey).build());
            return true;
        } catch (ErrorResponseException error) {
            return false;
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_STORE_UNAVAILABLE", "知识原件读取失败");
        }
    }

    public InputStream open(String objectKey) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket()).object(objectKey).build());
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_STORE_UNAVAILABLE", "知识原件读取失败");
        }
    }

    public void remove(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket()).object(objectKey).build());
        } catch (Exception error) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_STORE_UNAVAILABLE", "知识原件删除失败");
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256不可用", error);
        }
    }
}
