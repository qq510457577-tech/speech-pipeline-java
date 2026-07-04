package com.speechpipeline.asr;

import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.commonrequest.CommonRequest;
import com.alibaba.nls.client.protocol.commonrequest.CommonRequestListener;
import com.alibaba.nls.client.protocol.commonrequest.CommonRequestResponse;
import com.speechpipeline.config.AliConfig;
import com.speechpipeline.config.NlsClientManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class AliyunLanguageIdentifier {

    private static final String NAMESPACE = "LanguageIdentification";
    private static final int SAMPLE_RATE = 8000;
    private static final int CHUNK_DURATION_MS = 500;
    private static final int MAX_AUDIO_SECONDS = 60;
    private static final int PCM_BYTES_PER_SECOND = SAMPLE_RATE * 2;

    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;

    public Map<String, Object> identify(byte[] pcm8kMono, String languageType) throws Exception {
        if (pcm8kMono == null || pcm8kMono.length == 0) {
            throw new IllegalArgumentException("音频解码后为空");
        }
        if (pcm8kMono.length > PCM_BYTES_PER_SECOND * MAX_AUDIO_SECONDS) {
            throw new IllegalArgumentException("语种识别音频不能超过 60 秒");
        }

        NlsClient client = nlsClientManager.getNlsClient();
        if (client == null) {
            throw new IllegalStateException("阿里云 NlsClient 未初始化");
        }

        String normalizedType = normalizeLanguageType(languageType);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> latestResult = new AtomicReference<>(new LinkedHashMap<>());
        AtomicReference<Exception> failure = new AtomicReference<>();

        CommonRequestListener listener = new CommonRequestListener() {
            @Override
            public void onStarted(CommonRequestResponse response) {
                log.info("语种识别启动: task_id={}", response.getTaskId());
            }

            @Override
            public void onEvent(CommonRequestResponse response) {
                Map<String, Object> parsed = parseResult(response);
                if (!parsed.isEmpty()) {
                    latestResult.set(parsed);
                    log.info("语种识别结果: task_id={}, language={}, score={}",
                            response.getTaskId(), parsed.get("language"), parsed.get("score"));
                }
            }

            @Override
            public void onStopped(CommonRequestResponse response) {
                finished.countDown();
            }

            @Override
            public void onFailed(CommonRequestResponse response) {
                failure.set(new RuntimeException(response.header + " " + response.payload));
                finished.countDown();
            }
        };

        CommonRequest request = new CommonRequest(client, listener, NAMESPACE);
        request.setAppKey(aliConfig.getAppKey());
        request.addCustomedParam("format", "pcm");
        request.addCustomedParam("sample_rate", SAMPLE_RATE);
        request.addCustomedParam("language_type", normalizedType);

        try {
            request.start();
            int chunkSize = PCM_BYTES_PER_SECOND / 1000 * CHUNK_DURATION_MS;
            for (int i = 0; i < pcm8kMono.length; i += chunkSize) {
                int end = Math.min(i + chunkSize, pcm8kMono.length);
                request.send(Arrays.copyOfRange(pcm8kMono, i, end));
                Thread.sleep(CHUNK_DURATION_MS / 10L);
            }
            request.stop();
            if (!finished.await(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("语种识别等待结果超时");
            }
            if (failure.get() != null) {
                throw failure.get();
            }
        } finally {
            try {
                request.close();
            } catch (Exception e) {
                log.debug("关闭语种识别请求失败: {}", e.getMessage());
            }
        }

        Map<String, Object> result = latestResult.get();
        if (result.isEmpty()) {
            result.put("language", "Empty");
            result.put("language_name", "未识别");
            result.put("score", null);
        }
        result.put("language_type", normalizedType);
        return result;
    }

    private String normalizeLanguageType(String languageType) {
        if (languageType == null || languageType.isBlank()) {
            return "mandenglcant";
        }
        switch (languageType) {
            case "mandengl":
            case "mandcant":
            case "englcant":
            case "mandenglcant":
                return languageType;
            default:
                throw new IllegalArgumentException("language_type 仅支持 mandengl, mandcant, englcant, mandenglcant");
        }
    }

    private Map<String, Object> parseResult(CommonRequestResponse response) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (response == null || response.payload == null || response.payload.isEmpty()) {
            return result;
        }
        Map<String, Object> payload = response.payload;
        Object languageValue = payload.get("language");
        String language = languageValue != null ? String.valueOf(languageValue) : null;
        if (language == null) {
            Object typeValue = payload.get("type");
            language = typeValue != null ? String.valueOf(typeValue) : null;
        }
        result.put("language", language != null ? language : "Empty");
        result.put("language_name", languageName(language));
        result.put("score", payload.get("score"));
        result.put("task_id", response.getTaskId());
        return result;
    }

    private String languageName(String language) {
        if ("mand".equals(language)) {
            return "中文";
        }
        if ("engl".equals(language)) {
            return "英文";
        }
        if ("cant".equals(language)) {
            return "粤语";
        }
        return "未识别";
    }
}
