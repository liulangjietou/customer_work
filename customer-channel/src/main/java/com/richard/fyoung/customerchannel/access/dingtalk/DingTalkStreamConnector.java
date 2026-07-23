package com.richard.fyoung.customerchannel.access.dingtalk;

import com.fasterxml.jackson.databind.JsonNode;
import com.richard.fyoung.customerchannel.access.ChannelAccessConstants;
import com.richard.fyoung.customerchannel.access.ChannelMessagePipeline;
import com.richard.fyoung.customerchannel.access.model.ChannelInboundMessage;
import com.richard.fyoung.customerchannel.access.model.ChannelReplySender;
import com.richard.fyoung.customerchannel.access.model.ChannelRobot;
import com.richard.fyoung.customerchannel.access.spi.ImChannelConnector;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannelProperties;
import io.agentscope.extensions.channel.dingtalk.DingTalkStreamClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉 Stream 模式连接器（渠道接入层第一个实现）。
 *
 * <p>复用 AgentScope 的 {@link DingTalkStreamClient}（基于 JDK {@code java.net.http.WebSocket} 出站
 * 连接钉钉网关，注册 chatbot 消息 topic {@code /v1.0/im/bot/messages/get}，无需公网回调、零额外依赖）。
 * 收到原始 chatbot 消息 JSON 后：计算 externalUserId、判定文本、构造回复器（POST 本条消息回调里的
 * sessionWebhook），交给统一 {@link ChannelMessagePipeline} 处理。</p>
 * @author owlzhangfq@gmail.com
 */
public class DingTalkStreamConnector implements ImChannelConnector {

    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamConnector.class);

    /** 群聊会话类型标识（钉钉：1=单聊，2=群聊）。 */
    private static final String CONVERSATION_TYPE_GROUP = "2";
    /** 文本消息类型。 */
    private static final String MSG_TYPE_TEXT = "text";
    /** markdown 标题取正文前若干字符。 */
    private static final int MARKDOWN_TITLE_MAX = 12;
    /** sessionWebhook POST 超时。 */
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(10);

    private final ChannelRobot robot;
    private final ChannelMessagePipeline pipeline;
    private final WebClient replyWebClient;

    private volatile DingTalkStreamClient streamClient;
    private volatile boolean running;

    public DingTalkStreamConnector(ChannelRobot robot, ChannelMessagePipeline pipeline, WebClient replyWebClient) {
        this.robot = robot;
        this.pipeline = pipeline;
        this.replyWebClient = replyWebClient;
    }

    @Override
    public String channelType() {
        return ChannelAccessConstants.CHANNEL_TYPE_DINGTALK;
    }

    @Override
    public void start() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("appKey", robot.getAppKey());
        raw.put("appSecret", robot.getAppSecret());
        raw.put("robotCode", robot.getRobotCode());
        DingTalkChannelProperties props = DingTalkChannelProperties.from(
            ChannelAccessConstants.CHANNEL_TYPE_DINGTALK, raw);
        streamClient = new DingTalkStreamClient(props, this::onMessage);
        streamClient.start();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        DingTalkStreamClient client = this.streamClient;
        if (client != null) {
            client.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 处理钉钉网关下发的原始 chatbot 消息（该回调运行在 WebSocket 线程，须尽快交给管道异步处理）。
     */
    private void onMessage(JsonNode data) {
        if (data == null) {
            return;
        }
        String sessionWebhook = data.path("sessionWebhook").asText("");
        if (!StringUtils.hasText(sessionWebhook)) {
            // 无回复通道则无法应答，直接记日志丢弃
            log.error("dingtalk message missing sessionWebhook, code={}, appKey={}",
                ChannelAccessConstants.CODE_HANDLE_FAIL, robot.getAppKey());
            return;
        }
        String msgType = data.path("msgtype").asText("");
        boolean isText = MSG_TYPE_TEXT.equalsIgnoreCase(msgType);
        String content = data.path("text").path("content").asText("");
        String externalUserId = externalUserId(
            data.path("conversationType").asText(""),
            data.path("conversationId").asText(""),
            data.path("senderStaffId").asText(""));

        ChannelInboundMessage inbound = new ChannelInboundMessage(
            ChannelAccessConstants.CHANNEL_TYPE_DINGTALK, robot.getAppKey(), robot.getAgentCode(),
            externalUserId, isText, content);
        ChannelReplySender reply = replyText -> sendMarkdown(sessionWebhook, replyText);
        pipeline.submit(inbound, reply);
    }

    /**
     * externalUserId 规则：单聊=senderStaffId；群聊=conversationId(openConversationId)#senderStaffId。
     * 静态无副作用，便于单测。
     */
    static String externalUserId(String conversationType, String conversationId, String senderStaffId) {
        if (CONVERSATION_TYPE_GROUP.equals(conversationType)) {
            return conversationId + "#" + senderStaffId;
        }
        return senderStaffId;
    }

    /** 向 sessionWebhook POST markdown 回复。 */
    private void sendMarkdown(String sessionWebhook, String text) {
        String body = text == null ? "" : text;
        Map<String, Object> markdown = new LinkedHashMap<>();
        markdown.put("title", markdownTitle(body));
        markdown.put("text", body);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        payload.put("markdown", markdown);
        try {
            replyWebClient.post()
                .uri(sessionWebhook)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(Void.class)
                .block(REPLY_TIMEOUT);
        } catch (Exception e) {
            log.error("dingtalk reply failed, code={}, appKey={}",
                ChannelAccessConstants.CODE_REPLY_FAIL, robot.getAppKey(), e);
        }
    }

    /** markdown 标题取正文前若干字（去换行）。 */
    private String markdownTitle(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return "回复";
        }
        return flat.length() <= MARKDOWN_TITLE_MAX ? flat : flat.substring(0, MARKDOWN_TITLE_MAX);
    }
}
