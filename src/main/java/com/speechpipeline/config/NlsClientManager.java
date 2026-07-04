package com.speechpipeline.config;

import com.alibaba.nls.client.AccessToken;
import com.alibaba.nls.client.protocol.NlsClient;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 全局 NlsClient 单例 — 基于 Netty，创建消耗资源，推荐全局复用
 * 支持 Token 自动刷新（每 20 小时）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NlsClientManager {

    private final AliConfig aliConfig;
    private volatile NlsClient nlsClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nls-token-refresh");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        try {
            refreshTokenAndReconnect();
            log.info("NlsClient 初始化成功");
            // 启动 Token 定时刷新：每 20 小时刷新一次（Token 有效期 24 小时）
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    refreshTokenAndReconnect();
                } catch (Exception e) {
                    log.error("Token 刷新失败: {}", e.getMessage(), e);
                }
            }, 20, 20, TimeUnit.HOURS);
        } catch (Exception e) {
            log.error("NlsClient 初始化异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 获取新 Token 并重建 NlsClient
     * 旧的转写器不受影响，会继续存活直到被关闭
     */
    private synchronized void refreshTokenAndReconnect() {
        String token = getToken();
        if (token == null || token.isEmpty()) {
            log.error("获取 Token 失败，跳过 NlsClient 重建");
            return;
        }
        
        // 关闭旧 client
        if (nlsClient != null) {
            try {
                nlsClient.shutdown();
                log.info("NlsClient 旧连接已关闭");
            } catch (Exception e) {
                log.warn("关闭旧 NlsClient 失败: {}", e.getMessage());
            }
        }
        
        // 创建新 client
        nlsClient = new NlsClient(token);
        log.info("NlsClient 重建成功");
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (nlsClient != null) {
            nlsClient.shutdown();
            log.info("NlsClient 已关闭");
        }
    }

    public NlsClient getNlsClient() {
        return nlsClient;
    }

    private String getToken() {
        try {
            AccessToken accessToken = new AccessToken(
                aliConfig.getAccessKeyId(),
                aliConfig.getAccessKeySecret()
            );
            accessToken.apply();
            String token = accessToken.getToken();
            log.info("Token 获取成功，过期时间: {}", accessToken.getExpireTime());
            return token;
        } catch (Exception e) {
            log.error("获取 Token 失败: {}", e.getMessage());
            return null;
        }
    }
}
