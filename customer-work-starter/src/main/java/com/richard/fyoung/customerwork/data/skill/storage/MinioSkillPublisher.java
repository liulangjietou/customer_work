package com.richard.fyoung.customerwork.data.skill.storage;

import com.richard.fyoung.customerwork.core.constant.AgentFileNames;
import io.minio.BucketExistsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.MediaType;

/**
 * MinIO 对象存储目标：把 SKILL.md 发布为对象 {@code {prefix}{skillCode}/SKILL.md}，
 * 附属文件发布为 {@code {prefix}{skillCode}/{filePath}}。
 *
 * <p>取代此前的本地 workspace 目标——技能包写在单机磁盘上，多副本部署时各副本内容不一致、
 * 容器销毁即丢，而技能是要被运行时消费的配置产物，这种不一致没有意义。</p>
 *
 * <p>bucket 惰性确保：构造不连网（MinIO 不可达不影响应用启动），首次发布才检查 / 按需创建。
 * 发布失败抛异常，由上层转业务异常并回滚事务（与其余发布目标语义一致）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MinioSkillPublisher implements SkillContentPublisher {

    private static final Logger log = LoggerFactory.getLogger(MinioSkillPublisher.class);

    private final MinioClient client;
    private final String bucket;
    private final String prefix;
    private final boolean autoCreateBucket;

    /** bucket 已确保存在的缓存标志（volatile：首次确保后并发可见，避免重复触网）。 */
    private volatile boolean bucketEnsured = false;

    public MinioSkillPublisher(String endpoint, String accessKey, String secretKey,
                               String bucket, String prefix, boolean autoCreateBucket) {
        // 仅构建客户端，绝不连网——保证 MinIO 不可达时应用仍能启动
        this.client = MinioClient.builder().endpoint(endpoint).credentials(accessKey, secretKey).build();
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
        this.autoCreateBucket = autoCreateBucket;
    }

    @Override
    public SkillStorageTarget target() {
        return SkillStorageTarget.MINIO;
    }

    @Override
    public void publish(String skillCode, String content) {
        putObject(objectKey(skillCode, AgentFileNames.SKILL_MD), content.getBytes(StandardCharsets.UTF_8));
        log.info("minio skill published, skillCode={}, bucket={}", skillCode, bucket);
    }

    @Override
    public void publishFiles(String skillCode, List<SkillFileContent> files) {
        for (SkillFileContent file : files) {
            putObject(objectKey(skillCode, file.filePath()), file.content());
        }
        if (!files.isEmpty()) {
            log.info("minio skill files published, skillCode={}, count={}", skillCode, files.size());
        }
    }

    /** 删除该 skill 前缀下的全部对象（对象存储没有目录，只能列举后逐个删）。 */
    @Override
    public void remove(String skillCode) {
        String skillPrefix = prefix + skillCode + "/";
        try {
            ensureBucket();
            Iterable<Result<Item>> objects = client.listObjects(ListObjectsArgs.builder()
                .bucket(bucket).prefix(skillPrefix).recursive(true).build());
            for (Result<Item> result : objects) {
                client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(result.get().objectName()).build());
            }
            log.info("minio skill removed, skillCode={}, bucket={}", skillCode, bucket);
        } catch (Exception e) {
            throw new UncheckedIOException(
                new IOException("remove minio skill objects failed: " + skillPrefix, e));
        }
    }

    private void putObject(String objectKey, byte[] data) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .contentType(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .build());
        } catch (Exception e) {
            throw new UncheckedIOException(
                new IOException("put minio skill object failed: " + objectKey, e));
        }
    }

    private String objectKey(String skillCode, String relativePath) {
        return prefix + skillCode + "/" + relativePath;
    }

    /** 惰性确保 bucket 存在：首次调用检查 / 按需创建，之后凭 volatile 标志短路，不再触网。 */
    private void ensureBucket() throws Exception {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                if (!autoCreateBucket) {
                    throw new IllegalStateException("minio bucket not exists and auto-create disabled: " + bucket);
                }
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("minio bucket created, bucket={}", bucket);
            }
            bucketEnsured = true;
        }
    }

    /** 前缀规整：空则不加前缀，非空则保证以 / 结尾（对象 key 里靠它分层）。 */
    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }
}
