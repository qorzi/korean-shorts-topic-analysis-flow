package com.analysis.application.service;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.springframework.stereotype.Component;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * pHash (Perceptual Hash) 생성기
 * 
 * Python imagehash 라이브러리와 동일한 알고리즘을 Java로 구현
 * 
 * pHash 알고리즘:
 * 1. 이미지를 32x32 크기로 리사이즈
 * 2. 2D DCT (Discrete Cosine Transform) 적용
 * 3. 좌상단 8x8 저주파 영역 추출
 * 4. 평균값을 기준으로 이진 해시 생성
 * 5. 64비트 부호 있는 정수로 변환
 */
@Component
@Slf4j
public class PHashGenerator {
    
    private static final int PHASH_SIZE = 8;
    private static final int IMG_SIZE = 32;
    
    /**
     * Python imagehash.phash()와 동일한 pHash 생성
     * 
     * @param grayImage 그레이스케일 평균 프레임
     * @return 64비트 부호 있는 정수 pHash
     */
    public Long generatePHash(Mat grayImage) {
        try {
            // 1. 이미지를 32x32로 리사이즈
            Mat resized = new Mat();
            resize(grayImage, resized, new Size(IMG_SIZE, IMG_SIZE));
            
            // 2. float 타입으로 변환
            Mat floatImg = new Mat();
            resized.convertTo(floatImg, org.bytedeco.opencv.global.opencv_core.CV_32F);
            
            // 3. 2D DCT 적용
            Mat dctImg = new Mat();
            dct(floatImg, dctImg);
            
            // 4. 좌상단 8x8 저주파 영역 추출
            Mat dctLowFreq = dctImg.apply(new org.bytedeco.opencv.opencv_core.Rect(0, 0, PHASH_SIZE, PHASH_SIZE)).clone();
            
            // 5. 평균값 계산 (DC 성분 제외)
            double sum = 0.0;
            int count = 0;
            
            float[] data = new float[PHASH_SIZE * PHASH_SIZE];
            dctLowFreq.ptr(0).get(data);
            
            // DC 성분(0,0)을 제외하고 평균 계산
            for (int i = 1; i < PHASH_SIZE * PHASH_SIZE; i++) {
                sum += data[i];
                count++;
            }
            
            double average = sum / count;
            
            // 6. 이진 해시 생성
            long hash = 0L;
            for (int i = 0; i < PHASH_SIZE * PHASH_SIZE; i++) {
                if (data[i] > average) {
                    hash |= (1L << i);
                }
            }
            
            // 7. Python의 imagehash와 동일한 부호 있는 64비트 정수로 변환
            Long signedHash = convertToSignedLong(hash);
            
            // 메모리 정리
            resized.release();
            floatImg.release();
            dctImg.release();
            dctLowFreq.release();
            
            log.debug("pHash 생성 완료: 0x{} -> {}", Long.toHexString(hash), signedHash);
            return signedHash;
            
        } catch (Exception e) {
            log.error("pHash 생성 중 오류 발생", e);
            return null;
        }
    }
    
    /**
     * Python 코드와 동일한 16진수 문자열을 부호 있는 Long으로 변환
     * PostgreSQL의 BIGINT와 호환되도록 처리
     */
    private Long convertToSignedLong(long unsignedHash) {
        // 64비트 부호 없는 정수를 부호 있는 정수로 변환
        if (unsignedHash >= (1L << 63)) {
            return unsignedHash - (1L << 64);
        } else {
            return unsignedHash;
        }
    }
    
    /**
     * 두 pHash의 해밍 거리 계산
     * Python 코드의 hamming_distance_for_long과 동일
     * 
     * @param hash1 첫 번째 pHash
     * @param hash2 두 번째 pHash
     * @return 해밍 거리 (0-64)
     */
    public int calculateHammingDistance(long hash1, long hash2) {
        long xorResult = hash1 ^ hash2;
        return Long.bitCount(xorResult);
    }
    
    /**
     * 두 영상이 유사한지 판단
     * 일반적으로 해밍 거리가 8 이하면 유사한 것으로 간주
     * 
     * @param hash1 첫 번째 pHash
     * @param hash2 두 번째 pHash
     * @param threshold 유사성 임계값 (기본 8)
     * @return 유사 여부
     */
    public boolean isSimilar(long hash1, long hash2, int threshold) {
        return calculateHammingDistance(hash1, hash2) <= threshold;
    }
    
    /**
     * 기본 임계값(8)으로 유사성 판단
     */
    public boolean isSimilar(long hash1, long hash2) {
        return isSimilar(hash1, hash2, 8);
    }
}
