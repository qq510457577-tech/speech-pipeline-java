package com.speechpipeline.tts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Higgs Audio v3 TTS — Boson AI (OpenAI-compatible API)
 *
 * API: POST https://api.boson.ai/v1/audio/speech
 * Auth: Bearer $BOSON_API_KEY
 * Features: preset voices, zero-shot cloning, inline emotion/style/sfx/prosody tags
 */
@Slf4j
@Component
public class HiggsTTS {

    private static final String DEFAULT_MODEL = "higgs-audio-v3-tts";

    @Value("${boson.api.key:}")
    private String apiKey;

    private final WebClient webClient;

    public HiggsTTS(WebClient.Builder webClientBuilder,
                    @Value("${boson.api.timeout:30}") int timeoutSeconds) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(timeoutSeconds));
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl("https://api.boson.ai")
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 语音合成 — 支持标签和零样本克隆
     *
     * @param text         待合成文本（可含标签，最长 5000 字符）
     * @param voice        音色名或自定义 voice ID，默认 "default"
     * @param speed        语速（Higgs v3 不支持，忽略）
     * @param responseFormat 输出格式: mp3/wav/pcm/aac/flac/opus，默认 mp3
     * @param refAudio     零样本克隆：HTTP URL / data URI / base64
     * @param refText      ref_audio 的文本转录
     * @return 音频字节数组
     */
    public byte[] synthesize(String text, String voice, double speed, String responseFormat,
                              String refAudio, String refText) {
        if (text == null || text.isEmpty()) {
            log.warn("TTS 文本为空");
            return new byte[0];
        }
        if (!isConfigured()) {
            log.error("Boson API Key 未配置 (boson.api.key)");
            return new byte[0];
        }
        if (text.length() > 5000) {
            log.warn("TTS 文本过长 ({} chars)，截断到 5000", text.length());
            text = text.substring(0, 5000);
        }

        String v = (voice != null && !voice.isEmpty()) ? voice : "default";
        String fmt = (responseFormat != null && !responseFormat.isEmpty()) ? responseFormat : "mp3";
        if (!fmt.matches("(mp3|wav|pcm|aac|flac|opus)")) fmt = "mp3";

        try {
            // 重试机制：Boson API 可能因限流返回 429，最多重试 3 次
            byte[] audioBytes = null;
            int maxRetries = 3;
            for (int attempt = 0; attempt < maxRetries; attempt++) {
                try {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("model", DEFAULT_MODEL);
                    body.put("input", text);
                    body.put("voice", v);
                    body.put("response_format", fmt);
                    // ref_audio / ref_text for zero-shot cloning (mutually exclusive with voice)
                    if (refAudio != null && !refAudio.isEmpty()) {
                        body.put("ref_audio", refAudio);
                        body.put("ref_text", refText != null ? refText : "");
                    }

                    Flux<DataBuffer> flux = webClient.post()
                            .uri("/v1/audio/speech")
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(status -> status.value() >= 400, response -> {
                                return response.bodyToMono(String.class)
                                        .flatMap(errorBody -> {
                                            log.error("Higgs TTS API 错误: HTTP {}, body={}", response.statusCode(), errorBody);
                                            return Mono.error(new RuntimeException("Higgs TTS API error: " + errorBody));
                                        });
                            })
                            .bodyToFlux(DataBuffer.class);

                    audioBytes = DataBufferUtils.join(flux)
                            .map(buffer -> {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                DataBufferUtils.release(buffer);
                                return bytes;
                            })
                            .block();

                    if (audioBytes != null && audioBytes.length > 0) {
                        break; // 成功，跳出重试循环
                    }
                } catch (Exception e) {
                    if (attempt < maxRetries - 1) {
                        long delay = (long) Math.pow(2, attempt) * 2000; // 2s, 4s
                        log.warn("Higgs TTS 第 {} 次尝试失败: {}，{}ms 后重试", attempt + 1, e.getMessage(), delay);
                        Thread.sleep(delay);
                    } else {
                        throw e;
                    }
                }
            }

            if (audioBytes != null && audioBytes.length > 0) {
                log.info("Higgs TTS 合成成功: voice={}, format={}, size={} bytes", v, fmt, audioBytes.length);
                return audioBytes;
            } else {
                log.error("Higgs TTS 返回空响应");
                return new byte[0];
            }
        } catch (Exception e) {
            log.error("Higgs TTS 合成失败: {}", e.getMessage());
            return new byte[0];
        }
    }

    public byte[] synthesize(String text, String voice, double speed, String responseFormat) {
        return synthesize(text, voice, speed, responseFormat, null, null);
    }

    public byte[] synthesize(String text, String voice, double speed) {
        return synthesize(text, voice, speed, "mp3", null, null);
    }

    /**
     * 获取预设音色列表
     */
    public List<Map<String, String>> getPresetVoices() {
        List<Map<String, String>> voices = new ArrayList<>();
        String[][] presets = {
            {"default", "Default (默认)", "通用默认音色"},
            {"Chloe_Adams", "Chloe Adams", "女-友好清晰"},
            {"Eleanor_Reed", "Eleanor Reed", "女-冷静专业"},
            {"Jake_Rivers", "Jake Rivers", "男-活力激情"},
            {"Marcus_Webb", "Marcus Webb", "男-自信教授"},
            {"Nora_Vance", "Nora Vance", "女-冷静叙事"},
            {"Oliver_Grant", "Oliver Grant", "男-深思熟虑"},
        };
        for (String[] v : presets) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("id", v[0]);
            map.put("name", v[1]);
            map.put("gender", v[2]);
            voices.add(map);
        }
        return voices;
    }
}
