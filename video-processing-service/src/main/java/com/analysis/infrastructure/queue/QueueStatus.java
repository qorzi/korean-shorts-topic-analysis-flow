package com.analysis.infrastructure.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 큐 상태 정보를 나타내는 DTO
 * 
 * 모니터링 및 관리 목적으로 사용
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueStatus {
    
    /**
     * 큐 타입 (VIDEO_PROCESSING, AUDIO_PROCESSING)
     */
    private String queueType;
    
    /**
     * 현재 큐에 있는 작업 수
     */
    private Integer currentSize;
    
    /**
     * 큐의 최대 용량
     */
    private Integer capacity;
    
    /**
     * 큐 사용률 (백분율)
     */
    public Double getUsagePercentage() {
        if (capacity == null || capacity == 0) {
            return 0.0;
        }
        return (currentSize.doubleValue() / capacity.doubleValue()) * 100.0;
    }
    
    /**
     * 큐가 가득 찼는지 확인
     */
    public Boolean isFull() {
        return currentSize != null && capacity != null && currentSize.equals(capacity);
    }
    
    /**
     * 큐가 비어있는지 확인
     */
    public Boolean isEmpty() {
        return currentSize == null || currentSize == 0;
    }
    
    /**
     * 남은 용량
     */
    public Integer getRemainingCapacity() {
        if (capacity == null || currentSize == null) {
            return 0;
        }
        return capacity - currentSize;
    }
}
