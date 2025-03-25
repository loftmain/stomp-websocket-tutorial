package com.example.messagingstompwebsocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Controller
public class FileWebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(FileWebSocketController.class);

    private static final int MAX_MONITORS_PER_USER = 5;
    private static final int MAX_TOTAL_MONITORS = 100;
    private static final long INACTIVE_TIMEOUT_MINUTES = 30;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 키를 "filePath:userId" 형식으로 변경하여 사용자별로 독립적인 모니터링 관리
    private final ConcurrentHashMap<String, FileMonitor> activeMonitors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // 세션ID와 사용자ID를 매핑
    private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    // 사용자별 활성 모니터 수 추적
    private final ConcurrentHashMap<String, Integer> userMonitorCount = new ConcurrentHashMap<>();

    // 모니터 마지막 활동 시간 추적
    private final ConcurrentHashMap<String, Instant> monitorLastActivity = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 오래된 모니터 정리 작업 시작
        cleanupScheduler.scheduleAtFixedRate(this::cleanupInactiveMonitors, 10, 10, TimeUnit.MINUTES);
        logger.info("FileWebSocketController 초기화 완료 - 스케줄러 시작됨");
        logger.info("15초마다 데이터 통계 로깅 스케줄러 설정됨");
    }

    @PreDestroy
    public void cleanup() {
        System.out.println("애플리케이션 종료 - 모든 모니터 정리 중...");

        // 모든 모니터 중지
        for (Map.Entry<String, FileMonitor> entry : activeMonitors.entrySet()) {
            try {
                entry.getValue().stop();
            } catch (Exception e) {
                System.err.println("모니터 정리 중 오류 발생: " + e.getMessage());
            }
        }
        activeMonitors.clear();

        // 스케줄러 종료
        try {
            scheduler.shutdownNow();
            boolean terminated = scheduler.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                System.err.println("스케줄러 종료 타임아웃 - 강제 종료합니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 정리 스케줄러 종료
        try {
            cleanupScheduler.shutdownNow();
            cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("모든 리소스 정리 완료");
    }

    @MessageMapping("/readFile")
    public void readFile(FileRequest fileRequest, SimpMessageHeaderAccessor headerAccessor) throws IOException {
        String filePath = fileRequest.getFilePath();
        String userId = fileRequest.getUserId();

        // 세션 ID 가져오기
        String sessionId = headerAccessor.getSessionId();

        // 세션 ID와 사용자 ID 매핑 저장
        sessionUserMap.put(sessionId, userId);

        // 사용자별 고유 ID 생성
        String monitorKey = createMonitorKey(filePath, userId);

        // 리소스 제한 검사
        if (!activeMonitors.containsKey(monitorKey)) {
            // 사용자별 모니터 수 제한 검사
            int userMonitors = userMonitorCount.getOrDefault(userId, 0);
            if (userMonitors >= MAX_MONITORS_PER_USER) {
                messagingTemplate.convertAndSend("/topic/error/" + userId,
                        "최대 모니터 수 제한(" + MAX_MONITORS_PER_USER + ")에 도달했습니다. 다른 모니터를 중지하고 다시 시도하세요.");
                return;
            }

            // 총 모니터 수 제한 검사
            if (activeMonitors.size() >= MAX_TOTAL_MONITORS) {
                messagingTemplate.convertAndSend("/topic/error/" + userId,
                        "시스템 최대 모니터 수 제한에 도달했습니다. 나중에 다시 시도하세요.");
                return;
            }
        }

        try {
            List<String> lastLines = readLastLines(filePath, 20, userId);

            // 해당 사용자의 기존 모니터가 있으면 중지
            if (activeMonitors.containsKey(monitorKey)) {
                activeMonitors.get(monitorKey).stop();
                activeMonitors.remove(monitorKey);
                userMonitorCount.compute(userId, (k, v) -> v - 1);
            }

            // 새 모니터 시작 - 사용자별 전용 토픽으로 메시지 발송
            FileMonitor monitor = new FileMonitor(filePath, messagingTemplate, userId);
            activeMonitors.put(monitorKey, monitor);
            monitorLastActivity.put(monitorKey, Instant.now());

            // 사용자 모니터 카운트 증가
            userMonitorCount.compute(userId, (k, v) -> (v == null) ? 1 : v + 1);

            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (monitor.isRunning()) {
                        monitor.run();
                        // 활동 시간 갱신
                        monitorLastActivity.put(monitorKey, Instant.now());
                    }
                } catch (Exception e) {
                    System.err.println("모니터 실행 중 오류 발생: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 1, 1, TimeUnit.SECONDS);

            // 초기 파일 내용을 사용자별 토픽으로 전송
            messagingTemplate.convertAndSend("/topic/fileContent/" + userId, lastLines);

        } catch (IOException e) {
            // 클라이언트에 오류 알림
            messagingTemplate.convertAndSend("/topic/error/" + userId, "파일 읽기 오류: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 오래된/비활성 모니터 정리
     */
    private void cleanupInactiveMonitors() {
        System.out.println("[" + LocalDateTime.now() + "] 비활성 모니터 정리 작업 실행 중...");
        Instant now = Instant.now();

        List<String> keysToRemove = monitorLastActivity.entrySet().stream()
                .filter(entry -> {
                    long minutes = java.time.Duration.between(entry.getValue(), now).toMinutes();
                    return minutes > INACTIVE_TIMEOUT_MINUTES;
                })
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        for (String key : keysToRemove) {
            FileMonitor monitor = activeMonitors.get(key);
            if (monitor != null) {
                System.out.println("비활성 모니터 제거: " + key + " (마지막 활동: " +
                        java.time.Duration.between(monitorLastActivity.get(key), now).toMinutes() + "분 전)");

                monitor.stop();
                activeMonitors.remove(key);
                monitorLastActivity.remove(key);

                // 사용자별 모니터 카운트 감소
                String userId = key.substring(key.lastIndexOf(":") + 1);
                userMonitorCount.compute(userId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
            }
        }

        System.out.println("[" + LocalDateTime.now() + "] 비활성 모니터 정리 완료. 제거된 모니터: " + keysToRemove.size());
    }

    /**
     * 세션 연결 해제 시 호출되는 이벤트 리스너
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        String sessionId = event.getSessionId();

        // 세션에 매핑된 사용자 ID 찾기
        String userId = sessionUserMap.remove(sessionId);

        if (userId != null) {
            // 해당 사용자의 모든 모니터 찾아서 종료
            stopUserMonitors(userId);
            System.out.println("사용자 " + userId + "의 연결이 종료되어 모든 파일 모니터링을 중지했습니다.");
        }
    }

    /**
     * 특정 사용자의 모든 FileMonitor를 중지
     */
    private void stopUserMonitors(String userId) {
        // 사용자와 관련된 모든 모니터 키 찾기
        List<String> userMonitorKeys = activeMonitors.keySet().stream()
                .filter(key -> key.endsWith(":" + userId))
                .collect(Collectors.toList());

        // 모든 모니터 중지 및 제거
        for (String key : userMonitorKeys) {
            FileMonitor monitor = activeMonitors.get(key);
            if (monitor != null) {
                monitor.stop();
                activeMonitors.remove(key);
                monitorLastActivity.remove(key);
            }
        }

        // 사용자 모니터 카운트 초기화
        userMonitorCount.remove(userId);
    }

    // 모니터링 키 생성 헬퍼 메소드
    private String createMonitorKey(String filePath, String userId) {
        return filePath + ":" + userId;
    }

    private List<String> readLastLines(String filePath, int lineCount, String userId) throws IOException {
        List<String> result = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("파일이 존재하지 않습니다: " + filePath);
        }

        // try-with-resources 사용하여 리소스 누수 방지
        try {
            List<String> allLines = Files.readAllLines(Paths.get(filePath));
            int startIndex = Math.max(0, allLines.size() - lineCount);

            for (int i = startIndex; i < allLines.size(); i++) {
                String line = allLines.get(i);
                if (shouldIncludeLine(line, userId)) {
                    result.add(line);
                }
            }
        } catch (IOException e) {
            System.err.println("파일 읽기 오류: " + e.getMessage());
            throw e;
        }

        return result;
    }

    // 사용자 ID에 따라 라인을 필터링하는 헬퍼 메소드
    private boolean shouldIncludeLine(String line, String userId) {
        if (line == null || line.isEmpty()) {
            return false;
        }

        char firstChar = line.charAt(0);
        if ("user01".equals(userId)) {
            return firstChar == 'P';
        } else if ("user02".equals(userId)) {
            return firstChar == 'W';
        }

        // 다른 사용자는 모든 라인 표시
        return true;
    }

    // 파일 모니터링을 위한 내부 클래스
    private static class FileMonitor implements Runnable {
        private final String filePath;
        private final SimpMessagingTemplate messagingTemplate;
        private final String userId;
        private long lastPosition;
        private volatile boolean running = true;

        public FileMonitor(String filePath, SimpMessagingTemplate messagingTemplate, String userId) {
            this.filePath = filePath;
            this.messagingTemplate = messagingTemplate;
            this.userId = userId;
            File file = new File(filePath);
            this.lastPosition = file.length();
        }

        public void stop() {
            running = false;
        }

        public boolean isRunning() {
            return running;
        }

        @Override
        public void run() {
            if (!running)
                return;

            File file = new File(filePath);
            if (!file.exists() || !file.canRead()) {
                System.err.println("파일이 존재하지 않거나 읽을 수 없습니다: " + filePath);
                stop(); // 접근할 수 없는 파일은 모니터링 중단
                return;
            }

            long length = file.length();

            if (length > lastPosition) {
                // 새로운 내용이 추가됨
                List<String> newLines = new ArrayList<>();

                // try-with-resources 사용하여 리소스 누수 방지
                try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                    raf.seek(lastPosition);

                    String line;
                    while ((line = raf.readLine()) != null) {
                        // 바이트를 문자열로 변환 (인코딩 고려)
                        line = new String(line.getBytes("ISO-8859-1"), "UTF-8");
                        // 사용자 ID에 따라 필터링
                        if (shouldIncludeLine(line, userId)) {
                            newLines.add(line);
                        }
                    }

                    lastPosition = raf.getFilePointer();

                    if (!newLines.isEmpty()) {
                        // 사용자별 전용 토픽으로 메시지 발송
                        messagingTemplate.convertAndSend("/topic/fileUpdate/" + userId, newLines);
                    }
                } catch (IOException e) {
                    System.err.println("[" + LocalDateTime.now() + "] 파일 모니터링 오류: " + e.getMessage());

                    // 심각한 오류는 클라이언트에 알림
                    try {
                        messagingTemplate.convertAndSend("/topic/error/" + userId,
                                "파일 모니터링 오류: " + e.getMessage());
                    } catch (Exception ex) {
                        // 메시지 전송 실패 시 무시
                    }
                }
            } else if (length < lastPosition) {
                // 파일이 줄어든 경우 (다시 시작)
                lastPosition = length;
            }
        }

        // 사용자 ID에 따라 라인을 필터링하는 헬퍼 메소드
        private boolean shouldIncludeLine(String line, String userId) {
            if (line == null || line.isEmpty()) {
                return false;
            }

            char firstChar = line.charAt(0);
            if ("user01".equals(userId)) {
                return firstChar == 'P';
            } else if ("user02".equals(userId)) {
                return firstChar == 'W';
            }

            // 다른 사용자는 모든 라인 표시
            return true;
        }
    }

    /**
     * 15초마다 ConcurrentHashMap과 스케줄러 데이터 통계 로깅
     */
    @Scheduled(fixedRate = 5000)
    public void logDataStatistics() {
        logger.info("데이터 통계 로깅 스케줄러 실행 중... (현재 시간: {})",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String currentTime = LocalDateTime.now().format(formatter);

        logger.info("======== 파일 웹소켓 데이터 통계 ({}) ========", currentTime);

        // 활성 모니터 통계
        logger.info("활성 모니터 수: {}", activeMonitors.size());
        if (!activeMonitors.isEmpty()) {
            logger.info("활성 모니터 목록:");
            activeMonitors.forEach((key, monitor) -> {
                String[] parts = key.split(":");
                String filePath = parts[0];
                String userId = parts.length > 1 ? parts[1] : "unknown";
                logger.info("  - 파일: {}, 사용자: {}, 실행 상태: {}",
                        filePath, userId, monitor.isRunning());
            });
        }

        // 세션-사용자 매핑 통계
        logger.info("활성 세션 수: {}", sessionUserMap.size());
        if (!sessionUserMap.isEmpty() && sessionUserMap.size() < 10) { // 너무 많은 경우 상세 출력 제한
            logger.info("세션-사용자 매핑:");
            sessionUserMap.forEach((sessionId, userId) -> logger.info("  - 세션: {}, 사용자: {}", sessionId, userId));
        }

        // 사용자별 모니터 수 통계
        logger.info("사용자별 활성 모니터 수:");
        if (userMonitorCount.isEmpty()) {
            logger.info("  - 활성 사용자 없음");
        } else {
            userMonitorCount.forEach((userId, count) -> logger.info("  - 사용자: {}, 모니터 수: {}", userId, count));
        }

        // 모니터 활동 시간 통계
        logger.info("모니터 마지막 활동 시간:");
        if (monitorLastActivity.isEmpty()) {
            logger.info("  - 활동 기록 없음");
        } else {
            Instant now = Instant.now();
            monitorLastActivity.forEach((key, lastActivity) -> {
                long minutesAgo = java.time.Duration.between(lastActivity, now).toMinutes();
                String formattedTime = LocalDateTime.ofInstant(lastActivity, ZoneId.systemDefault())
                        .format(formatter);
                logger.info("  - 모니터: {}, 마지막 활동: {} ({}분 전)",
                        key, formattedTime, minutesAgo);
            });
        }

        // 스케줄러 통계
        logger.info("스케줄러 상태:");
        logger.info("  - 메인 스케줄러: isShutdown={}, isTerminated={}",
                scheduler.isShutdown(), scheduler.isTerminated());
        logger.info("  - 정리 스케줄러: isShutdown={}, isTerminated={}",
                cleanupScheduler.isShutdown(), cleanupScheduler.isTerminated());

        logger.info("===============================================");
    }
}
