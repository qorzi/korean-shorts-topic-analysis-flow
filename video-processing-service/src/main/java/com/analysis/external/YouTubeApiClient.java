package com.analysis.external;

import com.analysis.entity.Video;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.SearchListResponse;
import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.VideoListResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * YouTube Data API를 사용하여 한국 쇼츠 영상 데이터를 수집하는 클라이언트
 * 
 * 수집 조건:
 * - 지역: 대한민국 (regionCode: KR)
 * - 영상 길이: 2분 이하 (duration: short)
 * - 영상 타입: 쇼츠 (shorts)
 * - 일일 목표: 1000개 영상 (설정 가능)
 */
@Service
@Slf4j
public class YouTubeApiClient {
    
    private final YouTube youTube;
    private final String apiKey;
    private final String regionCode;
    private final Long maxResults;
    private final Integer targetDailyCount;
    private final Random random = new Random();
    
    public YouTubeApiClient(
            @Value("${app.youtube.api-key}") String apiKey,
            @Value("${app.youtube.region-code:KR}") String regionCode,
            @Value("${app.youtube.max-results:50}") Long maxResults,
            @Value("${app.video.target-daily-count:1000}") Integer targetDailyCount) throws Exception {
        
        this.apiKey = apiKey;
        this.regionCode = regionCode;
        this.maxResults = maxResults;
        this.targetDailyCount = targetDailyCount;
        
        this.youTube = new YouTube.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                null)
                .setApplicationName("korean-shorts-analysis")
                .build();
        
