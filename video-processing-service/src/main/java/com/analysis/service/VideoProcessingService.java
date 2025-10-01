package com.analysis.service;

import com.analysis.entity.Video;
import com.analysis.repository.VideoRepository;
import com.analysis.queue.QueueManager;
import com.analysis.queue.VideoProcessingTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * JavaCV 기반 영상 처리 서비스
 * 
 * 주요 기능:
 * 1. YouTube 영상을 240p 화질로 다운로드
 * 2. 쇼츠 영상 특성에 맞게 초당 10프레임 추출 (기존 6프레임에서 상향)
 * 3. Python 코드와 동일한 pHash 알고리즘 구현
 * 4. 비동기 멀티스레드 처리
 * 
 * pHash 생성 과정:
 * 1. 영상을 30개 세그먼트로 분할
 * 2. 각 세그먼트에서 초당 10프레임 추출
 * 3. 그레이스케일 변환 및 히스토그램 평활화
 * 4. 프레임들의 평균 이미지 생성
 * 5. pHash 알고리즘으로 64비트 지문 생성
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoProcessingService {
    
    private final VideoRepository videoRepository;
    private final QueueManager queueManager;
    private final PHashGenerator pHashGenerator;
    
    @Value("${app.video.phash.segments:30}")
    private int segments;
    
    @Value("${app.video.phash.frames-per-second:10}")
    private int framesPerSecond;
    
    private ExecutorService executorService;
    private final String tempDirectory = System.getProperty("java.io.tmpdir") + "/video-processing/";
    
    @PostConstruct
    public void init() {
        // 멀티스레드 처리를 위한 스레드 풀 초기화
        executorService = Executors.newFixedThreadPool(4);
        
        // 임시 디렉토리 생성
        try {
            Files.createDirectories(Paths.get(tempDirectory));
            log.info("영상 처리 서비스 초기화 완료 - 임시 디렉토리: {}", tempDirectory);
        } catch (IOException e) {
            log.error("임시 디렉토리 생성 실패", e);
        }
        
        // 백그라운드에서 큐 처리 시작
        startQueueProcessing();
    }
    
    /**
     * 큐에서 영상 처리 작업을 지속적으로 처리
     */
    private void startQueueProcessing() {
        executorService.submit(() -> {
            log.info("영상 처리 큐 워커 시작");
            
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 큐에서 작업 가져오기 (블로킹)
                    VideoProcessingTask task = queueManager.takeVideoProcessingTask();
                    
                    // 별도 스레드에서 처리
                    executorService.submit(() -> processVideoTask(task));
                    
                } catch (InterruptedException e) {
                    log.info("영상 처리 큐 워커 중단됨");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("영상 처리 큐 워커 오류", e);
                }
            }
        });
    }
    
    /**
     * 개별 영상 처리 작업 실행
     */
    @Transactional
    public void processVideoTask(VideoProcessingTask task) {
        log.info("영상 처리 시작: {} - {}", task.getYoutubeId(), task.getTitle());
        
        Video video = null;
        Path tempVideoFile = null;
        
        try {
            // 1. 데이터베이스에서 영상 정보 조회
            video = videoRepository.findById(task.getVideoId())
                    .orElseThrow(() -> new IllegalArgumentException("영상을 찾을 수 없습니다: " + task.getVideoId()));
            
            // 2. 영상 처리 시작 상태로 변경
            video.startProcessing();
            videoRepository.save(video);
            
            // 3. 영상 다운로드 (240p)
            tempVideoFile = downloadVideo(task.getVideoUrl(), task.getYoutubeId());
            
            // 4. pHash 지문 생성
            Long phashFingerprint = generatePHashFingerprint(tempVideoFile.toString(), task.getDurationSeconds());
            
            if (phashFingerprint != null) {
                // 5. 처리 완료
                video.completeProcessing(phashFingerprint);
                log.info("영상 처리 완료: {} - pHash: {}", task.getYoutubeId(), phashFingerprint);
            } else {
                // 처리 실패
                video.failProcessing("pHash 생성 실패");
                log.error("영상 처리 실패: {} - pHash 생성 실패", task.getYoutubeId());
            }
            
        } catch (Exception e) {
            log.error("영상 처리 중 오류 발생: {}", task.getYoutubeId(), e);
            
            if (video != null) {
                video.failProcessing("처리 중 오류: " + e.getMessage());
            }
            
            // 재시도 가능한 경우 다시 큐에 등록
            if (task.canRetry()) {
                task.incrementRetryCount();
                queueManager.addVideoProcessingTask(task);
                log.info("영상 처리 재시도 등록: {} ({}회차)", task.getYoutubeId(), task.getRetryCount());
            }
        } finally {
            // 6. 데이터베이스 저장
            if (video != null) {
                videoRepository.save(video);
            }
            
            // 7. 임시 파일 정리
            if (tempVideoFile != null) {
                cleanupTempFile(tempVideoFile);
            }
        }
    }
    
    /**
     * 영상을 240p 화질로 다운로드
     * 실제 구현에서는 youtube-dl이나 yt-dlp를 사용해야 함
     * 여기서는 개념적 구현
     */
    private Path downloadVideo(String videoUrl, String youtubeId) throws IOException {
        String fileName = "video_" + youtubeId + "_" + System.currentTimeMillis() + ".mp4";
        Path videoPath = Paths.get(tempDirectory, fileName);
        
        // 실제 구현에서는 youtube-dl 또는 yt-dlp 프로세스 실행
        // ProcessBuilder를 사용하여 외부 도구 호출
        // youtube-dl -f "best[height<=240]" --output tempDirectory/fileName videoUrl
        
        log.debug("영상 다운로드 시뮬레이션: {} -> {}", youtubeId, videoPath);
        
        // 임시로 빈 파일 생성 (실제로는 다운로드된 파일)
        Files.createFile(videoPath);
        
        return videoPath;
    }
    
    /**
     * Python 코드와 동일한 pHash 지문 생성
     * 
     * @param videoPath 영상 파일 경로
     * @param durationSeconds 영상 길이 (초)
     * @return pHash 지문 (64비트 부호 있는 정수)
     */
    private Long generatePHashFingerprint(String videoPath, Integer durationSeconds) {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath)) {
            grabber.start();
            
            double totalFrames = grabber.getLengthInFrames();
            double fps = grabber.getFrameRate();
            
            if (totalFrames <= 0 || fps <= 0) {
                log.warn("유효하지 않은 영상 정보: frames={}, fps={}", totalFrames, fps);
                return null;
            }
            
            // 1. 필요한 모든 타임스탬프 미리 계산
            List<Double> targetTimestamps = calculateTargetTimestamps(durationSeconds);
            targetTimestamps.sort(Double::compareTo);
            
            // 2. 프레임 축적을 위한 변수
            Mat accumulator = null;
            int frameCount = 0;
            int targetIndex = 0;
            
            Java2DFrameConverter converter = new Java2DFrameConverter();
            
            // 3. 영상을 처음부터 순차적으로 읽기
            while (targetIndex < targetTimestamps.size()) {
                Frame frame = grabber.grabFrame();
                if (frame == null || frame.image == null) {
                    break;
                }
                
                double currentTimeSeconds = grabber.getTimestamp() / 1000000.0; // 마이크로초를 초로 변환
                
                // 4. 목표 타임스탬프 도달 시 프레임 처리
                if (currentTimeSeconds >= targetTimestamps.get(targetIndex)) {
                    BufferedImage bufferedImage = converter.convert(frame);
                    if (bufferedImage != null) {
                        Mat processedFrame = processFrame(bufferedImage);
                        
                        if (accumulator == null) {
                            accumulator = new Mat(processedFrame.size(), processedFrame.type());
                            processedFrame.copyTo(accumulator);
                        } else {
                            // 프레임 누적
                            accumulator = addMatrices(accumulator, processedFrame);
                        }
                        
                        frameCount++;
                        processedFrame.release();
                    }
                    
                    targetIndex++;
                }
            }
            
            grabber.stop();
            
            if (accumulator == null || frameCount == 0) {
                log.warn("처리된 프레임이 없습니다.");
                return null;
            }
            
            // 5. 평균 프레임 계산
            Mat averageFrame = divideMatrix(accumulator, frameCount);
            
            // 6. pHash 생성
            Long phashValue = pHashGenerator.generatePHash(averageFrame);
            
            // 메모리 정리
            accumulator.release();
            averageFrame.release();
            
            return phashValue;
            
        } catch (Exception e) {
            log.error("pHash 생성 중 오류 발생", e);
            return null;
        }
    }
    
    /**
     * Python 코드와 동일한 타임스탬프 계산 로직
     */
    private List<Double> calculateTargetTimestamps(Integer durationSeconds) {
        List<Double> timestamps = new ArrayList<>();
        
        double totalDurationSeconds = durationSeconds.doubleValue();
        double segmentDurationSeconds = totalDurationSeconds / segments;
        
        for (int segmentIndex = 0; segmentIndex < segments; segmentIndex++) {
            for (int i = 0; i < framesPerSecond; i++) {
                double timestampOffset = (double) i / framesPerSecond;
                double targetTimestamp = (segmentIndex + timestampOffset) * segmentDurationSeconds;
                timestamps.add(targetTimestamp);
            }
        }
        
        return timestamps;
    }
    
    /**
     * 프레임을 그레이스케일로 변환하고 히스토그램 평활화 적용
     */
    private Mat processFrame(BufferedImage image) {
        // BufferedImage를 Mat으로 변환하는 로직 필요
        // 실제 구현에서는 JavaCV의 변환 유틸리티 사용
        
        Mat grayFrame = new Mat();
        Mat equalizedFrame = new Mat();
        
        // 그레이스케일 변환 및 히스토그램 평활화는 실제 구현에서 추가
        // cvtColor(originalMat, grayFrame, COLOR_BGR2GRAY);
        // equalizeHist(grayFrame, equalizedFrame);
        
        return equalizedFrame;
    }
    
    /**
     * 두 Mat 행렬을 더하는 유틸리티 메서드
     */
    private Mat addMatrices(Mat mat1, Mat mat2) {
        Mat result = new Mat();
        // 실제 구현에서는 OpenCV의 add 함수 사용
        return result;
    }
    
    /**
     * Mat 행렬을 스칼라로 나누는 유틸리티 메서드
     */
    private Mat divideMatrix(Mat mat, int divisor) {
        Mat result = new Mat();
        // 실제 구현에서는 OpenCV의 divide 함수 사용
        return result;
    }
    
    /**
     * 임시 파일 정리
     */
    private void cleanupTempFile(Path filePath) {
        try {
            Files.deleteIfExists(filePath);
            log.debug("임시 파일 삭제: {}", filePath);
        } catch (IOException e) {
            log.warn("임시 파일 삭제 실패: {}", filePath, e);
        }
    }
    
    /**
     * 서비스 종료 시 리소스 정리
     */
    public void shutdown() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
            log.info("영상 처리 서비스 종료");
        }
    }
}
