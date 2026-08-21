package com.richard.fyoung.customerwork.data.attachment;

import java.io.IOException;
import java.util.Optional;

/**
 * 附件原始文件存储抽象：把上传字节存到某种后端，返回相对 key。
 *
 * <p>返回的 key 语义与后端无关，统一为 {@code {yyyyMM}/{uuid}.{ext}}（落 DB 的 storage_path），
 * 对象 key 只用 {@code uuid.ext}（原始文件名不落 key，防路径穿越，原名仅进 DB）。生产实现只保留
 * {@link MinioAttachmentFileStorage}，不允许静默降级到本地盘。</p>
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
     * @throws IOException 对象上传失败
     */
    String store(byte[] data, String id, String ext) throws IOException;

    /**
     * 按<b>调用方指定的 key</b> 覆盖写入。
     *
     * <p>与 {@link #store} 的区别：那个由本 SPI 生成 {@code {yyyyMM}/{uuid}.{ext}} 形式的 key，
     * 适合"每次上传都是一个新对象"的附件语义；本方法适合"一个业务实体固定对应一个对象、每次覆盖"的
     * 场景（如 VibeCoding 会话工作区归档），key 由调用方按业务维度拼出且需要稳定可寻址。</p>
     *
     * @param storagePath 对象 key（调用方保证稳定与合法）
     * @param data        文件字节
     * @throws IOException 存储失败
     */
    void storeAt(String storagePath, byte[] data) throws IOException;

    /**
     * 按相对 key 读回原始文件字节（供附件预览 / 下载链路）。
     *
     * @param storagePath {@link #store} 返回并落 DB 的相对 key（形如 {@code 202607/{id}.{ext}}）
     * @return 文件全量字节
     * @throws IOException 对象不存在或读取失败（交上层翻译）
     */
    byte[] read(String storagePath) throws IOException;

    /**
     * 区分“对象不存在”与权限、网络等真实读取失败。
     * 默认实现保持原 SPI 行为；需要安全迁移回退的存储实现必须覆盖并准确分类不存在错误。
     */
    default Optional<byte[]> readIfExists(String storagePath) throws IOException {
        return Optional.of(read(storagePath));
    }

    /**
     * 仅当 key 尚不存在时原子写入；已存在返回 false，绝不覆盖。
     * 默认不支持，避免用“先查后写”伪装原子语义。
     */
    default boolean storeAtIfAbsent(String storagePath, byte[] data) throws IOException {
        throw new UnsupportedOperationException("atomic store-if-absent is not supported");
    }

    /**
     * 按相对 key 删除文件。
     *
     * <p>对象/文件不存在时<b>静默返回</b>（幂等）——调用方多为"删记录顺带清文件"的清理链路，
     * 文件早已不在不该算失败。真实的删除失败（权限、网络）仍抛异常交调用方决定是否兜底。</p>
     *
     * @param storagePath {@link #store} 返回并落 DB 的相对 key
     * @throws IOException 删除失败（不含"本就不存在"）
     */
    void delete(String storagePath) throws IOException;
}
