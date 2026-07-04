package com.speechpipeline.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * ASR 会话记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionRecord {
    private String id;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String startTime;
    
    private String text;
    
    private String voice;
    
    private List<SentenceSegment> segments;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SentenceSegment {
        private int index;
        private String text;
        private int confidence;
        private long beginTime;
        private long endTime;
    }
}
