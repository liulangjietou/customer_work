package com.richard.fyoung.customeradmin.aiconfig.skill.dto;

/**
 * 导出的技能包：建议文件名 + zip 字节。
 *
 * <p>文件名在 Service 层定（要按 skillCode 净化出合法文件名，那是业务规则），
 * Controller 只负责把它写进 {@code Content-Disposition}。</p>
 *
 * @param filename 建议的下载文件名，形如 {@code my-skill.zip}
 * @param content  zip 字节
 * @author owlzhangfq@gmail.com
 */
public record SkillExportPackage(String filename, byte[] content) {
}