        log.info("YouTube API 클라이언트 초기화 완료 - 지역: {}, 일일 목표: {}개", regionCode, targetDailyCount);
    }
    
    /**
     * 전날 업로드된 한국 쇼츠 영상 목록을 수집
     * 
     * @param targetDate 수집 대상 날짜
     * @return 수집된 영상 목록
     */
    public List<Video> collectDailyShorts(LocalDateTime targetDate) {
        try {
            log.info("일일 쇼츠 수집 시작 - 대상 날짜: {}", targetDate.toLocalDate());
            
            // 1. 검색 조건 설정
            String publishedAfter = targetDate.toLocalDate().atStartOfDay()
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
            String publishedBefore = targetDate.toLocalDate().atStartOfDay().plusDays(1)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
            
            List<Video> allVideos = new ArrayList<>();
            String nextPageToken = null;
            int apiCallCount = 0;
            final int maxApiCalls = 100; // API 할당량 보호
            
            do {
                // 2. YouTube Search API 호출
                SearchListResponse searchResponse = searchShorts(
                        publishedAfter, publishedBefore, nextPageToken);
                
                if (searchResponse.getItems() == null || searchResponse.getItems().isEmpty()) {
                    log.info("더 이상 검색 결과가 없습니다.");
                    break;
                }
                
                // 3. 검색 결과에서 비디오 ID 추출
                List<String> videoIds = searchResponse.getItems().stream()
                        .map(SearchResult::getId)
                        .map(id -> id.getVideoId())
                        .collect(Collectors.toList());
                
                // 4. 비디오 상세 정보 조회
                List<Video> videos = getVideoDetails(videoIds);
                
                // 5. 필터링: 2분 이하 쇼츠만 선택
                List<Video> filteredVideos = videos.stream()
                        .filter(this::isValidShort)
                        .collect(Collectors.toList());
                
                allVideos.addAll(filteredVideos);
                
                nextPageToken = searchResponse.getNextPageToken();
                apiCallCount++;
                
                log.debug("API 호출 #{}: {}개 영상 발견, {}개 필터링 통과", 
                        apiCallCount, videos.size(), filteredVideos.size());
                
                // API 할당량 보호
                if (apiCallCount >= maxApiCalls) {
                    log.warn("최대 API 호출 횟수에 도달했습니다. 수집을 중단합니다.");
                    break;
                }
                
                // 목표 수량 달성 시 중단
                if (allVideos.size() >= targetDailyCount * 2) { // 샘플링을 위해 여유분 수집
                    log.info("충분한 영상을 수집했습니다. 수집을 중단합니다.");
                    break;
                }
                
            } while (nextPageToken != null);
            
            // 6. 시간별 랜덤 샘플링
            List<Video> sampledVideos = sampleVideosByHour(allVideos, targetDailyCount);
            
            log.info("일일 쇼츠 수집 완료 - 전체: {}개, 샘플링: {}개", 
                    allVideos.size(), sampledVideos.size());
            
            return sampledVideos;
            
        } catch (Exception e) {
            log.error("일일 쇼츠 수집 중 오류 발생", e);
            return Collections.emptyList();
        }
    }
    
    /**
     * YouTube 쇼츠 검색
     */
    private SearchListResponse searchShorts(String publishedAfter, String publishedBefore, 
                                          String pageToken) throws Exception {
        return youTube.search()
                .list(List.of("id", "snippet"))
                .setKey(apiKey)
                .setRegionCode(regionCode)
                .setType("video")
                .setVideoDuration("short") // 4분 이하 영상
                .setPublishedAfter(publishedAfter)
                .setPublishedBefore(publishedBefore)
                .setMaxResults(maxResults)
                .setPageToken(pageToken)
                .setOrder("date") // 최신순 정렬
                .execute();
    }
    
    /**
     * 비디오 상세 정보 조회
     */
    private List<Video> getVideoDetails(List<String> videoIds) throws Exception {
        if (videoIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        String videoIdString = String.join(",", videoIds);
        
        VideoListResponse videoResponse = youTube.videos()
                .list(List.of("id", "snippet", "contentDetails", "statistics"))
                .setKey(apiKey)
                .setId(videoIdString)
                .execute();
        
        return videoResponse.getItems().stream()
                .map(this::convertToVideo)
                .collect(Collectors.toList());
    }
    
    /**
     * YouTube Video 객체를 도메인 Video 객체로 변환
     */
    private Video convertToVideo(com.google.api.services.youtube.model.Video youtubeVideo) {
        try {
            // Duration 파싱 (ISO 8601 형식: PT1M30S -> 90초)
            String duration = youtubeVideo.getContentDetails().getDuration();
            Integer durationSeconds = parseDuration(duration);
            
            return Video.builder()
                    .youtubeId(youtubeVideo.getId())
                    .title(youtubeVideo.getSnippet().getTitle())
                    .description(youtubeVideo.getSnippet().getDescription())
                    .channelId(youtubeVideo.getSnippet().getChannelId())
                    .channelTitle(youtubeVideo.getSnippet().getChannelTitle())
                    .durationSeconds(durationSeconds)
                    .viewCount(youtubeVideo.getStatistics() != null ? 
                            youtubeVideo.getStatistics().getViewCount().longValue() : 0L)
                    .likeCount(youtubeVideo.getStatistics() != null && 
                            youtubeVideo.getStatistics().getLikeCount() != null ? 
                            youtubeVideo.getStatistics().getLikeCount().longValue() : 0L)
                    .publishedAt(LocalDateTime.parse(
                            youtubeVideo.getSnippet().getPublishedAt().toString()
                                    .replace("Z", "")
                                    .replace("T", "T")))
                    .videoUrl("https://www.youtube.com/watch?v=" + youtubeVideo.getId())
                    .thumbnailUrl(youtubeVideo.getSnippet().getThumbnails() != null &&
                            youtubeVideo.getSnippet().getThumbnails().getDefault() != null ?
                            youtubeVideo.getSnippet().getThumbnails().getDefault().getUrl() : null)
                    .processingStatus(Video.ProcessingStatus.PENDING)
                    .collectedAt(LocalDateTime.now())
                    .build();
                    
        } catch (Exception e) {
            log.error("YouTube 영상 변환 중 오류 발생: {}", youtubeVideo.getId(), e);
            return null;
        }
    }
    
    /**
     * ISO 8601 duration을 초 단위로 변환
     * 예: PT1M30S -> 90, PT45S -> 45, PT2M -> 120
     */
    private Integer parseDuration(String duration) {
        if (duration == null || !duration.startsWith("PT")) {
            return 0;
        }
        
        try {
            String timePart = duration.substring(2); // "PT" 제거
            int seconds = 0;
            
            // 분 추출
            if (timePart.contains("M")) {
                String[] parts = timePart.split("M");
                seconds += Integer.parseInt(parts[0]) * 60;
                timePart = parts.length > 1 ? parts[1] : "";
            }
            
            // 초 추출
            if (timePart.contains("S")) {
                String secondsPart = timePart.replace("S", "");
                if (!secondsPart.isEmpty()) {
                    seconds += Integer.parseInt(secondsPart);
                }
            }
            
            return seconds;
        } catch (Exception e) {
            log.warn("Duration 파싱 실패: {}", duration, e);
            return 0;
        }
    }
    
    /**
     * 유효한 쇼츠인지 확인 (2분 이하)
     */
    private boolean isValidShort(Video video) {
        return video != null 
                && video.getDurationSeconds() != null 
                && video.getDurationSeconds() > 0 
                && video.getDurationSeconds() <= 120
                && video.getYoutubeId() != null 
                && !video.getYoutubeId().trim().isEmpty();
    }
    
    /**
     * 시간별로 영상을 분류하고 랜덤 샘플링
     * 24시간을 균등하게 분배하여 각 시간대별로 랜덤 선택
     */
    private List<Video> sampleVideosByHour(List<Video> videos, int targetCount) {
        if (videos.isEmpty() || targetCount <= 0) {
            return Collections.emptyList();
        }
        
        // 시간별로 영상 분류
        List<List<Video>> hourlyVideos = new ArrayList<>(24);
        for (int i = 0; i < 24; i++) {
            hourlyVideos.add(new ArrayList<>());
        }
        
        for (Video video : videos) {
            if (video.getPublishedAt() != null) {
                int hour = video.getPublishedAt().getHour();
                hourlyVideos.get(hour).add(video);
            }
        }
        
        // 각 시간대별 목표 수량 계산
        int videosPerHour = targetCount / 24;
        int remainder = targetCount % 24;
        
        List<Video> sampledVideos = new ArrayList<>();
        
        for (int hour = 0; hour < 24; hour++) {
            List<Video> hourVideos = hourlyVideos.get(hour);
            if (hourVideos.isEmpty()) {
                continue;
            }
            
            int hourTarget = videosPerHour + (hour < remainder ? 1 : 0);
            int sampleSize = Math.min(hourTarget, hourVideos.size());
            
            // 랜덤 샘플링
            Collections.shuffle(hourVideos, random);
            sampledVideos.addAll(hourVideos.subList(0, sampleSize));
        }
        
        log.info("시간별 샘플링 결과: 목표 {}개, 실제 {}개", targetCount, sampledVideos.size());
        return sampledVideos;
    }
}
