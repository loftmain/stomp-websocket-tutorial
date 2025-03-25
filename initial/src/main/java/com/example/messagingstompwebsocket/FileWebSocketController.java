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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
    private final ExecutorService watchExecutor = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // 세션ID와 사용자ID를 매핑
    private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    // 사용자별 활성 모니터 수 추적
    private final ConcurrentHashMap<String, Integer> userMonitorCount = new ConcurrentHashMap<>();

    // 모니터 마지막 활동 시간 추적
    private final ConcurrentHashMap<String, Instant> monitorLastActivity = new ConcurrentHashMap<>();

    // WatchService와 경로를 디렉토리별로 관리
    private final ConcurrentHashMap<Path, WatchService> watchServices = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, List<String>> directoryMonitors = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // 오래된 모니터 정리 작업 시작
        cleanupScheduler.scheduleAtFixedRate(this::cleanupInactiveMonitors, 10, 10, TimeUnit.MINUTES);
        logger.info("FileWebSocketController 초기화 완료 - WatchService 모니터링 시스템 시작됨");
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

        // 모든 WatchService 종료
        for (WatchService watchService : watchServices.values()) {
            try {
                watchService.close();
            } catch (IOException e) {
                System.err.println("WatchService 종료 중 오류 발생: " + e.getMessage());
            }
        }
        watchServices.clear();

        // 스케줄러 종료
        try {
            watchExecutor.shutdownNow();
            boolean terminated = watchExecutor.awaitTermination(5, TimeUnit.SECONDS);
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
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                throw new IOException("파일이 존재하지 않습니다: " + filePath);
            }

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

            // 디렉토리 WatchService 등록
            Path directory = path.getParent();
            registerDirectoryWatcher(directory, monitorKey);

            // 파일 모니터링 시작
            watchExecutor.submit(() -> monitor.startWatching());

            // 초기 파일 내용을 사용자별 토픽으로 전송
            messagingTemplate.convertAndSend("/topic/fileContent/" + userId, lastLines);

        } catch (IOException e) {
            // 클라이언트에 오류 알림
            messagingTemplate.convertAndSend("/topic/error/" + userId, "파일 읽기 오류: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 디렉토리에 WatchService 등록
     */
    private void registerDirectoryWatcher(Path directory, String monitorKey) throws IOException {
        // 디렉토리에 이미 WatchService가 등록되어 있는지 확인
        if (!watchServices.containsKey(directory)) {
            // 새 WatchService 생성 및 등록
            WatchService watchService = FileSystems.getDefault().newWatchService();
            directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_CREATE);

            watchServices.put(directory, watchService);
            directoryMonitors.put(directory, new ArrayList<>());

            // 새 WatchService 폴링 스레드 시작
            watchExecutor.submit(() -> pollWatchService(directory, watchService));
            logger.info("디렉토리에 WatchService 등록: {}", directory);
        }

        // 디렉토리에 모니터키 추가
        directoryMonitors.compute(directory, (k, v) -> {
            if (v == null)
                v = new ArrayList<>();
            v.add(monitorKey);
            return v;
        });
    }

    /**
     * WatchService 이벤트 폴링 메서드
     */
    private void pollWatchService(Path directory, WatchService watchService) {
        try {
            while (true) {
                WatchKey key = watchService.take(); // 이벤트가 발생할 때까지 차단됨

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    // 파일 이름 얻기
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = directory.resolve(fileName);

                    // 변경된 파일에 대한 모니터 찾기 및 처리
                    List<String> monitorsToNotify = directoryMonitors.get(directory).stream()
                            .filter(monitorKey -> {
                                String filePath = monitorKey.substring(0, monitorKey.lastIndexOf(":"));
                                return Paths.get(filePath).equals(fullPath);
                            })
                            .collect(Collectors.toList());

                    for (String monitorKey : monitorsToNotify) {
                        FileMonitor monitor = activeMonitors.get(monitorKey);
                        if (monitor != null && monitor.isRunning()) {
                            if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                                monitor.processFileChange();
                                monitorLastActivity.put(monitorKey, Instant.now());
                            } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                String userId = monitorKey.substring(monitorKey.lastIndexOf(":") + 1);
                                messagingTemplate.convertAndSend("/topic/error/" + userId,
                                        "감시 중인 파일이 삭제되었습니다: " + fullPath);
                            }
                        }
                    }
                }

                // 키 재설정 (다음 이벤트를 받을 준비)
                boolean valid = key.reset();
                if (!valid) {
                    // 디렉토리가 더 이상 액세스할 수 없는 경우
                    watchServices.remove(directory);
                    directoryMonitors.remove(directory);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("WatchService 폴링 중단됨: {}", directory);
        } catch (Exception e) {
            logger.error("WatchService 폴링 중 오류 발생: {}", e.getMessage(), e);
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

                // 디렉토리 모니터 목록에서 제거
                String filePath = key.substring(0, key.lastIndexOf(":"));
                Path directory = Paths.get(filePath).getParent();

                if (directoryMonitors.containsKey(directory)) {
                    directoryMonitors.compute(directory, (k, v) -> {
                        if (v != null) {
                            v.remove(key);
                            // 디렉토리에 더 이상 모니터가 없으면 WatchService 제거
                            if (v.isEmpty() && watchServices.containsKey(directory)) {
                                try {
                                    watchServices.get(directory).close();
                                    watchServices.remove(directory);
                                } catch (IOException e) {
                                    logger.error("WatchService 종료 중 오류: {}", e.getMessage());
                                }
                                return null;
                            }
                        }
                        return v;
                    });
                }
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

                // 디렉토리 모니터 목록에서 제거
                String filePath = key.substring(0, key.lastIndexOf(":"));
                Path directory = Paths.get(filePath).getParent();

                if (directoryMonitors.containsKey(directory)) {
                    directoryMonitors.compute(directory, (k, v) -> {
                        if (v != null) {
                            v.remove(key);
                            // 디렉토리에 더 이상 모니터가 없으면 WatchService 제거
                            if (v.isEmpty() && watchServices.containsKey(directory)) {
                                try {
                                    watchServices.get(directory).close();
                                    watchServices.remove(directory);
                                } catch (IOException e) {
                                    logger.error("WatchService 종료 중 오류: {}", e.getMessage());
                                }
                                return null;
                            }
                        }
                        return v;
                    });
                }
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
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new IOException("파일이 존재하지 않습니다: " + filePath);
        }

        // NIO를 사용하여 파일 읽기 최적화
        try {
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int startIndex = Math.max(0, allLines.size() - lineCount);

            for (int i = startIndex; i < allLines.size(); i++) {
                String line = allLines.get(i);
                if (shouldIncludeLine(line, userId)) {
                    result.add(line);
                }
            }
        } catch (IOException e) {
            logger.error("파일 읽기 오류: {}", e.getMessage(), e);
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

    // 파일 모니터링을 위한 내부 클래스 - WatchService 기반으로 변경
    private class FileMonitor {
        private final String filePath;
        private final SimpMessagingTemplate messagingTemplate;
        private final String userId;
        private volatile boolean running = true;
        private long lastSize;
        private final Path path;

        public FileMonitor(String filePath, SimpMessagingTemplate messagingTemplate, String userId) {
            this.filePath = filePath;
            this.messagingTemplate = messagingTemplate;
            this.userId = userId;
            this.path = Paths.get(filePath);

            try {
                if (Files.exists(path)) {
                    this.lastSize = Files.size(path);
                } else {
                    this.lastSize = 0;
                }
            } catch (IOException e) {
                logger.error("파일 크기 확인 중 오류: {}", e.getMessage(), e);
                this.lastSize = 0;
            }
        }

        public void stop() {
            running = false;
        }

        public boolean isRunning() {
            return running;
        }

        public void startWatching() {
            logger.info("파일 모니터링 시작: {}", filePath);
        }

        /**
         * 파일 변경 처리
         */
        public void processFileChange() {
            if (!running)
                return;

            try {
                if (!Files.exists(path)) {
                    logger.warn("파일이 존재하지 않습니다: {}", filePath);
                    messagingTemplate.convertAndSend("/topic/error/" + userId,
                            "파일이 존재하지 않거나 접근할 수 없습니다: " + filePath);
                    return;
                }

                long currentSize = Files.size(path);

                if (currentSize > lastSize) {
                    // 파일이 커졌을 때만 새 내용 읽기
                    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                        ByteBuffer buffer = ByteBuffer.allocate((int) (currentSize - lastSize));

                        // 마지막으로 읽은 위치부터 채널 위치 설정
                        channel.position(lastSize);

                        // 새 내용 읽기
                        channel.read(buffer);
                        buffer.flip();

                        // 버퍼 내용을 문자열로 변환
                        String newContent = StandardCharsets.UTF_8.decode(buffer).toString();

                        // 줄 단위로 처리
                        List<String> newLines = new ArrayList<>();
                        for (String line : newContent.split("\\r?\\n")) {
                            if (!line.isEmpty() && shouldIncludeLine(line, userId)) {
                                newLines.add(line);
                            }
                        }

                        if (!newLines.isEmpty()) {
                            messagingTemplate.convertAndSend("/topic/fileUpdate/" + userId, newLines);
                        }
                    }

                    // 마지막 위치 업데이트
                    lastSize = currentSize;

                } else if (currentSize < lastSize) {
                    // 파일이 줄어든 경우 (다시 시작)
                    lastSize = currentSize;

                    // 파일이 줄어든 경우 전체 파일 다시 읽기
                    List<String> allLines = readLastLines(filePath, 20, userId);
                    messagingTemplate.convertAndSend("/topic/fileContent/" + userId, allLines);
                    logger.info("파일 크기가 감소하여 전체 내용을 다시 읽습니다: {}", filePath);
                }
                // 파일 크기 변화가 없으면 아무 작업도 하지 않음

            } catch (IOException e) {
                logger.error("파일 변경 처리 중 오류: {}", e.getMessage(), e);
                messagingTemplate.convertAndSend("/topic/error/" + userId,
                        "파일 모니터링 오류: " + e.getMessage());
            }
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
                watchExecutor.isShutdown(), watchExecutor.isTerminated());
        logger.info("  - 정리 스케줄러: isShutdown={}, isTerminated={}",
                cleanupScheduler.isShutdown(), cleanupScheduler.isTerminated());

        logger.info("===============================================");
    }
}
