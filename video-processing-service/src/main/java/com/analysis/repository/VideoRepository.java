package com.analysis.repository;

import com.analysis.entity.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 영상 데이터 저장소
 */
@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {
    
    /**
     * YouTube ID로 영상 조회
     */
    Optional<Video> findByYoutubeId(String youtubeId);
    
    /**
     * 처리 상태별 영상 조회
     */
    List<Video> findByProcessingStatus(Video.ProcessingStatus status);

    /**
     * 처리 상태별 영상 조회 (페이징)
     */
    Page<Video> findByProcessingStatus(Video.ProcessingStatus status, Pageable pageable);
    
    /**
     * 처리 대기 중인 영상 조회 (생성 시간 순)
     */
    @Query("SELECT v FROM Video v WHERE v.processingStatus = 'PENDING' ORDER BY v.collectedAt ASC")
    List<Video> findPendingVideosOrderByCollectedAt();
    
    /**
     * 특정 기간에 수집된 영상 조회
     */
    @Query("SELECT v FROM Video v WHERE v.collectedAt BETWEEN :startDate AND :endDate")
    List<Video> findByCollectedAtBetween(@Param("startDate") LocalDateTime startDate, 
                                       @Param("endDate") LocalDateTime endDate);
    
    /**
     * 특정 날짜에 업로드된 영상 조회
     */
    @Query("SELECT v FROM Video v WHERE DATE(v.publishedAt) = DATE(:publishDate)")
    List<Video> findByPublishedDate(@Param("publishDate") LocalDateTime publishDate);
    
    /**
     * pHash 지문이 있는 영상 조회
     */
    @Query("SELECT v FROM Video v WHERE v.phashFingerprint IS NOT NULL")
    List<Video> findVideosWithPhash();
    
    /**
     * 유사한 pHash를 가진 영상 조회 (해밍 거리 기반)
     * 주의: 이 쿼리는 성능상 문제가 있을 수 있으므로 인덱스 최적화 필요
     */
    @Query(value = "SELECT * FROM videos v WHERE v.phash_fingerprint IS NOT NULL " +
                   "AND BIT_COUNT(v.phash_fingerprint ^ :targetPhash) <= :maxHammingDistance " +
                   "AND v.id != :excludeVideoId",
           nativeQuery = true)
    List<Video> findSimilarVideosByPhash(@Param("targetPhash") Long targetPhash,
                                       @Param("maxHammingDistance") Integer maxHammingDistance,
                                       @Param("excludeVideoId") Long excludeVideoId);
    
    /**
     * 처리 실패한 영상 중 재시도 가능한 영상 조회
     */
    @Query("SELECT v FROM Video v WHERE v.processingStatus = 'FAILED' " +
           "AND v.processedAt < :retryAfter")
    List<Video> findFailedVideosForRetry(@Param("retryAfter") LocalDateTime retryAfter);
    
    /**
     * 채널별 영상 수 조회
     */
    @Query("SELECT v.channelId, v.channelTitle, COUNT(v) as videoCount " +
           "FROM Video v GROUP BY v.channelId, v.channelTitle " +
           "ORDER BY videoCount DESC")
    List<Object[]> countVideosByChannel();
    
    /**
     * 일별 수집 통계
     */
    @Query("SELECT DATE(v.collectedAt) as collectDate, COUNT(v) as videoCount " +
           "FROM Video v GROUP BY DATE(v.collectedAt) " +
           "ORDER BY collectDate DESC")
    List<Object[]> getDailyCollectionStats();
    
    /**
     * YouTube ID가 이미 존재하는지 확인
     */
    boolean existsByYoutubeId(String youtubeId);
}
