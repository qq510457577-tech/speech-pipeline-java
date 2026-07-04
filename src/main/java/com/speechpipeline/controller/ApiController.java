package com.speechpipeline.controller;

import com.speechpipeline.asr.AliyunRealtimeASR;
import com.speechpipeline.asr.AsrSessionManager;
import com.speechpipeline.config.AliConfig;
import com.speechpipeline.config.NlsClientManager;
import com.speechpipeline.tts.HiggsTTS;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ASR/TTS REST API 控制器
 */
@Slf4j
@RestController
@RequestMapping({"/api", "/speech/api"})
@RequiredArgsConstructor
public class ApiController {

    private final AsrSessionManager sessionManager;
    private final HiggsTTS higgsTts;
    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;

    private static final Set<String> SUPPORTED_ASR_EXTENSIONS = Set.of(
        ".pcm", ".raw", ".mp3", ".wav", ".m4a", ".aac", ".flac", ".ogg", ".opus"
    );
    private static final int PCM_16K_MONO_BYTES_PER_SECOND = 16_000 * 2;

    private byte[] extractAudioBytes(Object audioValue) {
        if (audioValue == null) {
            return new byte[0];
        }
        if (audioValue instanceof byte[]) {
            return (byte[]) audioValue;
        }
        if (audioValue instanceof String) {
            String audio = ((String) audioValue).trim();
            if (audio.isEmpty()) {
                return new byte[0];
            }
            int comma = audio.indexOf(',');
            String payload = audio.startsWith("data:") && comma >= 0 ? audio.substring(comma + 1) : audio;
            try {
                return Base64.getDecoder().decode(payload);
            } catch (IllegalArgumentException ignored) {
                return audio.getBytes(StandardCharsets.ISO_8859_1);
            }
        }
        if (audioValue instanceof List<?>) {
            List<?> values = (List<?>) audioValue;
            byte[] bytes = new byte[values.size()];
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException("audio 数组必须只包含数字字节");
                }
                bytes[i] = (byte) (((Number) value).intValue() & 0xff);
            }
            return bytes;
        }
        throw new IllegalArgumentException("audio 必须是 base64 字符串或字节数组");
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }

    private byte[] loadAs16kPcm(Path inputPath, String ext) throws IOException, InterruptedException {
        if (".pcm".equals(ext) || ".raw".equals(ext)) {
            return Files.readAllBytes(inputPath);
        }

        Path pcmPath = Files.createTempFile("asr_", ".pcm");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-i", inputPath.toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", pcmPath.toString()
            );
            Process process = pb.redirectErrorStream(true).start();
            ByteArrayOutputStream ffmpegOutput = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                in.transferTo(ffmpegOutput);
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = ffmpegOutput.toString(StandardCharsets.UTF_8);
                throw new IOException("ffmpeg 转码失败: " + output.trim());
            }
            return Files.readAllBytes(pcmPath);
        } finally {
            Files.deleteIfExists(pcmPath);
        }
    }

    private void streamPcmToAsr(AliyunRealtimeASR asr, byte[] audioBytes) throws InterruptedException {
        int chunkSize = 3200; // 100ms of 16kHz 16-bit mono PCM
        for (int i = 0; i < audioBytes.length; i += chunkSize) {
            int end = Math.min(i + chunkSize, audioBytes.length);
            byte[] chunk = Arrays.copyOfRange(audioBytes, i, end);
            asr.sendAudio(chunk);
            Thread.sleep(100);
        }
    }

    // ===== ASR =====

    @PostMapping("/asr/stream")
    public ResponseEntity<Map<String, Object>> createAsrStream() {
        Map<String, Object> result = new HashMap<>();
        try {
            AsrSessionManager.AsrSession session = sessionManager.createSession();
            result.put("status", "ok");
            result.put("session", session.getSessionId());
            result.put("text", "");
            result.put("final", false);
            log.info("创建 ASR 会话: {}", session.getSessionId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            log.error("创建 ASR 会话失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/asr/stream/audio")
    public ResponseEntity<Map<String, Object>> sendAudio(
            @RequestParam String session,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (body == null || !body.containsKey("audio")) {
                result.put("error", "audio is required");
                return ResponseEntity.badRequest().body(result);
            }
            Optional<AsrSessionManager.AsrSession> opt = sessionManager.getSession(session);
            if (opt.isEmpty()) {
                result.put("error", "session not found");
                return ResponseEntity.status(404).body(result);
            }
            byte[] audioBytes = extractAudioBytes(body.get("audio"));
            if (audioBytes.length == 0) {
                result.put("error", "audio is empty");
                return ResponseEntity.badRequest().body(result);
            }
            AsrSessionManager.AsrSession s = opt.get();
            s.getAsr().sendAudio(audioBytes);
            s.touch();
            result.put("status", "ok");
            result.put("bytes", audioBytes.length);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    @PostMapping("/asr/stream/end")
    public ResponseEntity<Map<String, Object>> endSession(@RequestParam String session) {
        Map<String, Object> result = new HashMap<>();
        try {
            Optional<AsrSessionManager.AsrSession> opt = sessionManager.getSession(session);
            if (opt.isEmpty()) {
                result.put("error", "session not found");
                return ResponseEntity.status(404).body(result);
            }
            AsrSessionManager.AsrSession s = opt.get();
            AtomicBoolean completed = new AtomicBoolean(false);
            Thread closeThread = new Thread(() -> {
                try {
                    s.getAsr().stop();
                    s.getAsr().waitForCompletion(5000);
                    result.put("text", s.getAsr().getFullText());
                    result.put("final", true);
                } catch (Exception e) {
                    log.warn("ASR 停止异常: {}", e.getMessage());
                    result.put("text", "");
                    result.put("final", true);
                }
                completed.set(true);
            });
            closeThread.setDaemon(true);
            closeThread.start();
            long start = System.currentTimeMillis();
            while (!completed.get() && (System.currentTimeMillis() - start) < 10000) {
                Thread.sleep(100);
            }
            if (!completed.get()) {
                log.warn("ASR 关闭超时: {}", session);
                result.put("text", "");
                result.put("final", true);
            }
            result.put("session", session);
            s.close();
            sessionManager.removeSession(session);
            log.info("ASR 会话结束: {}, 结果: '{}'", session, result.get("text"));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * 文件上传 ASR 识别
     * POST /api/asr/file
     * Body: multipart/form-data with 'file' part
     */
    @PostMapping("/asr/file")
    public ResponseEntity<Map<String, Object>> recognizeFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "linglong") String model) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            if (file.isEmpty()) {
                result.put("error", "文件为空");
                return ResponseEntity.badRequest().body(result);
            }

            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            String ext = getExtension(originalName);
            if (!SUPPORTED_ASR_EXTENSIONS.contains(ext)) {
                result.put("error", "不支持的音频格式，支持: " + String.join(", ", SUPPORTED_ASR_EXTENSIONS));
                result.put("filename", originalName);
                return ResponseEntity.badRequest().body(result);
            }

            Path inputPath = Files.createTempFile("asr_upload_", ext);
            AsrSessionManager.AsrSession tempSession = null;
            try {
                try (InputStream in = file.getInputStream()) {
                    Files.copy(in, inputPath, StandardCopyOption.REPLACE_EXISTING);
                }
                byte[] audioBytes = loadAs16kPcm(inputPath, ext);
                if (audioBytes.length == 0) {
                    result.put("error", "音频解码后为空");
                    return ResponseEntity.badRequest().body(result);
                }

                tempSession = sessionManager.createSession();
                AliyunRealtimeASR tempAsr = tempSession.getAsr();

                streamPcmToAsr(tempAsr, audioBytes);
                
                // 停止并等待结果
                tempAsr.stop();
                tempAsr.waitForCompletion(5000);
                
                String recognizedText = tempAsr.getFullText();
                result.put("status", "ok");
                result.put("text", recognizedText != null ? recognizedText : "");
                result.put("filename", originalName);
                result.put("filesize", file.getSize());
                result.put("pcm_bytes", audioBytes.length);
                result.put("duration_seconds", Math.round(audioBytes.length * 100.0 / PCM_16K_MONO_BYTES_PER_SECOND) / 100.0);
                result.put("model", model);

            } catch (Exception e) {
                log.error("文件识别失败: {}", e.getMessage());
                result.put("status", "error");
                result.put("text", "");
                result.put("error", "文件识别失败: " + e.getMessage());
            } finally {
                if (tempSession != null) {
                    sessionManager.removeSession(tempSession.getSessionId());
                }
                Files.deleteIfExists(inputPath);
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("error", e.getMessage());
            log.error("文件上传处理失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    // ===== TTS =====

    /**
     * 语音合成 (Higgs Audio v3)
     * POST /api/tts/synthesize
     * Body: {"text": "...", "voice": "default", "response_format": "mp3",
     *        "ref_audio": "...", "ref_text": "..."}
     */
    @PostMapping("/tts/synthesize")
    public ResponseEntity<byte[]> synthesize(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ResponseEntity.badRequest()
                .header("X-TTS-Error", "request_body_required")
                .body("请求体不能为空".getBytes());
        }
        String text = (String) body.get("text");
        String voice = (String) body.getOrDefault("voice", "default");
        String format = (String) body.getOrDefault("response_format", "mp3");
        String refAudio = (String) body.get("ref_audio");
        String refText = (String) body.get("ref_text");
        String refAudioFile = (String) body.get("ref_audio_file");

        if (text == null || text.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                .header("X-TTS-Error", "text_required")
                .body("文本不能为空".getBytes());
        }
        if (text.length() > 5000) {
            return ResponseEntity.badRequest()
                .header("X-TTS-Error", "text_too_long")
                .body(("文本过长（最多5000字符），当前" + text.length() + "字符").getBytes());
        }
        if (!format.matches("(mp3|wav|flac|opus)")) {
            return ResponseEntity.badRequest()
                .header("X-TTS-Error", "unsupported_format")
                .body(("不支持的格式: " + format + "，支持: mp3, wav, flac, opus").getBytes());
        }
        try {
            // Handle ref_audio_file path -> read file content
            if (refAudioFile != null && !refAudioFile.isEmpty()) {
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(refAudioFile);
                    if (java.nio.file.Files.exists(path)) {
                        byte[] fileBytes = java.nio.file.Files.readAllBytes(path);
                        String base64 = java.util.Base64.getEncoder().encodeToString(fileBytes);
                        refAudio = "data:audio/wav;base64," + base64;
                    }
                } catch (Exception e) {
                    log.warn("读取参考音频文件失败: {}", e.getMessage());
                }
            }
            byte[] audio = higgsTts.synthesize(text, voice, 1.0, format, refAudio, refText);
            if (audio.length == 0) {
                return ResponseEntity.internalServerError()
                    .header("X-TTS-Error", "higgs_api_failed")
                    .body("合成失败".getBytes());
            }
            String contentType;
            switch (format.toLowerCase()) {
                case "mp3": contentType = "audio/mpeg"; break;
                case "wav": contentType = "audio/wav"; break;
                case "flac": contentType = "audio/flac"; break;
                case "opus": contentType = "audio/ogg"; break;
                default: contentType = "audio/mpeg";
            }
            return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("X-Voice", voice != null ? voice : "default")
                .header("X-Model", "higgs-audio-v3-tts")
                .header("X-Audio-Size", String.valueOf(audio.length))
                .body(audio);
        } catch (Exception e) {
            log.error("TTS 合成失败: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                .header("X-TTS-Error", "tts_synthesis_failed")
                .body(("合成失败: " + e.getMessage()).getBytes());
        }
    }

    /**
     * 获取预设音色列表
     */
    @GetMapping("/tts/voices")
    public ResponseEntity<Map<String, Object>> getVoices() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("preset_voices", higgsTts.getPresetVoices());
        result.put("supported_formats", Arrays.asList("mp3", "wav", "pcm", "aac", "flac", "opus"));
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有标签参考
     */
    @GetMapping("/tts/tags")
    public ResponseEntity<Map<String, Object>> getTags() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Emotion tags
        List<Map<String, String>> emotions = new ArrayList<>();
        String[][] emotionData = {
            {"happiness", "开心"}, {"sadness", "悲伤"}, {"anger", "愤怒"},
            {"fear", "恐惧"}, {"surprise", "惊讶"}, {"disgust", "厌恶"},
            {"excitement", "兴奋"}, {"calmness", "平静"}, {"anxiety", "焦虑"},
            {"pride", "自豪"}, {"shame", "羞愧"}, {"helplessness", "无助"}
        };
        for (String[] e : emotionData) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tag", "<|emotion:" + e[0] + "|>");
            m.put("name", e[1]);
            m.put("example", "<|emotion:" + e[0] + "|>" + e[0]);
            emotions.add(m);
        }
        result.put("emotions", emotions);

        // Style tags
        List<Map<String, String>> styles = new ArrayList<>();
        String[][] styleData = {
            {"singing", "歌唱"}, {"shouting", "喊叫/投射"}, {"whispering", "耳语"}
        };
        for (String[] s : styleData) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tag", "<|" + s[0] + "|" + s[1] + "|>");
            m.put("name", s[1]);
            styles.add(m);
        }
        result.put("styles", styles);

        // SFX tags
        List<Map<String, String>> sfxs = new ArrayList<>();
        String[][] sfxData = {
            {"cough", "咳嗽"}, {"laughter", "笑声"}, {"crying", "哭泣"},
            {"screaming", "尖叫"}, {"burping", "打嗝"}, {"humming", "哼唱"},
            {"sigh", "叹气"}, {"sniff", "吸鼻子"}, {"sneeze", "打喷嚏"}
        };
        for (String[] s : sfxData) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tag", "<|sfx:" + s[0] + "|>");
            m.put("name", s[1]);
            sfxs.add(m);
        }
        result.put("sfx", sfxs);

        // Prosody tags
        List<Map<String, String>> prosodies = new ArrayList<>();
        String[][] prosodyData = {
            {"speed_very_slow", "~0.65x"}, {"speed_slow", "~0.85x"},
            {"speed_fast", "~1.2x"}, {"speed_very_fast", "~1.4x"},
            {"pitch_low", "~-3 semitones"}, {"pitch_high", "+2.5 semitones"},
            {"pause", "~400-700ms"}, {"long_pause", "~700-1500ms"},
            {"expressive_high", "更富有表现力"}, {"expressive_low", "平淡"}
        };
        for (String[] p : prosodyData) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("tag", "<|prosody:" + p[0] + "|>");
            m.put("effect", p[1]);
            prosodies.add(m);
        }
        result.put("prosody", prosodies);

        return ResponseEntity.ok(result);
    }

    // ===== Health =====

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("sessions", sessionManager.getActiveSessionCount());
        result.put("tts_status", higgsTts.isConfigured() ? "ready" : "unconfigured");
        result.put("tts_engine", "Higgs Audio v3 (Boson AI)");
        result.put("services", Arrays.asList("ASR", "TTS-Higgs"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "speech-pipeline-java");
        result.put("version", "1.0.4");
        result.put("tts_engine", "Higgs Audio v3 (Boson AI)");
        result.put("features", Arrays.asList(
            "asr-stream", "asr-file", "asr-microphone",
            "tts", "tts-voices", "tts-tags", "tts-zero-shot",
            "tts-inline-tags", "websocket"
        ));
        result.put("active_sessions", sessionManager.getActiveSessionCount());
        return ResponseEntity.ok(result);
    }
}
