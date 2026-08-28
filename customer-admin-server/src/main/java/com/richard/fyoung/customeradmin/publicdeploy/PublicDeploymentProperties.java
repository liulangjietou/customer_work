package com.richard.fyoung.customeradmin.publicdeploy;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对外开放部署形态开关。
 *
 * <p>同一份代码要同时支撑两种部署：内网自用实例（同事可信、内部工具是主力功能）与
 * 对外开放实例（陌生人可自助注册）。二者的差别不只是“配置紧一点”，而是若干能力
 * 在对外实例上<b>根本不该存在</b>——留着只靠权限点挡，迟早有人配错角色。</p>
 *
 * <p>打开后一次性生效三件事，避免上线时逐项勾选漏掉其中一条：</p>
 * <ol>
 *   <li>内部运维工具（SQL 客户端、账号本、开发者工具箱，见
 *       {@code ControlPlanePermissions.internalToolFamilies()}）的菜单不再下发、接口直接拒绝；</li>
 *   <li>自助注册强制图形验证码与 IP 限流，不接受配置为关；</li>
 *   <li>注册审核通过时要求给出归属租户，不允许并入平台自用的 {@code default} 租户。</li>
 * </ol>
 * @author owlzhangfq@gmail.com
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "admin.public-deployment")
public class PublicDeploymentProperties {

    /**
     * 是否为对外开放实例。默认 {@code false}（内网自用）。
     *
     * <p>默认值刻意保守：把内网实例误当成对外实例，只会让内部工具消失、运维骂街；
     * 反过来把对外实例误当成内网实例，等于把 SQL 客户端和账号本挂到公网上。</p>
     */
    private boolean enabled = false;
}
