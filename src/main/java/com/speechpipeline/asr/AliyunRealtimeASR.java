package com.speechpipeline.asr;

import com.alibaba.nls.client.protocol.InputFormatEnum;
import com.alibaba.nls.client.protocol.SampleRateEnum;
import com.alibaba.nls.client.protocol.NlsClient;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriber;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberListener;
import com.alibaba.nls.client.protocol.asr.SpeechTranscriberResponse;
import com.speechpipeline.config.AliConfig;
import com.speechpipeline.config.NlsClientManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 阿里云实时语音识别封装
 * 对应 Python 的 AliyunRealtimeASR
 */
@Slf4j
@Component
public class AliyunRealtimeASR {

    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;
    private final NlsClient nlsClient;
    
    private SpeechTranscriber transcriber;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final List<String> intermediateResults = Collections.synchronizedList(new ArrayList<>());
    private final List<SentenceResult> sentences = Collections.synchronizedList(new ArrayList<>());
    private String fullText = "";
    private String taskId;

    public record SentenceResult(int index, String text, int confidence, 
                                  long beginTime, long endTime, long time) {}

    public AliyunRealtimeASR(NlsClientManager nlsClientManager, AliConfig aliConfig) {
        this.nlsClientManager = nlsClientManager;
        this.aliConfig = aliConfig;
        this.nlsClient = nlsClientManager.getNlsClient();
    }

    /**
     * 启动识别任务
     */
    public boolean start() {
        if (started.compareAndSet(false, true)) {
            try {
                com.alibaba.nls.client.protocol.NlsClient client = nlsClientManager.getNlsClient();
                if (client == null) {
                    log.error("❌ NlsClient 未初始化，无法启动 ASR");
                    started.set(false);
                    return false;
                }
                SpeechTranscriberListener listener = getTranscriberListener();
                transcriber = new SpeechTranscriber(client, listener);
                transcriber.setAppKey(aliConfig.getAppKey());
                transcriber.setFormat(InputFormatEnum.PCM);
                transcriber.setSampleRate(SampleRateEnum.SAMPLE_RATE_16K);
                transcriber.setEnableIntermediateResult(true);
                transcriber.setEnablePunctuation(true);
                transcriber.setEnableITN(true);
                
                // 设置端到端模型（识音石）
                transcriber.addCustomedParam("model", aliConfig.getAsrModel());
                transcriber.addCustomedParam("route_group", "asr-EEND");
                
                transcriber.start();
                log.info("✅ ASR 实时转写启动成功");
                return true;
            } catch (Exception e) {
                log.error("❌ 启动 ASR 失败: {}", e.getMessage());
                started.set(false);
                return false;
            }
        }
        return false;
    }

    /**
     * 发送音频数据
     */
    public void sendAudio(byte[] pcmData) {
        if (transcriber != null && !stopped.get()) {
            try {
                transcriber.send(pcmData);
            } catch (Exception e) {
                log.error("发送音频失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 停止识别
     */
    public void stop() {
        if (transcriber == null || !stopped.compareAndSet(false, true)) {
            return;
        }
        try {
            transcriber.stop();
            log.info("ASR 停止发送音频");
        } catch (Exception e) {
            log.error("停止 ASR 失败: {}", e.getMessage());
        }
    }

    /**
     * 等待识别完成
     */
    public void waitForCompletion(long timeoutMs) {
        if (transcriber == null || !started.get()) {
            // ASR not started, no need to wait
            return;
        }
        try {
            Thread.sleep(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取完整文本
     */
    public String getFullText() {
        return fullText;
    }

    /**
     * 获取所有句子结果
     */
    public List<SentenceResult> getSentences() {
        return new ArrayList<>(sentences);
    }

    /**
     * 获取中间结果列表
     */
    public List<String> getIntermediateResults() {
        return new ArrayList<>(intermediateResults);
    }

    /**
     * 重置状态
     */
    public void reset() {
        synchronized (this) {
            intermediateResults.clear();
            sentences.clear();
            fullText = "";
            taskId = null;
            started.set(false);
            stopped.set(false);
        }
    }

    /**
     * 关闭资源
     */
    public void close() {
        if (transcriber != null) {
            try {
                if (!stopped.get()) {
                    stop();
                }
                transcriber.close();
            } catch (Exception e) {
                log.warn("关闭 ASR 转写器失败: {}", e.getMessage());
            } finally {
                transcriber = null;
            }
        }
        reset();
    }

    /**
     * 创建监听器 — 注意方法名与 SDK 一致
     */
    private SpeechTranscriberListener getTranscriberListener() {
        return new SpeechTranscriberListener() {
            @Override
            public void onTranscriberStart(SpeechTranscriberResponse response) {
                taskId = response.getTaskId();
                log.info("🚀 ASR 识别启动: task_id={}", taskId);
            }

            @Override
            public void onSentenceBegin(SpeechTranscriberResponse response) {
                log.debug("📌 句子开始: index={}", response.getTransSentenceIndex());
            }

            @Override
            public void onSentenceEnd(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                int index = response.getTransSentenceIndex();
                int confidence = response.getConfidence() != null ? response.getConfidence().intValue() : 0;
                long beginTime = response.getSentenceBeginTime();
                long endTime = response.getTransSentenceTime();
                
                if (text != null && !text.isEmpty()) {
                    sentences.add(new SentenceResult(index, text, confidence, beginTime, endTime, 0));
                    fullText = text;
                    log.info("✅ 句子识别完成 [{}]: '{}' (置信度: {})", index, text, confidence);
                }
            }

            @Override
            public void onTranscriptionResultChange(SpeechTranscriberResponse response) {
                String text = response.getTransSentenceText();
                if (text != null && !text.isEmpty()) {
                    synchronized (intermediateResults) {
                        intermediateResults.add(text);
                    }
                    fullText = text;
                    log.debug("📝 中间结果: '{}'", text);
                }
            }

            @Override
            public void onTranscriptionComplete(SpeechTranscriberResponse response) {
                log.info("✅ 识别任务完成: task_id={}", response.getTaskId());
            }

            @Override
            public void onFail(SpeechTranscriberResponse response) {
                log.error("❌ ASR 识别失败: task_id={}, status={}, text={}", 
                    response.getTaskId(), response.getStatus(), response.getStatusText());
            }
        };
    }
}
