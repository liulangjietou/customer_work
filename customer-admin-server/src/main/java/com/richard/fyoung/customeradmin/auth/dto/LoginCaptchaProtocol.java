package com.richard.fyoung.customeradmin.auth.dto;

/**
 * 登录拼图前后端共同遵守的固定协议边界。
 *
 * <p>这些值决定请求结构与交互行为，不作为运行期配置开放；修改时必须同步前端并按接口变更评审。
 * 运维侧只允许调整 TTL、签发限流和进程内容量等不改变协议的数据。</p>
 *
 * @author owlzhangfq@gmail.com
 */
public final class LoginCaptchaProtocol {

    public static final int TRACK_MIN_X = 0;
    public static final int TRACK_MAX_X = 1_000;
    public static final int TRACK_MIN_Y = -1_000;
    public static final int TRACK_MAX_Y = 1_000;
    public static final int MIN_POINTS = 6;
    public static final int MAX_POINTS = 80;
    public static final long MIN_DURATION_MS = 300L;
    public static final long MAX_DURATION_MS = 8_000L;
    public static final int START_X_MAX = 20;
    /** 最终轨迹点与显式放置位置允许的归一化坐标误差。 */
    public static final int ENDPOINT_PLACEMENT_TOLERANCE = 12;
    public static final int MIN_DISTINCT_X = 5;
    public static final int MIN_INTERMEDIATE_POINTS = 4;
    public static final long MAX_INITIAL_TIME_MS = 100L;

    private LoginCaptchaProtocol() {
    }
}
