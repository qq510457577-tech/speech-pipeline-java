package com.speechpipeline.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ali")
public class AliConfig {
    private String accessKeyId;
    private String accessKeySecret;
    private String appKey;
    private String asrModel = "linglong";
    private String gatewayUrl = "wss://nls-gateway-cn-shanghai.aliyuncs.com/ws/v1";
    private String ossBucket;
    private String ossRegion;
}
