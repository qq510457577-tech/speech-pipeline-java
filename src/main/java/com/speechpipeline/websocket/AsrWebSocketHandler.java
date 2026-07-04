package com.speechpipeline.websocket;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speechpipeline.config.AliConfig;
import com.speechpipeline.config.NlsClientManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 实时转写端点 — Spring WebSocket 实现
 * 接收浏览器通过 AudioContext 捕获的原始 PCM 16kHz 音频流
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrWebSocketHandler implements WebSocketHandler {

    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 每个会话的当前识别文本 */
    private static final Map<WebSocketSession, StringBuilder> CURRENT_TEXTS = new ConcurrentHashMap<>();
    /** 每个会话的转写器 */
    private static final Map<WebSocketSession, SpeechTranscriber> TRANSCRIBERS = new ConcurrentHashMap<>();
    /** 每个会话累计发送到 ASR 的音频字节数 */
    private static final Map<WebSocketSession, Long> AUDIO_BYTES = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        CURRENT_TEXTS.put(session, new StringBuilder());
        AUDIO_BYTES.put(session, 0L);
        log.info("WebSocket 已连接: {}", session.getId());
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        try {
            Object payload = message.getPayload();
            if (payload instanceof String) {
                handleTextMessage(session, (String) payload);
            } else if (payload instanceof byte[]) {
                handleBinaryMessage(session, ByteBuffer.wrap((byte[]) payload));
            } else if (payload instanceof ByteBuffer) {
                handleBinaryMessage(session, (ByteBuffer) payload);
            }
        } catch (Exception e) {
            log.error("WebSocket 消息处理失败: {}", e.getMessage(), e);
        }
    }

    private void handleTextMessage(WebSocketSession session, String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);

            // 停止指令
            if (json.has("stop") && json.get("stop").asBoolean(false)) {
                log.info("收到停止指令");
                stopASR(session);
                return;
            }

            // 重新配置指令 (如模型切换)
            if (json.has("configure")) {
                log.info("收到重新配置指令");
                configureASR(session);
                return;
            }

            // 如果包含 audio base64 字段，解码后发送
            if (json.has("audio")) {
                String audioBase64 = json.get("audio").asText();
                if (audioBase64 != null && !audioBase64.isEmpty()) {
                    byte[] audioBytes = java.util.Base64.getDecoder().decode(audioBase64);
                    sendAudioToASR(session, audioBytes);
                }
            }
        } catch (Exception e) {
            log.error("处理文本消息失败: {}", e.getMessage(), e);
        }
    }

    private void handleBinaryMessage(WebSocketSession session, ByteBuffer payload) {
        byte[] audioBytes = new byte[payload.remaining()];
        payload.get(audioBytes);
        sendAudioToASR(session, audioBytes);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: {}", exception.getMessage(), exception);
        cleanupSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        log.info("WebSocket 已断开: {} (原因: {})", session.getId(), closeStatus);
        cleanupSession(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    /**
     * 发送 JSON 消息（使用 Jackson 避免注入攻击）
     */
    private void sendJson(WebSocketSession session, String type, Map<String, Object> data) {
        if (session == null || !session.isOpen()) return;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", type);
            if (data != null) {
                msg.putAll(data);
            }
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
        } catch (IOException e) {
            log.error("推送消息失败: {}", e.getMessage());
        }
    }

    private void sendJson(WebSocketSession session, String type, String text) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (text != null) {
            data.put("text", text);
        }
        sendJson(session, type, data);
    }

    /**
     * 获取或创建转写器
     */
    private SpeechTranscriber getOrCreateTranscriber(WebSocketSession session) {
        synchronized (TRANSCRIBERS) {
            SpeechTranscriber existing = TRANSCRIBERS.get(session);
            if (existing != null) return existing;

            try {
                SpeechTranscriberListener listener = createListener(session);
                SpeechTranscriber transcriber = new SpeechTranscriber(
                    nlsClientManager.getNlsClient(), listener);
                transcriber.setAppKey(aliConfig.getAppKey());
                transcriber.setFormat(InputFormatEnum.PCM);
                transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
                transcriber.setEnableIntermediateResult(true);
                transcriber.setEnablePunctuation(true);
                transcriber.setEnableITN(true);
                if (aliConfig.getAsrModel() != null && !aliConfig.getAsrModel().isBlank()) {
                    transcriber.addCustomedParam("model", aliConfig.getAsrModel());
                }
                transcriber.addCustomedParam("returnSentenceLevel", true);

                transcriber.start();
                log.info("ASR 转写器已创建: {}", session.getId());
                TRANSCRIBERS.put(session, transcriber);
                return transcriber;
            } catch (Exception e) {
                log.error("启动 ASR 失败: {}", e.getMessage(), e);
                return null;
            }
        }
    }

    /**
     * 重新配置转写器（关闭旧的，创建新的）
     */
    private void configureASR(WebSocketSession session) {
        SpeechTranscriber transcriber = TRANSCRIBERS.remove(session);
        if (transcriber != null) {
            try {
                transcriber.stop();
                transcriber.close();
            } catch (Exception e) {
                log.warn("关闭旧转写器失败: {}", e.getMessage());
            }
        }
        getOrCreateTranscriber(session);
        sendJson(session, "configured", (String) null);
    }

    /**
     * 发送音频到 ASR
     */
    private void sendAudioToASR(WebSocketSession session, byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) return;
        SpeechTranscriber transcriber = getOrCreateTranscriber(session);
        if (transcriber != null) {
            try {
                transcriber.send(pcmData);
                long total = AUDIO_BYTES.merge(session, (long) pcmData.length, Long::sum);
                if (total == pcmData.length || total % 32000 < pcmData.length) {
                    log.info("ASR 已接收音频流: session={}, totalBytes={}", session.getId(), total);
                }
            } catch (Exception e) {
                log.error("发送音频失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 创建 ASR 监听器
     */
    private SpeechTranscriberListener createListener(WebSocketSession session) {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                log.info("ASR 识别启动: task_id={}", response.getTaskId());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("taskId", response.getTaskId());
                sendJson(session, "start", data);
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("句子开始: index={}", response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (text != null && !text.isEmpty()) {
                    StringBuilder sb = CURRENT_TEXTS.get(session);
                    if (sb != null) {
                        appendText(sb, text);
                        log.info("句子识别完成 [{}]: '{}'", response.getTransSentenceIndex(), text);
                        sendJson(session, "sentence_end", sb.toString());
                    }
                }
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (text != null && !text.isEmpty()) {
                    StringBuilder sb = CURRENT_TEXTS.get(session);
                    if (sb != null) {
                        sendJson(session, "intermediate", combineText(sb, text));
                    }
                }
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.info("ASR 识别完成: task_id={}", response.getTaskId());
                StringBuilder sb = CURRENT_TEXTS.get(session);
                if (sb != null && sb.length() > 0) {
                    sendJson(session, "complete", sb.toString());
                }
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("ASR 识别失败: status={}, text={}", response.getStatus(), response.getStatusText());
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("status", response.getStatus());
                data.put("message", response.getStatusText());
                sendJson(session, "error", data);
            }
        };
    }

    /**
     * 停止 ASR 并发送最终结果
     */
    private void stopASR(WebSocketSession session) {
        SpeechTranscriber transcriber = TRANSCRIBERS.remove(session);
        if (transcriber != null) {
            try {
                transcriber.stop();
                transcriber.close();
            } catch (Exception e) {
                log.error("停止 ASR 失败: {}", e.getMessage());
            }
        }

        StringBuilder sb = CURRENT_TEXTS.get(session);
        String finalText = sb != null ? sb.toString() : "";
        sendJson(session, "final", finalText);
        log.info("ASR 已停止，最终结果: '{}'", finalText);
        CURRENT_TEXTS.remove(session);
        AUDIO_BYTES.remove(session);
    }

    /**
     * 清理会话资源
     */
    private void cleanupSession(WebSocketSession session) {
        SpeechTranscriber transcriber = TRANSCRIBERS.remove(session);
        if (transcriber != null) {
            try {
                transcriber.close();
            } catch (Exception e) {
                log.warn("关闭转写器失败: {}", e.getMessage());
            }
        }
        CURRENT_TEXTS.remove(session);
        AUDIO_BYTES.remove(session);
    }

    private String combineText(StringBuilder finalizedText, String liveText) {
        StringBuilder combined = new StringBuilder(finalizedText.toString());
        appendText(combined, liveText);
        return combined.toString();
    }

    private void appendText(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0 && !endsWithSentencePunctuation(sb)) {
            sb.append(' ');
        }
        sb.append(text.trim());
    }

    private boolean endsWithSentencePunctuation(StringBuilder sb) {
        char last = sb.charAt(sb.length() - 1);
        return last == '。' || last == '！' || last == '？'
                || last == '.' || last == '!' || last == '?'
                || last == '，' || last == ',' || last == '；' || last == ';';
    }
}
