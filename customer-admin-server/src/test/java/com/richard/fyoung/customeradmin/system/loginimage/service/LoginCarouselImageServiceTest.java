package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginCarouselImageVO;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageReorderRequest;
import com.richard.fyoung.customeradmin.system.loginimage.entity.LoginCarouselImage;
import com.richard.fyoung.customeradmin.system.loginimage.mapper.LoginCarouselImageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LoginCarouselImageService} 单测：上传追加排序/数量上限、启用图 URL 过滤、
 * 排序重写与 id 集合一致性校验、删除联动清理文件与不存在 fast fail。
 * {@link LoginCarouselImageMapper} 与 {@link LoginImageStorageService} 均用 mock（不依赖真实库与磁盘）。
 * @author owlzhangfq@gmail.com
 */
class LoginCarouselImageServiceTest {

    private LoginCarouselImageMapper imageMapper;
    private LoginImageStorageService storageService;
    private LoginCarouselImageService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), LoginCarouselImage.class);
    }

    @BeforeEach
    void setUp() {
        imageMapper = mock(LoginCarouselImageMapper.class);
        storageService = mock(LoginImageStorageService.class);
        service = new LoginCarouselImageService(imageMapper, storageService);
    }

    private LoginCarouselImage image(long id, int sortOrder, int enabled) {
        LoginCarouselImage image = new LoginCarouselImage();
        image.setId(id);
        image.setImageName("bg-" + id + ".jpg");
        image.setImageUrl("/api/login-images/uuid-" + id + ".jpg");
        image.setSortOrder(sortOrder);
        image.setEnabled(enabled);
        return image;
    }

    @Test
    void upload_shouldAppendToTailWithEnabled() {
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1L, 1, 1), image(2L, 2, 0))));
        when(storageService.store(any())).thenReturn("/api/login-images/new-uuid.jpg");

        LoginCarouselImageVO vo = service.upload(
            new MockMultipartFile("file", "sunset.jpg", "image/jpeg", new byte[] {1}));

        ArgumentCaptor<LoginCarouselImage> captor = ArgumentCaptor.forClass(LoginCarouselImage.class);
        verify(imageMapper).insert(captor.capture());
        assertEquals(3, captor.getValue().getSortOrder());
        assertEquals(1, captor.getValue().getEnabled());
        assertEquals("sunset.jpg", captor.getValue().getImageName());
        assertEquals("/api/login-images/new-uuid.jpg", vo.getImageUrl());
        assertTrue(vo.getEnabled());
    }

    @Test
    void upload_shouldFailWhenReachMaxCount() {
        List<LoginCarouselImage> full = IntStream.rangeClosed(1, 10)
            .mapToObj(i -> image(i, i, 1))
            .collect(Collectors.toList());
        when(imageMapper.selectList(any())).thenReturn(full);

        assertThrows(BizException.class, () -> service.upload(
            new MockMultipartFile("file", "extra.jpg", "image/jpeg", new byte[] {1})));
        verify(storageService, never()).store(any());
        verify(imageMapper, never()).insert(any(LoginCarouselImage.class));
    }

    @Test
    void listEnabledUrls_shouldFilterDisabledAndKeepOrder() {
        when(imageMapper.selectList(any()))
            .thenReturn(new ArrayList<>(List.of(image(1L, 1, 1), image(2L, 2, 0), image(3L, 3, 1))));

        List<String> urls = service.listEnabledUrls();

        assertEquals(List.of("/api/login-images/uuid-1.jpg", "/api/login-images/uuid-3.jpg"), urls);
    }

    @Test
    void reorder_shouldRewriteSortOrderByIndex() {
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1L, 1, 1), image(2L, 2, 1))));

        service.reorder(new LoginImageReorderRequest(List.of(2L, 1L)));

        ArgumentCaptor<LoginCarouselImage> captor = ArgumentCaptor.forClass(LoginCarouselImage.class);
        verify(imageMapper, times(2)).updateById(captor.capture());
        assertEquals(2L, captor.getAllValues().get(0).getId());
        assertEquals(1, captor.getAllValues().get(0).getSortOrder());
        assertEquals(1L, captor.getAllValues().get(1).getId());
        assertEquals(2, captor.getAllValues().get(1).getSortOrder());
    }

    @Test
    void reorder_shouldFailWhenIdsMismatch() {
        when(imageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(image(1L, 1, 1), image(2L, 2, 1))));

        assertThrows(BizException.class, () -> service.reorder(new LoginImageReorderRequest(List.of(2L, 3L))));
        verify(imageMapper, never()).updateById(any(LoginCarouselImage.class));
    }

    @Test
    void delete_shouldRemoveRecordAndFile() {
        when(imageMapper.selectById(1L)).thenReturn(image(1L, 1, 1));

        service.delete(1L);

        verify(imageMapper).deleteById(1L);
        verify(storageService).delete("/api/login-images/uuid-1.jpg");
    }

    @Test
    void delete_shouldFailWhenNotFound() {
        when(imageMapper.selectById(99L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.delete(99L));
        verify(imageMapper, never()).deleteById(99L);
    }

    @Test
    void updateEnabled_shouldFailWhenNotFound() {
        when(imageMapper.selectById(99L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.updateEnabled(99L, false));
    }
}
