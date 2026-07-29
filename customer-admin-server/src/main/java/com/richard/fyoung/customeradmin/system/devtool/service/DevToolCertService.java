package com.richard.fyoung.customeradmin.system.devtool.service;

import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertMatchResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCertParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolCsrInfo;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolKeystoreParseResponse;
import com.richard.fyoung.customeradmin.system.devtool.dto.DevToolPrivateKeyExportResponse;
import com.richard.fyoung.customerwork.tool.devtool.CertDevToolOps;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 开发者工具箱 · 证书解析（页面侧）。
 *
 * <p><b>解析能力不在本类</b>：核心实现是 starter 的 {@link CertDevToolOps}（纯函数、无 Spring 依赖），
 * 同一份实现同时供 {@code devtoolbox} 系统工具的 {@code cert_parse} / {@code cert_match} 暴露给智能体
 * ——页面与智能体走同一套解析逻辑，不存在两处实现漂移的可能。本类只做两件事：把 Ops 的结果对象转成
 * 页面 VO，把 {@link IllegalArgumentException} 转成 {@link BizException} 交给全局异常处理器。</p>
 *
 * <p>隐私边界继承自 Ops：输入只在请求内存中解析，不落库、不写日志。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class DevToolCertService {

    private final CertDevToolOps certOps = new CertDevToolOps();

    /** 解析 PEM 文本中的全部证书与 CSR 块（页面侧回显每张证书的 PEM，便于把证书链拆开取用）。 */
    public DevToolCertParseResponse parse(String pemContent) {
        CertDevToolOps.CertParseResult result = call(() -> certOps.parse(pemContent, true));

        List<DevToolCertInfo> certs = new ArrayList<>(result.getCertificates().size());
        for (CertDevToolOps.CertInfo info : result.getCertificates()) {
            certs.add(toCertVO(info));
        }
        List<DevToolCsrInfo> csrs = new ArrayList<>(result.getCsrs().size());
        for (CertDevToolOps.CsrInfo info : result.getCsrs()) {
            DevToolCsrInfo vo = new DevToolCsrInfo();
            vo.setSubject(info.getSubject());
            vo.setPublicKeyAlgorithm(info.getPublicKeyAlgorithm());
            vo.setPublicKeyBits(info.getPublicKeyBits());
            vo.setSigAlgName(info.getSigAlgName());
            vo.setSubjectAlternativeNames(info.getSubjectAlternativeNames());
            csrs.add(vo);
        }
        DevToolCertParseResponse response = new DevToolCertParseResponse();
        response.setCertificates(certs);
        response.setCsrs(csrs);
        return response;
    }

    /** 私钥与证书匹配校验。 */
    public DevToolCertMatchResponse match(String certPem, String privateKeyPem) {
        CertDevToolOps.CertMatchResult result = call(() -> certOps.match(certPem, privateKeyPem));
        return new DevToolCertMatchResponse(result.isMatched(), result.getPublicKeyAlgorithm(), result.getReason());
    }

    /** 解析 PFX/JKS 密钥库（页面独有：智能体没有文件上传通道，不在系统工具里暴露）。 */
    public DevToolKeystoreParseResponse parseKeystore(byte[] data, String password) {
        CertDevToolOps.KeystoreParseResult result = call(() -> certOps.parseKeystore(data, password));

        List<DevToolKeystoreParseResponse.Entry> entries = new ArrayList<>(result.getEntries().size());
        for (CertDevToolOps.KeystoreEntry entry : result.getEntries()) {
            List<DevToolCertInfo> chain = new ArrayList<>(entry.getChain().size());
            for (CertDevToolOps.CertInfo info : entry.getChain()) {
                chain.add(toCertVO(info));
            }
            DevToolKeystoreParseResponse.Entry vo = new DevToolKeystoreParseResponse.Entry();
            vo.setAlias(entry.getAlias());
            vo.setEntryType(entry.getEntryType());
            vo.setChain(chain);
            entries.add(vo);
        }
        DevToolKeystoreParseResponse response = new DevToolKeystoreParseResponse();
        response.setKeystoreType(result.getKeystoreType());
        response.setEntries(entries);
        return response;
    }

    /** 导出密钥库指定条目的私钥 PEM（由页面显式动作触发，不随条目列举一起返回）。 */
    public DevToolPrivateKeyExportResponse exportPrivateKey(byte[] data, String password, String alias,
                                                            String keyPassword) {
        CertDevToolOps.PrivateKeyExport export =
            call(() -> certOps.exportPrivateKeyPem(data, password, alias, keyPassword));
        return new DevToolPrivateKeyExportResponse(alias, export.getAlgorithm(), export.getPem());
    }

    /**
     * 异常转换单一收口：Ops 的入参/格式问题一律是 {@link IllegalArgumentException}，
     * 这里统一转成 {@link ResultCode#PARAM_INVALID}，各方法内不再重复 try-catch。
     */
    private <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, e.getMessage());
        }
    }

    private DevToolCertInfo toCertVO(CertDevToolOps.CertInfo info) {
        DevToolCertInfo vo = new DevToolCertInfo();
        vo.setPem(info.getPem());
        vo.setSubject(info.getSubject());
        vo.setIssuer(info.getIssuer());
        vo.setSerialNumberHex(info.getSerialNumberHex());
        vo.setVersion(info.getVersion());
        vo.setNotBeforeMs(info.getNotBeforeMs());
        vo.setNotAfterMs(info.getNotAfterMs());
        vo.setExpired(info.isExpired());
        vo.setDaysRemaining(info.getDaysRemaining());
        vo.setSigAlgName(info.getSigAlgName());
        vo.setPublicKeyAlgorithm(info.getPublicKeyAlgorithm());
        vo.setPublicKeyBits(info.getPublicKeyBits());
        vo.setCa(info.isCa());
        vo.setSubjectAlternativeNames(info.getSubjectAlternativeNames());
        vo.setKeyUsages(info.getKeyUsages());
        vo.setSha1Fingerprint(info.getSha1Fingerprint());
        vo.setSha256Fingerprint(info.getSha256Fingerprint());
        return vo;
    }
}
