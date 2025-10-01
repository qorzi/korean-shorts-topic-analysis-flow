package com.analysis.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 오디오 처리 작업을 나타내는 태스크 객체
 * 
 * 향후 확장을 위한 오디오 처리 기능:
 * 1. 영상에서 오디오 추출
 * 2. 오디오 지문 생성 (AudioFingerprinting)
 * 3. 음성 인식 및 텍스트 변환
 * 4. 오디오 유사도 분석
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AudioProcessingTask {
    
    /**
     * 영상 ID (데이터베이스 PK)
     */
    private Long videoId;
    
    /**
     * YouTube 영상 ID
     */
    private String youtubeId;
    
    /**
     * 영상 제목 (로깅 및 디버깅용)
     */
    private String title;
    
    /**
     * 영상 URL
     */
    private String videoUrl;
    
    /**
     * 영상 길이 (초)
     */
    private Integer durationSeconds;
    
    /**
     * 작업 생성 시간
     */
    private LocalDateTime createdAt;
    
    /**
     * 재시도 횟수
     */
    private Integer retryCount;
    
    /**
     * 처리할 오디오 작업 유형
     */
    private AudioProcessingType processingType;
    
    /**
     * 최대 재시도 횟수
     */
    private static final int MAX_RETRY_COUNT = 3;
    
    public enum AudioProcessingType {
        EXTRACT_AUDIO,      // 오디오 추출
        GENERATE_FINGERPRINT, // 오디오 지문 생성
        SPEECH_TO_TEXT,     // 음성 텍스트 변환
        SIMILARITY_ANALYSIS  // 유사도 분석
    }
    
    /**
     * 작업이 재시도 가능한지 확인
     */
    public boolean canRetry() {
        return retryCount == null || retryCount < MAX_RETRY_COUNT;
    }
    
    /**
     * 재시도 횟수 증가
     */
    public void incrementRetryCount() {
        this.retryCount = (this.retryCount == null) ? 1 : this.retryCount + 1;
    }
    
    /**
     * 작업이 유효한지 확인
     */
    public boolean isValid() {
        return videoId != null 
            && youtubeId != null 
            && !youtubeId.trim().isEmpty()
            && videoUrl != null 
            && !videoUrl.trim().isEmpty()
            && durationSeconds != null 
            && durationSeconds > 0 
            && durationSeconds <= 120 // 2분 이하
            && processingType != null;
    }
    
    /**
     * 작업 생성 팩토리 메서드
     */
    public static AudioProcessingTask create(Long videoId, String youtubeId, 
                                           String title, String videoUrl, 
                                           Integer durationSeconds,
                                           AudioProcessingType processingType) {
        return AudioProcessingTask.builder()
                .videoId(videoId)
                .youtubeId(youtubeId)
                .title(title)
                .videoUrl(videoUrl)
                .durationSeconds(durationSeconds)
                .processingType(processingType)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .build();
    }
}
