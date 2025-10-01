package com.analysis.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * YouTube 쇼츠 영상 정보를 저장하는 엔티티
 * 
 * 필터링 조건:
 * - 영상 길이: 2분 이하 (120초)
 * - 지역: 대한민국 (KR)
 * - 화질: 240p (대역폭 최소화)
 */
@Entity
@Table(name = "videos")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Video {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * YouTube 영상 고유 ID
     */
    @Column(unique = true, nullable = false)
    private String youtubeId;
    
    /**
     * 영상 제목
     */
    @Column(length = 500)
    private String title;
    
    /**
     * 영상 설명
     */
    @Column(length = 2000)
    private String description;
    
    /**
     * 채널 ID
     */
    private String channelId;
    
    /**
     * 채널명
     */
    private String channelTitle;
    
    /**
     * 영상 길이 (초 단위)
     */
    private Integer durationSeconds;
    
    /**
     * 조회수
     */
    private Long viewCount;
    
    /**
     * 좋아요 수
     */
    private Long likeCount;
    
    /**
     * 영상 업로드 시간
     */
    private LocalDateTime publishedAt;
    
    /**
     * 영상 URL
     */
    private String videoUrl;
    
    /**
     * 썸네일 URL
     */
    private String thumbnailUrl;
    
    /**
     * 영상 처리 상태
     */
    @Enumerated(EnumType.STRING)
    private ProcessingStatus processingStatus;
    
    /**
     * pHash 지문 값 (64비트 부호 있는 정수)
     */
    private Long phashFingerprint;
    
    /**
     * 데이터 수집 시간
     */
    private LocalDateTime collectedAt;
    
    /**
     * 영상 처리 완료 시간
     */
    private LocalDateTime processedAt;
    
    /**
     * 처리 오류 메시지
     */
    private String errorMessage;
    
    public enum ProcessingStatus {
        PENDING,        // 처리 대기
        IN_PROGRESS,    // 처리 중
        COMPLETED,      // 처리 완료
        FAILED          // 처리 실패
    }
    
    /**
     * 영상이 처리 가능한 조건을 만족하는지 확인
     * - 길이 2분 이하
     * - 유효한 YouTube ID 존재
     */
    public boolean isProcessable() {
        return durationSeconds != null 
            && durationSeconds <= 120 
            && youtubeId != null 
            && !youtubeId.trim().isEmpty();
    }
    
    /**
     * 영상 처리 시작
     */
    public void startProcessing() {
        this.processingStatus = ProcessingStatus.IN_PROGRESS;
    }
    
    /**
     * 영상 처리 완료
     */
    public void completeProcessing(Long phashFingerprint) {
        this.processingStatus = ProcessingStatus.COMPLETED;
        this.phashFingerprint = phashFingerprint;
        this.processedAt = LocalDateTime.now();
        this.errorMessage = null;
    }
    
    /**
     * 영상 처리 실패
     */
    public void failProcessing(String errorMessage) {
        this.processingStatus = ProcessingStatus.FAILED;
        this.errorMessage = errorMessage;
        this.processedAt = LocalDateTime.now();
    }
}
