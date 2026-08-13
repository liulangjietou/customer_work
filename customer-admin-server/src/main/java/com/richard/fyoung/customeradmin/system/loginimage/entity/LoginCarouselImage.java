package com.richard.fyoung.customeradmin.system.loginimage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录页轮播背景图。原图存 MinIO（走 AttachmentFileStorage SPI，项目内不落盘），
 * {@code imageUrl} 存对外访问的相对 URL（{@code /api/login-images/xxx.jpg}），
 * 登录页免鉴权实时拉取启用中的图片列表。
 * @author owlzhangfq@gmail.com
 */
@Data
@TableName("login_carousel_image")
public class LoginCarouselImage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传时的原始文件名，仅用于管理页展示。 */
    private String imageName;
    /** 对外访问的相对 URL：/api/login-images/{uuid}.{ext}。 */
    private String imageUrl;
    /** 轮播顺序，小的在前。 */
    private Integer sortOrder;
    /** 0禁用 / 1启用，禁用的不出现在登录页。 */
    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private Long createBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableLogic
    private Integer deleted;
}
