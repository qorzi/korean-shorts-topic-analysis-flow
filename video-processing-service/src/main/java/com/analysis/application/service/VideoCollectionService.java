package com.analysis.application.service;

import com.analysis.domain.model.Video;
import com.analysis.infrastructure.persistence.VideoRepository;
import com.analysis.infrastructure.queue.QueueManager;
import com.analysis.infrastructure.queue.VideoProcessingTask;
import com.analysis.infrastructure.youtube.YouTubeApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 영상 수집 및 큐 관리 서비스
 * 
 * 주요 기능:
 * 1. YouTube에서 쇼츠 영상 데이터 수집
 * 2. 수집된 영상을 데이터베이스에 저장
 * 3. 처리 큐에 영상 등록
 * 4. 실패한 영상 재시도 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoCollectionService {
    
    private final YouTubeApiService youTubeApiService;
    private final VideoRepository videoRepository;
    private final QueueManager queueManager;
    
    /**
     * 지정된 날짜의 영상을 수집하고 처리 큐에 등록
     * 
     * @param targetDate 수집 대상 날짜
     * @return 수집된 영상 수
     */
    @Transactional
    public int collectAndQueueVideos(LocalDateTime targetDate) {
        log.info("영상 수집 시작 - 대상 날짜: {}", targetDate.toLocalDate());
        
        try {
            // 1. YouTube에서 영상 수집
            List<Video> videos = youTubeApiService.collectDailyShorts(targetDate);
            
            if (videos.isEmpty()) {
                log.warn("수집된 영상이 없습니다.");
                return 0;
            }
            
            // 2. 중복 제거 및 데이터베이스 저장
            int savedCount = 0;
            int queuedCount = 0;
            
            for (Video video : videos) {
                if (saveVideoIfNotExists(video)) {
                    savedCount++;
                    
                    // 3. 처리 큐에 등록
                    if (queueVideoForProcessing(video)) {
                        queuedCount++;
                    }
                }
            }
            
            log.info("영상 수집 완료 - 저장: {}개, 큐 등록: {}개", savedCount, queuedCount);
            return savedCount;
            
        } catch (Exception e) {
            log.error("영상 수집 중 오류 발생", e);
            throw new RuntimeException("영상 수집 실패", e);
        }
    }
    
    /**
     * 중복되지 않은 영상만 저장
     */
    private boolean saveVideoIfNotExists(Video video) {
        try {
            if (videoRepository.existsByYoutubeId(video.getYoutubeId())) {
                log.debug("이미 존재하는 영상: {}", video.getYoutubeId());
                return false;
            }
            
            Video savedVideo = videoRepository.save(video);
            log.debug("새 영상 저장: {} - {}", savedVideo.getYoutubeId(), savedVideo.getTitle());
            return true;
            
        } catch (Exception e) {
            log.error("영상 저장 실패: {}", video.getYoutubeId(), e);
            return false;
        }
    }
    
    /**
     * 영상을 처리 큐에 등록
     */
    private boolean queueVideoForProcessing(Video video) {
        try {
            if (!video.isProcessable()) {
                log.debug("처리 불가능한 영상: {}", video.getYoutubeId());
                return false;
            }
            
            VideoProcessingTask task = VideoProcessingTask.create(
                    video.getId(),
                    video.getYoutubeId(),
                    video.getTitle(),
                    video.getVideoUrl(),
                    video.getDurationSeconds()
            );
            
            boolean queued = queueManager.addVideoProcessingTask(task);
            
            if (queued) {
                log.debug("영상 처리 큐에 등록: {}", video.getYoutubeId());
            } else {
                log.warn("영상 처리 큐 등록 실패: {}", video.getYoutubeId());
            }
            
            return queued;
            
        } catch (Exception e) {
            log.error("영상 큐 등록 실패: {}", video.getYoutubeId(), e);
            return false;
        }
    }
    
    /**
     * 처리 실패한 영상들을 재시도 큐에 등록
     * 
     * @return 재시도 등록된 영상 수
     */
    @Transactional
    public int retryFailedVideos() {
        // 1시간 전에 실패한 영상들을 재시도 대상으로 선정
        LocalDateTime retryAfter = LocalDateTime.now().minusHours(1);
        
        List<Video> failedVideos = videoRepository.findFailedVideosForRetry(retryAfter);
        
        if (failedVideos.isEmpty()) {
            return 0;
        }
        
        int retryCount = 0;
        
        for (Video video : failedVideos) {
            try {
                // 상태를 다시 PENDING으로 변경
                video.setProcessingStatus(Video.ProcessingStatus.PENDING);
                video.setErrorMessage(null);
                videoRepository.save(video);
                
                // 재시도 큐에 등록
                if (queueVideoForProcessing(video)) {
                    retryCount++;
                    log.debug("처리 실패 영상 재시도 등록: {}", video.getYoutubeId());
                }
                
            } catch (Exception e) {
                log.error("처리 실패 영상 재시도 등록 실패: {}", video.getYoutubeId(), e);
            }
        }
        
        return retryCount;
    }
    
    /**
     * 큐 상태를 로그로 출력
     */
    public void logQueueStatus() {
        var videoQueueStatus = queueManager.getVideoProcessingQueueStatus();
        var audioQueueStatus = queueManager.getAudioProcessingQueueStatus();
        
        log.info("=== 큐 상태 모니터링 ===");
        log.info("영상 처리 큐: {}/{} ({}%)", 
                videoQueueStatus.getCurrentSize(), 
                videoQueueStatus.getCapacity(),
                String.format("%.1f", videoQueueStatus.getUsagePercentage()));
        log.info("오디오 처리 큐: {}/{} ({}%)", 
                audioQueueStatus.getCurrentSize(), 
                audioQueueStatus.getCapacity(),
                String.format("%.1f", audioQueueStatus.getUsagePercentage()));
        
        // 큐가 가득 찬 경우 경고
        if (videoQueueStatus.isFull()) {
            log.warn("⚠️ 영상 처리 큐가 가득 참!");
        }
        if (audioQueueStatus.isFull()) {
            log.warn("⚠️ 오디오 처리 큐가 가득 참!");
        }
    }
    
    /**
     * 특정 영상을 높은 우선순위로 처리 큐에 등록
     * 
     * @param videoId 영상 ID
     * @return 성공 여부
     */
    @Transactional
    public boolean queueVideoWithHighPriority(Long videoId) {
        try {
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new IllegalArgumentException("영상을 찾을 수 없습니다: " + videoId));
            
            if (!video.isProcessable()) {
                log.warn("처리 불가능한 영상: {}", video.getYoutubeId());
                return false;
            }
            
            VideoProcessingTask task = VideoProcessingTask.createHighPriority(
                    video.getId(),
                    video.getYoutubeId(),
                    video.getTitle(),
                    video.getVideoUrl(),
                    video.getDurationSeconds()
            );
            
            boolean queued = queueManager.addVideoProcessingTask(task);
            
            if (queued) {
                video.setProcessingStatus(Video.ProcessingStatus.PENDING);
                videoRepository.save(video);
                log.info("높은 우선순위로 영상 처리 큐에 등록: {}", video.getYoutubeId());
            }
            
            return queued;
            
        } catch (Exception e) {
            log.error("높은 우선순위 영상 큐 등록 실패: {}", videoId, e);
            return false;
        }
    }
}
