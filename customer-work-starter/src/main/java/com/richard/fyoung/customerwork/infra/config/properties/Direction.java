package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 敏感词过滤生效方向。 */
public enum Direction {
    INBOUND, OUTBOUND, BOTH
}
