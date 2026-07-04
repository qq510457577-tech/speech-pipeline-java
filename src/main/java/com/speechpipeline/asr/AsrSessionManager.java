package com.speechpipeline.asr;

import com.speechpipeline.config.AliConfig;
import com.speechpipeline.config.NlsClientManager;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程安全的 ASR 会话管理器
 * 管理实时转写会话的创建、销毁和清理
 */
@Component
@RequiredArgsConstructor
public class AsrSessionManager {
    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;

    private final Map<String, AsrSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger cleanupTaskCounter = new AtomicInteger(0);
    
    /** 超时会话清理定时器 */
    private java.util.Timer cleanupTimer;

    {
        // 每 60 秒清理一次超时会话
        cleanupTimer = new java.util.Timer("asr-session-cleanup", true);
        cleanupTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                cleanup();
            }
        }, 60_000, 60_000);
    }

    /**
     * 创建新的 ASR 会话
     */
    public AsrSession createSession() {
        String sessionId = UUID.randomUUID().toString().substring(0, 12);
        // 创建新的 ASR 实例
        AliyunRealtimeASR asr = new AliyunRealtimeASR(nlsClientManager, aliConfig);
        asr.start();
        
        AsrSession session = new AsrSession(sessionId, asr);
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * 创建新的 ASR 实例（供 WebSocket 使用）
     */
    public AliyunRealtimeASR createNewASR() {
        return new AliyunRealtimeASR(nlsClientManager, aliConfig);
    }

    /**
     * 获取会话
     */
    public Optional<AsrSession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /**
     * 移除会话
     */
    public AsrSession removeSession(String sessionId) {
        AsrSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
        return session;
    }

    /**
     * 清理超时会话
     */
    public int cleanup() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        for (Map.Entry<String, AsrSession> entry : sessions.entrySet()) {
            AsrSession session = entry.getValue();
            if (now - session.getLastActiveTime() > 120_000) { // 2 分钟超时
                session.close();
                sessions.remove(entry.getKey());
                cleaned++;
            }
        }
        if (cleaned > 0) {
            System.out.println("🗑️ 清理 " + cleaned + " 个超时会话");
        }
        return cleaned;
    }

    /**
     * 获取活跃会话数
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    @PreDestroy
    public void shutdown() {
        if (cleanupTimer != null) {
            cleanupTimer.cancel();
        }
        // 关闭所有活跃会话
        for (AsrSession session : sessions.values()) {
            session.close();
        }
        sessions.clear();
    }

    /**
     * ASR 会话
     */
    public static class AsrSession {
        private final String sessionId;
        private final AliyunRealtimeASR asr;
        private volatile long lastActiveTime;

        public AsrSession(String sessionId, AliyunRealtimeASR asr) {
            this.sessionId = sessionId;
            this.asr = asr;
            this.lastActiveTime = System.currentTimeMillis();
        }

        public String getSessionId() {
            return sessionId;
        }

        public AliyunRealtimeASR getAsr() {
            return asr;
        }

        public long getLastActiveTime() {
            return lastActiveTime;
        }

        public void touch() {
            this.lastActiveTime = System.currentTimeMillis();
        }

        public void close() {
            asr.close();
        }
    }
}
