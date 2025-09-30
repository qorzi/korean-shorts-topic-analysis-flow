# 한국 쇼츠 토픽 분석 플로우

## 📋 프로젝트 개요
한국의 YouTube 쇼츠 영상을 수집하고 분석하여 양산형 콘텐츠의 비율을 파악하는 프로젝트입니다.

## 🎯 목표
- 한국의 쇼츠를 분석해서 양산형 쇼츠가 얼마나 많은지 알아보자.
- 영상 지문(pHash) 기술을 활용한 유사 콘텐츠 탐지
- 대규모 영상 데이터 처리를 위한 확장 가능한 아키텍처 구축

## 🏗️ 프로젝트 구조

### 1. video-processing-service (스프링부트 프로젝트)
YouTube 쇼츠 영상 수집 및 처리를 담당하는 핵심 서비스

```
video-processing-service/
├── src/main/java/com/analysis/
│   ├── VideoProcessingServiceApplication.java
│   ├── application/
│   │   ├── scheduler/DailyCollectionScheduler.java
│   │   └── service/
│   │       ├── VideoCollectionService.java
│   │       ├── VideoProcessingService.java
│   │       └── PHashGenerator.java
│   ├── domain/model/Video.java
│   ├── infrastructure/
│   │   ├── persistence/VideoRepository.java
│   │   ├── queue/
│   │   │   ├── QueueManager.java
│   │   │   ├── VideoProcessingTask.java
│   │   │   ├── AudioProcessingTask.java
│   │   │   └── QueueStatus.java
│   │   └── youtube/YouTubeApiService.java
│   └── presentation/controller/VideoController.java
└── src/main/resources/
    └── application.yml
```

## ⚙️ 주요 기능

### 1. 일일 영상 수집 시스템
- **실행 시간**: 매일 자정 (00:00:00)
- **수집 조건**:
  - 지역: 대한민국 (KR)
  - 영상 길이: 2분 이하 쇼츠
  - 일일 목표: 1,000개 영상 (설정 가능)
  - 화질: 240p (대역폭 최소화)
- **샘플링**: 24시간을 균등 분할하여 시간별 랜덤 샘플링

### 2. 인메모리 큐 시스템
- **영상 처리 큐**: 영상 다운로드 및 pHash 생성 작업
- **오디오 처리 큐**: 오디오 추출 및 분석 작업 (향후 확장)
- **확장성**: Redis나 RabbitMQ로 전환 가능하도록 설계
- **용량**: 각 큐당 5,000개 작업 (설정 가능)

### 3. JavaCV 기반 영상 처리
#### pHash 생성 알고리즘 (Python 코드와 동일):
1. **프레임 추출**: 영상을 30개 세그먼트로 분할, 초당 10프레임 추출
2. **전처리**: 그레이스케일 변환 및 히스토그램 평활화
3. **평균 프레임**: 추출된 프레임들의 평균 이미지 생성
4. **pHash 생성**: 
   - 32x32 리사이즈
   - 2D DCT 변환
   - 8x8 저주파 영역 추출
   - 64비트 부호 있는 정수 지문 생성

#### 해밍 거리 기반 유사도 측정:
```java
// 두 pHash의 해밍 거리 계산
int hammingDistance = Long.bitCount(hash1 ^ hash2);
// 일반적으로 거리 8 이하면 유사한 영상으로 판단
```

## 🔧 기술 스택

### Backend
- **Java 17**
- **Spring Boot 3.2.0**
- **Spring Data JPA**
- **Spring Scheduler**
- **JavaCV 1.5.9** (OpenCV + FFmpeg)
- **YouTube Data API v3**

### Database
- **H2** (개발용)
- **PostgreSQL** (운영용)

### Queue System
- **In-Memory Queue** (현재)
- **Redis/RabbitMQ** (향후 확장)

## 🚀 설치 및 실행

### 1. 환경 설정
```bash
# YouTube Data API 키 설정
export YOUTUBE_API_KEY=your-youtube-api-key

# 데이터베이스 설정 (선택사항)
export DB_USERNAME=postgres
export DB_PASSWORD=password
```

### 2. 빌드 및 실행
```bash
cd video-processing-service
./gradlew build
./gradlew bootRun
```

### 3. H2 데이터베이스 콘솔 (개발용)
```
URL: http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:testdb
Username: sa
Password: (비어둠)
```

## 📊 API 엔드포인트

### 영상 관리
```bash
# 영상 목록 조회 (페이징)
GET /api/videos?page=0&size=20&status=COMPLETED

# 영상 상세 정보
GET /api/videos/{id}

# 수동 영상 수집
POST /api/videos/collect?targetDate=2023-12-01

# 특정 영상 처리 (높은 우선순위)
POST /api/videos/{id}/process

# 유사한 영상 검색 (pHash 기반)
GET /api/videos/{id}/similar?threshold=8
```

### 시스템 모니터링
```bash
# 큐 상태 조회
GET /api/videos/queue/status

# 처리 통계
GET /api/videos/stats

# 실패한 영상 재시도
POST /api/videos/retry-failed

# 헬스체크
GET /actuator/health
```

## ⚡ 성능 최적화

### 1. 영상 처리 최적화
- **대역폭 최소화**: 240p 화질로 다운로드
- **프레임 추출 향상**: 초당 10프레임 (기존 6프레임에서 상향)
- **멀티스레드 처리**: 4개 워커 스레드로 병렬 처리
- **메모리 관리**: 임시 파일 자동 정리

### 2. 데이터베이스 최적화
- **인덱스**: youtube_id, processing_status, phash_fingerprint
- **배치 저장**: 대량 데이터 효율적 처리
- **커넥션 풀**: HikariCP 사용

### 3. 큐 시스템 최적화
- **백프레셔**: 큐 용량 초과 시 자동 대기
- **재시도 메커니즘**: 실패한 작업 자동 재시도
- **모니터링**: 큐 상태 실시간 추적

## 📈 확장 계획

### 1. 외부 큐 시스템 전환
```yaml
# application.yml 설정 예시
app:
  queue:
    type: redis  # in-memory, redis, rabbitmq
    redis:
      host: localhost
      port: 6379
```

### 2. 오디오 분석 기능
- 음성 인식 및 텍스트 변환
- 오디오 지문 생성
- 배경음악 탐지

### 3. 분산 처리
- Kubernetes 기반 스케일링
- 멀티 인스턴스 로드 밸런싱
- 분산 작업 큐 관리

## 🔍 모니터링 및 로깅

### 로그 레벨
- **DEBUG**: 상세한 처리 과정
- **INFO**: 주요 이벤트 및 통계
- **WARN**: 주의가 필요한 상황
- **ERROR**: 처리 실패 및 예외

### 주요 메트릭
- 일일 수집 영상 수
- 큐 사용률 및 처리 속도
- pHash 생성 성공률
- API 응답 시간

## 🤝 기여 방법
1. Fork 프로젝트
2. Feature 브랜치 생성 (`git checkout -b feature/amazing-feature`)
3. 변경사항 커밋 (`git commit -m 'Add amazing feature'`)
4. 브랜치에 Push (`git push origin feature/amazing-feature`)
5. Pull Request 생성

## 📄 라이선스
이 프로젝트는 MIT 라이선스를 따릅니다.

## 📞 문의
프로젝트 관련 문의나 버그 리포트는 Issues 페이지를 이용해 주세요.