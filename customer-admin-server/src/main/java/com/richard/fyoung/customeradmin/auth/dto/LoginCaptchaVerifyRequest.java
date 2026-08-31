package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 登录拼图落点与轨迹核验请求。 */
public record LoginCaptchaVerifyRequest(
    @NotBlank(message = "challengeId 不能为空")
    @Size(max = 128, message = "challengeId 长度不能超过 128") String challengeId,
    @NotNull(message = "placementX 不能为空")
    @Min(value = LoginCaptchaProtocol.TRACK_MIN_X, message = "placementX 不能小于 0")
    @Max(value = LoginCaptchaProtocol.TRACK_MAX_X, message = "placementX 不能大于 1000")
    Integer placementX,
    @NotNull(message = "trajectory 不能为空")
    @Size(min = LoginCaptchaProtocol.MIN_POINTS, max = LoginCaptchaProtocol.MAX_POINTS,
        message = "trajectory 点数必须在 6 到 80 之间")
    List<@Valid SliderTrackPoint> trajectory) {
}
