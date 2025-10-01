package com.analysis.queue;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 인메모리 큐 관리자
 * 
 * 현재는 인메모리 방식으로 구현되어 있으며, 
 * 향후 Redis나 RabbitMQ 등의 외부 메시지 큐로 확장 가능하도록 설계
 * 
 * 두 가지 큐를 관리:
 * 1. 영상 처리 큐 - 영상 다운로드 및 pHash 생성 작업
 * 2. 오디오 처리 큐 - 오디오 추출 및 문맥 분석 작업 (향후 확장)
 */
@Component
@Slf4j
public class QueueManager {
    
    private final BlockingQueue<VideoProcessingTask> videoProcessingQueue;
    private final BlockingQueue<AudioProcessingTask> audioProcessingQueue;
    
    private final AtomicInteger videoQueueSize = new AtomicInteger(0);
    private final AtomicInteger audioQueueSize = new AtomicInteger(0);
    
    public QueueManager(
            @Value("${app.queue.video-processing.capacity:5000}") int videoQueueCapacity,
            @Value("${app.queue.audio-processing.capacity:5000}") int audioQueueCapacity) {
        
        this.videoProcessingQueue = new LinkedBlockingQueue<>(videoQueueCapacity);
        this.audioProcessingQueue = new LinkedBlockingQueue<>(audioQueueCapacity);
        
        log.info("큐 매니저 초기화 완료 - 영상 처리 큐 용량: {}, 오디오 처리 큐 용량: {}", 
                videoQueueCapacity, audioQueueCapacity);
    }
    
    /**
     * 영상 처리 작업을 큐에 추가
     * 
     * @param task 영상 처리 작업
     * @return 성공 여부
     */
    public boolean addVideoProcessingTask(VideoProcessingTask task) {
        try {
            boolean added = videoProcessingQueue.offer(task);
            if (added) {
                videoQueueSize.incrementAndGet();
                log.debug("영상 처리 작업 큐에 추가됨: {}, 현재 큐 크기: {}", 
                        task.getVideoId(), videoQueueSize.get());
            } else {
                log.warn("영상 처리 큐가 가득참. 작업 추가 실패: {}", task.getVideoId());
            }
            return added;
        } catch (Exception e) {
            log.error("영상 처리 작업 추가 중 오류 발생: {}", task.getVideoId(), e);
            return false;
        }
    }
    
    /**
     * 영상 처리 작업을 큐에서 가져옴 (블로킹)
     * 
     * @return 영상 처리 작업 (큐가 비어있으면 대기)
     * @throws InterruptedException 대기 중 인터럽트 발생
     */
    public VideoProcessingTask takeVideoProcessingTask() throws InterruptedException {
        VideoProcessingTask task = videoProcessingQueue.take();
        videoQueueSize.decrementAndGet();
        log.debug("영상 처리 작업 큐에서 가져옴: {}, 남은 큐 크기: {}", 
                task.getVideoId(), videoQueueSize.get());
        return task;
    }
    
    /**
     * 영상 처리 작업을 큐에서 가져옴 (논블로킹)
     * 
     * @return 영상 처리 작업 (큐가 비어있으면 null)
     */
    public VideoProcessingTask pollVideoProcessingTask() {
        VideoProcessingTask task = videoProcessingQueue.poll();
        if (task != null) {
            videoQueueSize.decrementAndGet();
            log.debug("영상 처리 작업 큐에서 가져옴: {}, 남은 큐 크기: {}", 
                    task.getVideoId(), videoQueueSize.get());
        }
        return task;
    }
    
    /**
     * 오디오 처리 작업을 큐에 추가
     * 
     * @param task 오디오 처리 작업
     * @return 성공 여부
     */
    public boolean addAudioProcessingTask(AudioProcessingTask task) {
        try {
            boolean added = audioProcessingQueue.offer(task);
            if (added) {
                audioQueueSize.incrementAndGet();
                log.debug("오디오 처리 작업 큐에 추가됨: {}, 현재 큐 크기: {}", 
                        task.getVideoId(), audioQueueSize.get());
            } else {
                log.warn("오디오 처리 큐가 가득참. 작업 추가 실패: {}", task.getVideoId());
            }
            return added;
        } catch (Exception e) {
            log.error("오디오 처리 작업 추가 중 오류 발생: {}", task.getVideoId(), e);
            return false;
        }
    }
    
    /**
     * 오디오 처리 작업을 큐에서 가져옴 (블로킹)
     * 
     * @return 오디오 처리 작업 (큐가 비어있으면 대기)
     * @throws InterruptedException 대기 중 인터럽트 발생
     */
    public AudioProcessingTask takeAudioProcessingTask() throws InterruptedException {
        AudioProcessingTask task = audioProcessingQueue.take();
        audioQueueSize.decrementAndGet();
        log.debug("오디오 처리 작업 큐에서 가져옴: {}, 남은 큐 크기: {}", 
                task.getVideoId(), audioQueueSize.get());
        return task;
    }
    
    /**
     * 오디오 처리 작업을 큐에서 가져옴 (논블로킹)
     * 
     * @return 오디오 처리 작업 (큐가 비어있으면 null)
     */
    public AudioProcessingTask pollAudioProcessingTask() {
        AudioProcessingTask task = audioProcessingQueue.poll();
        if (task != null) {
            audioQueueSize.decrementAndGet();
            log.debug("오디오 처리 작업 큐에서 가져옴: {}, 남은 큐 크기: {}", 
                    task.getVideoId(), audioQueueSize.get());
        }
        return task;
    }
    
    /**
     * 영상 처리 큐 상태 조회
     */
    public QueueStatus getVideoProcessingQueueStatus() {
        return QueueStatus.builder()
                .queueType("VIDEO_PROCESSING")
                .currentSize(videoQueueSize.get())
                .capacity(videoProcessingQueue.remainingCapacity() + videoQueueSize.get())
                .build();
    }
    
    /**
     * 오디오 처리 큐 상태 조회
     */
    public QueueStatus getAudioProcessingQueueStatus() {
        return QueueStatus.builder()
                .queueType("AUDIO_PROCESSING")
                .currentSize(audioQueueSize.get())
                .capacity(audioProcessingQueue.remainingCapacity() + audioQueueSize.get())
                .build();
    }
    
    /**
     * 모든 큐 초기화 (개발/테스트 용도)
     */
    public void clearAllQueues() {
        videoProcessingQueue.clear();
        audioProcessingQueue.clear();
        videoQueueSize.set(0);
        audioQueueSize.set(0);
        log.info("모든 큐가 초기화되었습니다.");
    }
}
