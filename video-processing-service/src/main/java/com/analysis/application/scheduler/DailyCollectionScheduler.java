package com.analysis.application.scheduler;

import com.analysis.application.service.VideoCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 일일 영상 수집 스케줄러
 * 
 * 실행 시간: 매일 자정 (00:00:00)
 * 작업 내용: 전날 업로드된 한국 쇼츠 영상 수집 및 처리 큐 등록
 * 
 * 스케줄링 비활성화: application.yml에서 scheduling.enabled=false로 설정
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class DailyCollectionScheduler {
    
    private final VideoCollectionService videoCollectionService;
    
    /**
     * 매일 자정에 전날 영상 수집 실행
     * Cron 표현식: "초 분 시 일 월 요일"
     * "0 0 0 * * *" = 매일 00:00:00
     */
    @Scheduled(cron = "${scheduling.daily-collection.cron:0 0 0 * * *}")
    public void collectDailyShorts() {
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        
        log.info("=== 일일 쇼츠 수집 스케줄러 시작 ===");
        log.info("수집 대상 날짜: {}", yesterday.toLocalDate());
        
        try {
            // 전날 영상 수집 및 처리 큐 등록
            int collectedCount = videoCollectionService.collectAndQueueVideos(yesterday);
            
            log.info("=== 일일 쇼츠 수집 완료 ===");
            log.info("수집된 영상 수: {}개", collectedCount);
            
        } catch (Exception e) {
            log.error("일일 쇼츠 수집 중 오류 발생", e);
            
            // 에러 알림 또는 재시도 로직을 여기에 추가할 수 있음
            // 예: 슬랙 알림, 이메일 발송, 재시도 큐 등록 등
        }
    }
    
    /**
     * 매 30분마다 처리 실패한 영상 재시도
     * 시스템 안정성을 위한 복구 메커니즘
     */
    @Scheduled(fixedRate = 1800000) // 30분 = 30 * 60 * 1000ms
    public void retryFailedProcessing() {
        log.debug("처리 실패 영상 재시도 검사 시작");
        
        try {
            int retryCount = videoCollectionService.retryFailedVideos();
            
            if (retryCount > 0) {
                log.info("처리 실패 영상 재시도: {}개", retryCount);
            }
            
        } catch (Exception e) {
            log.error("처리 실패 영상 재시도 중 오류 발생", e);
        }
    }
    
    /**
     * 매 시간마다 큐 상태 모니터링
     * 큐가 가득 차거나 처리가 지연되는 경우 로그 출력
     */
    @Scheduled(fixedRate = 3600000) // 1시간 = 60 * 60 * 1000ms
    public void monitorQueueStatus() {
        try {
            videoCollectionService.logQueueStatus();
        } catch (Exception e) {
            log.error("큐 상태 모니터링 중 오류 발생", e);
        }
    }
}
