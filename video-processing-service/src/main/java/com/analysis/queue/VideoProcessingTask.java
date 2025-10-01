package com.analysis.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 영상 처리 작업을 나타내는 태스크 객체
 * 
 * 영상 처리 과정:
 * 1. YouTube에서 240p 화질로 영상 다운로드
 * 2. JavaCV를 사용하여 프레임 추출 (초당 10프레임)
 * 3. pHash 알고리즘으로 영상 지문 생성
 * 4. 데이터베이스에 결과 저장
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoProcessingTask {
    
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
     * 최대 재시도 횟수
     */
    private static final int MAX_RETRY_COUNT = 3;
    
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
            && durationSeconds <= 120; // 2분 이하
    }
    
    /**
     * 작업 생성 팩토리 메서드
     */
    public static VideoProcessingTask create(Long videoId, String youtubeId, 
                                           String title, String videoUrl, 
                                           Integer durationSeconds) {
        return VideoProcessingTask.builder()
                .videoId(videoId)
                .youtubeId(youtubeId)
                .title(title)
                .videoUrl(videoUrl)
                .durationSeconds(durationSeconds)
                .createdAt(LocalDateTime.now())
                .retryCount(0)
                .build();
    }
}
