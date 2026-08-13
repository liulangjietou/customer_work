package com.richard.fyoung.customerwork.data.attachment;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 附件 MinIO 对象存储：把上传字节以对象 key {@code {yyyyMM}/{uuid}.{ext}} 上传到指定 bucket，返回该 key。
 *
 * <p>返回 key 与 {@link LocalAttachmentFileStorage} 的相对路径完全一致（落 DB storage_path 语义不变，DB 无需迁移）。
 * bucket <b>惰性确保</b>：构造函数只建 {@link MinioClient}（不连网，MinIO 不可达不影响应用启动，与 OCR 惰性一致），
 * 首次 {@link #store} 才 bucketExists / makeBucket，用 volatile 标志缓存，确保后续调用不再触网检查。
 * 存储失败抛 {@link IOException}（与本地盘落盘失败语义一致，交由编排层 {@code catch(Exception)} 兜底）。</p>
 * @author owlzhangfq@gmail.com
 */
public class MinioAttachmentFileStorage implements AttachmentFileStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioAttachmentFileStorage.class);

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 上传对象的 content-type（统一按二进制流，解析侧凭扩展名 / Tika 识别，不依赖对象元数据）。 */
    private static final String OBJECT_CONTENT_TYPE = "application/octet-stream";

    private final MinioClient client;
    private final String bucket;
    private final boolean autoCreateBucket;

    /** bucket 已确保存在的缓存标志（volatile：首次确保后并发可见，避免重复触网）。 */
    private volatile boolean bucketEnsured = false;

    public MinioAttachmentFileStorage(String endpoint, String accessKey, String secretKey,
                                      String bucket, boolean autoCreateBucket) {
        // 仅构建客户端，绝不连网——保证 MinIO 不可达时应用仍能启动
        this.client = MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build();
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;
    }

    @Override
    public String store(byte[] data, String id, String ext) throws IOException {
        String month = LocalDate.now().format(MONTH_FMT);
        String fileName = (ext == null || ext.isEmpty()) ? id : id + "." + ext;
        String objectKey = month + "/" + fileName;
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(objectKey)
                .contentType(OBJECT_CONTENT_TYPE)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .build());
        } catch (Exception e) {
            // 与本地盘落盘失败同语义：包成 IOException 抛出，交编排层兜底
            throw new IOException("failed to put object to minio, bucket=" + bucket + ", key=" + objectKey, e);
        }
        log.info("attachment stored to minio, id={}, bucket={}, key={}, size={}", id, bucket, objectKey, data.length);
        return objectKey;
    }

    @Override
    public void storeAt(String storagePath, byte[] data) throws IOException {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(storagePath)
                .contentType(OBJECT_CONTENT_TYPE)
                .stream(new ByteArrayInputStream(data), data.length, -1)
                .build());
        } catch (Exception e) {
            throw new IOException("failed to put object to minio, bucket=" + bucket + ", key=" + storagePath, e);
        }
        log.info("object stored to minio, bucket={}, key={}, size={}", bucket, storagePath, data.length);
    }

    @Override
    public byte[] read(String storagePath) throws IOException {
        // 读路径不做 ensureBucket：读不到对象（桶/对象不存在）本身即"不存在"，getObject 抛异常包成 IOException。
        try (GetObjectResponse resp = client.getObject(GetObjectArgs.builder()
            .bucket(bucket)
            .object(storagePath)
            .build())) {
            return resp.readAllBytes();
        } catch (Exception e) {
            // 与 store 失败同语义：包成 IOException 抛出，交上层翻译成业务错误
            throw new IOException("failed to get object from minio, bucket=" + bucket + ", key=" + storagePath, e);
        }
    }

    @Override
    public void delete(String storagePath) throws IOException {
        // 同 read：不做 ensureBucket。MinIO 的 removeObject 对不存在的对象本就不报错，天然满足幂等语义
        try {
            client.removeObject(RemoveObjectArgs.builder()
                .bucket(bucket)
                .object(storagePath)
                .build());
        } catch (Exception e) {
            throw new IOException("failed to remove object from minio, bucket=" + bucket + ", key=" + storagePath, e);
        }
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
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                if (!autoCreateBucket) {
                    throw new IllegalStateException(
                        "minio bucket not exists and auto-create disabled: " + bucket);
                }
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("minio bucket created, bucket={}", bucket);
            }
            bucketEnsured = true;
        }
    }
}
