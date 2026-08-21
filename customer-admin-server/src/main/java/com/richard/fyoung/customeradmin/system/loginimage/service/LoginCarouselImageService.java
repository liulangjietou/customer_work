package com.richard.fyoung.customeradmin.system.loginimage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richard.fyoung.customeradmin.common.exception.BizException;
import com.richard.fyoung.customeradmin.common.result.ResultCode;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginCarouselImageVO;
import com.richard.fyoung.customeradmin.system.loginimage.dto.LoginImageReorderRequest;
import com.richard.fyoung.customeradmin.system.loginimage.entity.LoginCarouselImage;
import com.richard.fyoung.customeradmin.system.loginimage.mapper.LoginCarouselImageMapper;
import com.richard.fyoung.customerwork.core.constant.StatusFlags;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 登录页轮播图管理：上传/列表/启停/排序/删除，以及登录页免鉴权拉取的启用图列表。
 *
 * <p>格式/大小校验与落盘收敛在 {@link LoginImageStorageService}，本服务只管 DB 记录与业务规则
 * （数量上限、排序重写、删除时联动清理磁盘文件）。</p>
 * @author owlzhangfq@gmail.com
 */
@Service
public class LoginCarouselImageService {

    /** 轮播图数量上限：登录页轮播超过这个数没有展示价值，也防目录被无限制堆积。 */
    private static final int MAX_IMAGE_COUNT = 10;

    private final LoginCarouselImageMapper imageMapper;
    private final LoginImageStorageService storageService;

    public LoginCarouselImageService(LoginCarouselImageMapper imageMapper, LoginImageStorageService storageService) {
        this.imageMapper = imageMapper;
        this.storageService = storageService;
    }

    /** 管理页列表：全量按 sortOrder 升序（含禁用的，禁用状态由前端标识展示）。 */
    public List<LoginCarouselImageVO> list() {
        return listOrdered().stream().map(this::toVo).collect(Collectors.toList());
    }

    /** 登录页免鉴权实时拉取：仅启用图的访问 URL，按 sortOrder 升序。 */
    public List<String> listEnabledUrls() {
        return listOrdered().stream()
            .filter(image -> image.getEnabled() != null && image.getEnabled() == StatusFlags.ENABLED)
            .map(LoginCarouselImage::getImageUrl)
            .collect(Collectors.toList());
    }

    /** 存量轮播图没有独立对象名前缀，只允许仍被业务记录精确引用的 URL 公开读取。 */
    public boolean isReferencedImageUrl(String imageUrl) {
        return imageMapper.exists(new LambdaQueryWrapper<LoginCarouselImage>()
            .eq(LoginCarouselImage::getImageUrl, imageUrl));
    }

    /** 上传新图：落盘后追加到当前排序末尾，默认启用。 */
    public LoginCarouselImageVO upload(MultipartFile file) {
        List<LoginCarouselImage> existing = listOrdered();
        if (existing.size() >= MAX_IMAGE_COUNT) {
            throw new BizException(ResultCode.PARAM_INVALID, "轮播图数量已达上限 " + MAX_IMAGE_COUNT + " 张，请先删除旧图");
        }
        String imageUrl = storageService.store(file);

        LoginCarouselImage image = new LoginCarouselImage();
        image.setImageName(file.getOriginalFilename());
        image.setImageUrl(imageUrl);
        image.setSortOrder(existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getSortOrder() + 1);
        image.setEnabled(StatusFlags.ENABLED);
        imageMapper.insert(image);
        return toVo(image);
    }

    public void updateEnabled(Long id, boolean enabled) {
        LoginCarouselImage image = requireImage(id);
        image.setEnabled(enabled ? StatusFlags.ENABLED : 0);
        imageMapper.updateById(image);
    }

    /**
     * 按前端传来的完整 id 顺序重写 sortOrder。要求 id 集合与库内完全一致，
     * 防止并发编辑下按过期列表排序造成部分记录顺序丢失。
     */
    public void reorder(LoginImageReorderRequest request) {
        List<LoginCarouselImage> existing = listOrdered();
        Set<Long> existingIds = existing.stream().map(LoginCarouselImage::getId).collect(Collectors.toSet());
        Set<Long> requestIds = request.ids().stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (!existingIds.equals(requestIds)) {
            throw new BizException(ResultCode.PARAM_INVALID, "排序列表与当前数据不一致，请刷新后重试");
        }
        for (int i = 0; i < request.ids().size(); i++) {
            LoginCarouselImage image = new LoginCarouselImage();
            image.setId(request.ids().get(i));
            image.setSortOrder(i + 1);
            imageMapper.updateById(image);
        }
    }

    /** 删除记录（逻辑删除）并联动清理磁盘文件（清理失败不中断，见 storage 侧说明）。 */
    public void delete(Long id) {
        LoginCarouselImage image = requireImage(id);
        imageMapper.deleteById(id);
        storageService.delete(image.getImageUrl());
    }

    private List<LoginCarouselImage> listOrdered() {
        List<LoginCarouselImage> images = imageMapper.selectList(
            new LambdaQueryWrapper<LoginCarouselImage>().orderByAsc(LoginCarouselImage::getSortOrder));
        return CollectionUtils.isEmpty(images) ? new ArrayList<>() : images;
    }

    private LoginCarouselImage requireImage(Long id) {
        LoginCarouselImage image = imageMapper.selectById(id);
        if (image == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "轮播图不存在: " + id);
        }
        return image;
    }

    private LoginCarouselImageVO toVo(LoginCarouselImage image) {
        LoginCarouselImageVO vo = new LoginCarouselImageVO();
        vo.setId(image.getId());
        vo.setImageName(image.getImageName());
        vo.setImageUrl(image.getImageUrl());
        vo.setSortOrder(image.getSortOrder());
        vo.setEnabled(image.getEnabled() != null && image.getEnabled() == StatusFlags.ENABLED);
        vo.setCreateTime(image.getCreateTime());
        vo.setUpdateTime(image.getUpdateTime());
        return vo;
    }
}
