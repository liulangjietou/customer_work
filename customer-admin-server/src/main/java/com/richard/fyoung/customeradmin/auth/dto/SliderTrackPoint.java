package com.richard.fyoung.customeradmin.auth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 滑块轨迹采样点。X 使用 0..1000 归一化坐标，Y 是相对按下点的偏移，T 是相对毫秒数。
 * @author owlzhangfq@gmail.com
 */
public record SliderTrackPoint(
    @Min(value = LoginCaptchaProtocol.TRACK_MIN_X, message = "x 不能小于 0")
    @Max(value = LoginCaptchaProtocol.TRACK_MAX_X, message = "x 不能大于 1000")
    int x,
    @Min(value = LoginCaptchaProtocol.TRACK_MIN_Y, message = "y 不能小于 -1000")
    @Max(value = LoginCaptchaProtocol.TRACK_MAX_Y, message = "y 不能大于 1000")
    int y,
    @PositiveOrZero(message = "t 不能为负数")
    long t) {
}
