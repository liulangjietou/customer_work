package com.richard.fyoung.customerwork.data.attachment;

import java.io.IOException;

/**
 * 附件原始文件存储抽象：把上传字节存到某种后端，返回相对 key。
 *
 * <p>返回的 key 语义与后端无关，统一为 {@code {yyyyMM}/{uuid}.{ext}}（落 DB 的 storage_path），
 * 磁盘文件名 / 对象 key 只用 {@code uuid.ext}（原始文件名不落 key，防路径穿越，原名仅进 DB）。
 * 两种实现：{@link LocalAttachmentFileStorage}（本地磁盘）/ {@link MinioAttachmentFileStorage}（MinIO 对象存储），
 * 由 {@link AttachmentFileStorages} 按配置 {@code customer-work.attachment.storage.type} 选型。</p>
 * @author owlzhangfq@gmail.com
 */
public interface AttachmentFileStorage {

    /**
     * 存储文件字节并返回相对 key。
     *
     * @param data 文件字节
     * @param id   附件 ID（作文件名 / 对象 key 主体）
     * @param ext  小写扩展名（不含点，可空——空则不带后缀）
     * @return 相对 key，形如 {@code 202607/{id}.{ext}}（落 DB storage_path）
     * @throws IOException 存储失败（本地落盘或对象上传失败，语义与原本地盘一致）
     */
    String store(byte[] data, String id, String ext) throws IOException;

    /**
     * 按相对 key 读回原始文件字节（供附件预览 / 下载链路）。
     *
     * @param storagePath {@link #store} 返回并落 DB 的相对 key（形如 {@code 202607/{id}.{ext}}）
     * @return 文件全量字节
     * @throws IOException 文件不存在或读取失败（本地盘读不到 / 对象不存在，语义与 store 失败一致，交上层翻译）
     */
    byte[] read(String storagePath) throws IOException;
}
