package com.richard.fyoung.customerwork.infra.config.properties;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 交互协议配置（AG-UI / TTS）。 */
@Data
public class ProtocolProperties {
    private final Agui agui = new Agui();
    private final Tts tts = new Tts();

    /** AG-UI：标准 Agent-UI 事件协议（前端可直接消费的类型化事件流）。 */
    @Data
    public static class Agui {
        private boolean enabled = true;
        /** 是否在事件流中输出推理增量。 */
        private boolean enableReasoning = true;
        /** 是否输出工具调用参数事件。 */
        private boolean emitToolCallArgs = true;
    }

    /** TTS：语音合成（实时多模态）。需 DashScope 实时 TTS 模型与音频输出，默认关闭。 */
    @Data
    public static class Tts {
        private boolean enabled = false;
    }
}
