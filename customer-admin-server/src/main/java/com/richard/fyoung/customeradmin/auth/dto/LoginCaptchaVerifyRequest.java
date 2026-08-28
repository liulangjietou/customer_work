package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 登录滑块轨迹核验请求。 */
public record LoginCaptchaVerifyRequest(
    @NotBlank(message = "challengeId 不能为空")
    @Size(max = 128, message = "challengeId 长度不能超过 128") String challengeId,
    @NotNull(message = "trajectory 不能为空")
    @Size(min = LoginCaptchaProtocol.MIN_POINTS, max = LoginCaptchaProtocol.MAX_POINTS,
        message = "trajectory 点数必须在 6 到 80 之间")
    List<@Valid SliderTrackPoint> trajectory) {
}
