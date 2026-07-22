package com.richard.fyoung.customeradmin.workbench.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richard.fyoung.customeradmin.common.crypto.AesGcmCryptoUtil;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.page.PageQuery;
import com.richard.fyoung.customeradmin.common.page.PageResult;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteSaveRequest;
import com.richard.fyoung.customeradmin.workbench.dto.WorkbenchSiteVO;
import com.richard.fyoung.customeradmin.workbench.entity.WorkbenchSite;
import com.richard.fyoung.customeradmin.workbench.mapper.WorkbenchSiteMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkbenchSiteService} 单测：分页 VO 映射、密码加密落库/留空不改、重名与不存在的 fast fail、
 * 明文密码解密。{@link WorkbenchSiteMapper} 用 mock 代替（不依赖真实库），加解密用真实
 * {@link AesGcmCryptoUtil}（16 字节测试密钥）验证密文可被同一 util 解回明文。
 * @author owlzhangfq@gmail.com
 */
class WorkbenchSiteServiceTest {

    private static final String PLAIN_PASSWORD = "s3cr3t-pass";

    private WorkbenchSiteMapper siteMapper;
    private AesGcmCryptoUtil cryptoUtil;
    private WorkbenchSiteService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), WorkbenchSite.class);
    }

    @BeforeEach
    void setUp() {
        siteMapper = mock(WorkbenchSiteMapper.class);
        cryptoUtil = new AesGcmCryptoUtil("0123456789abcdef0123456789abcdef");
        service = new WorkbenchSiteService(siteMapper, cryptoUtil);
    }

    private WorkbenchSiteSaveRequest request(String name, String password) {
        return new WorkbenchSiteSaveRequest(name, "git", "https://git.internal", "admin", password, "备注", true,
            null, null, null, null, null, null, null);
    }

    @Test
    void page_shouldMapEntitiesToVO_withMaskAndHasPassword() {
        WorkbenchSite site = new WorkbenchSite();
        site.setId(1L);
        site.setName("Gitlab");
        site.setCategory("git");
        site.setUrl("https://git.internal");
        site.setAccount("admin");
        site.setPassword(cryptoUtil.encrypt(PLAIN_PASSWORD));
        site.setEnabled(1);
        Page<WorkbenchSite> page = new Page<>(1, 10);
        page.setRecords(List.of(site));
        page.setTotal(1);
        when(siteMapper.selectPage(any(IPage.class), any())).thenReturn(page);

        PageQuery query = new PageQuery();
        query.setKeyword("git");
        PageResult<WorkbenchSiteVO> result = service.page(query);

        assertEquals(1, result.getTotal());
        WorkbenchSiteVO vo = result.getList().get(0);
        assertEquals("Gitlab", vo.getName());
        assertEquals(Boolean.TRUE, vo.getHasPassword());
        // 掩码只保留末 4 位，绝不出现明文
        assertNotEquals(PLAIN_PASSWORD, vo.getPasswordMasked());
        assertEquals("*******pass", vo.getPasswordMasked());
    }

    @Test
    void create_shouldEncryptPassword_notStorePlaintext() {
        when(siteMapper.exists(any())).thenReturn(false);

        service.create(request("Gitlab", PLAIN_PASSWORD));

        ArgumentCaptor<WorkbenchSite> captor = ArgumentCaptor.forClass(WorkbenchSite.class);
        verify(siteMapper).insert(captor.capture());
        WorkbenchSite saved = captor.getValue();
        assertNotEquals(PLAIN_PASSWORD, saved.getPassword(), "落库密码必须是密文");
        assertEquals(PLAIN_PASSWORD, cryptoUtil.decrypt(saved.getPassword()), "密文应能被同一 util 解回明文");
    }

    @Test
    void create_shouldThrowDuplicate_whenNameExists() {
        when(siteMapper.exists(any())).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.create(request("Gitlab", PLAIN_PASSWORD)));
        assertEquals(ResultCode.RESOURCE_DUPLICATE, ex.getResultCode());
        verify(siteMapper, never()).insert(any(WorkbenchSite.class));
    }

    @Test
    void update_shouldKeepOriginalPassword_whenRequestPasswordBlank() {
        WorkbenchSite existing = new WorkbenchSite();
        existing.setId(1L);
        existing.setName("Gitlab");
        String originalCipher = cryptoUtil.encrypt(PLAIN_PASSWORD);
        existing.setPassword(originalCipher);
        when(siteMapper.selectById(1L)).thenReturn(existing);
        when(siteMapper.exists(any())).thenReturn(false);

        service.update(1L, request("Gitlab", null));

        ArgumentCaptor<WorkbenchSite> captor = ArgumentCaptor.forClass(WorkbenchSite.class);
        verify(siteMapper).updateById(captor.capture());
        assertEquals(originalCipher, captor.getValue().getPassword(), "留空不改密码，密文应保持原值");
    }

    @Test
    void update_shouldReEncryptPassword_whenRequestPasswordPresent() {
        WorkbenchSite existing = new WorkbenchSite();
        existing.setId(1L);
        existing.setName("Gitlab");
        existing.setPassword(cryptoUtil.encrypt("old-pass"));
        when(siteMapper.selectById(1L)).thenReturn(existing);
        when(siteMapper.exists(any())).thenReturn(false);

        service.update(1L, request("Gitlab", "new-pass"));

        ArgumentCaptor<WorkbenchSite> captor = ArgumentCaptor.forClass(WorkbenchSite.class);
        verify(siteMapper).updateById(captor.capture());
        assertEquals("new-pass", cryptoUtil.decrypt(captor.getValue().getPassword()));
    }

    @Test
    void delete_shouldThrowNotFound_whenMissing() {
        when(siteMapper.selectById(1L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.delete(1L));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
        verify(siteMapper, never()).deleteById(any(Long.class));
    }

    @Test
    void getSecret_shouldDecryptPassword() {
        WorkbenchSite site = new WorkbenchSite();
        site.setId(1L);
        site.setPassword(cryptoUtil.encrypt(PLAIN_PASSWORD));
        when(siteMapper.selectById(1L)).thenReturn(site);

        assertEquals(PLAIN_PASSWORD, service.getSecret(1L));
    }

    @Test
    void getSecret_shouldThrowNotFound_whenNoPassword() {
        WorkbenchSite site = new WorkbenchSite();
        site.setId(1L);
        site.setPassword(null);
        when(siteMapper.selectById(1L)).thenReturn(site);

        BizException ex = assertThrows(BizException.class, () -> service.getSecret(1L));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void create_shouldLeavePasswordNull_whenRequestPasswordBlank() {
        when(siteMapper.exists(any())).thenReturn(false);

        service.create(request("Jenkins", null));

        ArgumentCaptor<WorkbenchSite> captor = ArgumentCaptor.forClass(WorkbenchSite.class);
        verify(siteMapper).insert(captor.capture());
        assertNull(captor.getValue().getPassword(), "未提供密码时不应写入任何密文");
    }

    @Test
    void create_shouldFallbackLoginConfigDefaults_whenBlank() {
        when(siteMapper.exists(any())).thenReturn(false);

        service.create(request("Gitlab", PLAIN_PASSWORD));

        ArgumentCaptor<WorkbenchSite> captor = ArgumentCaptor.forClass(WorkbenchSite.class);
        verify(siteMapper).insert(captor.capture());
        WorkbenchSite saved = captor.getValue();
        assertEquals("auto", saved.getFillMode());
        assertEquals("click", saved.getSubmitMode());
        assertEquals(500, saved.getInitDelayMs());
        assertEquals(300, saved.getSubmitDelayMs());
    }

    @Test
    void findAgentSiteByHost_shouldReturnDecryptedPassword_forExactHostMatch() {
        WorkbenchSite site = new WorkbenchSite();
        site.setUrl("https://git.example.com/login");
        site.setAccount("test-user");
        site.setPassword(cryptoUtil.encrypt(PLAIN_PASSWORD));
        site.setFillMode("auto");
        // 同时返回一个 host 是子串但不精确的干扰项，验证 URI 精确匹配
        WorkbenchSite noise = new WorkbenchSite();
        noise.setUrl("https://xgit.example.com/login");
        when(siteMapper.selectList(any())).thenReturn(List.of(noise, site));

        var vo = service.findAgentSiteByHost("git.example.com");

        assertEquals("test-user", vo.getAccount());
        assertEquals(PLAIN_PASSWORD, vo.getPassword());
    }

    @Test
    void findAgentSiteByHost_shouldThrowNotFound_whenNoExactMatch() {
        WorkbenchSite noise = new WorkbenchSite();
        noise.setUrl("https://xgit.example.com/login");
        when(siteMapper.selectList(any())).thenReturn(List.of(noise));

        BizException ex = assertThrows(BizException.class,
            () -> service.findAgentSiteByHost("git.example.com"));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, ex.getResultCode());
    }

    @Test
    void listEnabledHosts_shouldParseAndDedupHosts() {
        WorkbenchSite a = new WorkbenchSite();
        a.setUrl("https://wiki.example.com/");
        WorkbenchSite b = new WorkbenchSite();
        b.setUrl("https://wiki.example.com/dashboard"); // 同 host 应去重
        WorkbenchSite c = new WorkbenchSite();
        c.setUrl("http://gitlab.example.com");
        when(siteMapper.selectList(any())).thenReturn(List.of(a, b, c));

        List<String> hosts = service.listEnabledHosts();

        assertEquals(List.of("wiki.example.com", "gitlab.example.com"), hosts);
    }
}
