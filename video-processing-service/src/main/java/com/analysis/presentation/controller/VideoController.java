package com.analysis.presentation.controller;

import com.analysis.application.service.VideoCollectionService;
import com.analysis.domain.model.Video;
import com.analysis.infrastructure.persistence.VideoRepository;
import com.analysis.infrastructure.queue.QueueManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 영상 관리 REST API 컨트롤러
 * 
 * 제공 기능:
 * 1. 영상 목록 조회
 * 2. 영상 상세 정보 조회
 * 3. 수동 영상 수집 트리거
 * 4. 큐 상태 모니터링
 * 5. 영상 처리 상태 관리
 */
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
@Slf4j
public class VideoController {
    
    private final VideoRepository videoRepository;
    private final VideoCollectionService videoCollectionService;
    private final QueueManager queueManager;
    
    /**
     * 영상 목록 조회 (페이징)
     * 
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @param status 처리 상태 필터
     * @return 영상 목록
     */
    @GetMapping
    public ResponseEntity<Page<Video>> getVideos(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        
        Pageable pageable = PageRequest.of(page, size, Sort.by("collectedAt").descending());
        
        Page<Video> videos;
        if (status != null) {
            Video.ProcessingStatus processingStatus = Video.ProcessingStatus.valueOf(status.toUpperCase());
            videos = videoRepository.findAll(pageable); // 실제로는 status 필터링 필요
        } else {
            videos = videoRepository.findAll(pageable);
        }
        
        return ResponseEntity.ok(videos);
    }
    
    /**
     * 영상 상세 정보 조회
     * 
     * @param id 영상 ID
     * @return 영상 상세 정보
     */
    @GetMapping("/{id}")
    public ResponseEntity<Video> getVideo(@PathVariable Long id) {
        return videoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 수동 영상 수집 실행
     * 
     * @param targetDate 수집 대상 날짜 (YYYY-MM-DD, 기본값: 어제)
     * @return 수집 결과
     */
    @PostMapping("/collect")
    public ResponseEntity<Map<String, Object>> collectVideos(
            @RequestParam(required = false) String targetDate) {
        
        try {
            LocalDateTime collectDate = targetDate != null 
                    ? LocalDateTime.parse(targetDate + "T00:00:00")
                    : LocalDateTime.now().minusDays(1);
            
            log.info("수동 영상 수집 요청 - 대상 날짜: {}", collectDate.toLocalDate());
            
            int collectedCount = videoCollectionService.collectAndQueueVideos(collectDate);
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "영상 수집 완료",
                    "targetDate", collectDate.toLocalDate().toString(),
                    "collectedCount", collectedCount
            ));
            
        } catch (Exception e) {
            log.error("수동 영상 수집 실패", e);
            
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "영상 수집 실패: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 특정 영상을 높은 우선순위로 처리 큐에 등록
     * 
     * @param id 영상 ID
     * @return 처리 결과
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<Map<String, Object>> processVideo(@PathVariable Long id) {
        try {
            boolean success = videoCollectionService.queueVideoWithHighPriority(id);
            
            if (success) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "영상이 처리 큐에 등록되었습니다.",
                        "videoId", id
                ));
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "영상 처리 큐 등록 실패"
                ));
            }
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "오류: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 큐 상태 조회
     * 
     * @return 큐 상태 정보
     */
    @GetMapping("/queue/status")
    public ResponseEntity<Map<String, Object>> getQueueStatus() {
        var videoQueueStatus = queueManager.getVideoProcessingQueueStatus();
        var audioQueueStatus = queueManager.getAudioProcessingQueueStatus();
        
        return ResponseEntity.ok(Map.of(
                "videoProcessingQueue", Map.of(
                        "currentSize", videoQueueStatus.getCurrentSize(),
                        "capacity", videoQueueStatus.getCapacity(),
                        "usagePercentage", videoQueueStatus.getUsagePercentage(),
                        "isFull", videoQueueStatus.isFull(),
                        "isEmpty", videoQueueStatus.isEmpty()
                ),
                "audioProcessingQueue", Map.of(
                        "currentSize", audioQueueStatus.getCurrentSize(),
                        "capacity", audioQueueStatus.getCapacity(),
                        "usagePercentage", audioQueueStatus.getUsagePercentage(),
                        "isFull", audioQueueStatus.isFull(),
                        "isEmpty", audioQueueStatus.isEmpty()
                )
        ));
    }
    
    /**
     * 처리 실패한 영상 재시도
     * 
     * @return 재시도 결과
     */
    @PostMapping("/retry-failed")
    public ResponseEntity<Map<String, Object>> retryFailedVideos() {
        try {
            int retryCount = videoCollectionService.retryFailedVideos();
            
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "처리 실패 영상 재시도 완료",
                    "retryCount", retryCount
            ));
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "재시도 실패: " + e.getMessage()
            ));
        }
    }
    
    /**
     * 영상 처리 통계 조회
     * 
     * @return 처리 통계
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getProcessingStats() {
        long totalVideos = videoRepository.count();
        long pendingVideos = videoRepository.findByProcessingStatus(Video.ProcessingStatus.PENDING).size();
        long processingVideos = videoRepository.findByProcessingStatus(Video.ProcessingStatus.IN_PROGRESS).size();
        long completedVideos = videoRepository.findByProcessingStatus(Video.ProcessingStatus.COMPLETED).size();
        long failedVideos = videoRepository.findByProcessingStatus(Video.ProcessingStatus.FAILED).size();
        
        return ResponseEntity.ok(Map.of(
                "total", totalVideos,
                "pending", pendingVideos,
                "processing", processingVideos,
                "completed", completedVideos,
                "failed", failedVideos,
                "completionRate", totalVideos > 0 ? (double) completedVideos / totalVideos * 100 : 0.0
        ));
    }
    
    /**
     * 유사한 영상 검색 (pHash 기반)
     * 
     * @param id 기준 영상 ID
     * @param threshold 해밍 거리 임계값 (기본값: 8)
     * @return 유사한 영상 목록
     */
    @GetMapping("/{id}/similar")
    public ResponseEntity<Object> getSimilarVideos(
            @PathVariable Long id,
            @RequestParam(defaultValue = "8") Integer threshold) {
        
        try {
            Video targetVideo = videoRepository.findById(id)
                    .orElse(null);
            
            if (targetVideo == null) {
                return ResponseEntity.notFound().build();
            }
            
            if (targetVideo.getPhashFingerprint() == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "해당 영상의 pHash가 생성되지 않았습니다."
                ));
            }
            
            var similarVideos = videoRepository.findSimilarVideosByPhash(
                    targetVideo.getPhashFingerprint(), threshold, id);
            
            return ResponseEntity.ok(Map.of(
                    "targetVideo", targetVideo,
                    "similarVideos", similarVideos,
                    "threshold", threshold,
                    "count", similarVideos.size()
            ));
            
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "유사 영상 검색 실패: " + e.getMessage()
            ));
        }
    }
}
