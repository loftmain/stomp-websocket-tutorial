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
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Controller
public class FileWebSocketController {

    private static final Logger logger = LoggerFactory.getLogger(FileWebSocketController.class);

    // 고정된 단일 파일 경로 설정 (시스템에 맞게 경로 조정 필요)
    private static final String MONITORED_FILE_PATH = "/Users/ijinjae/Documents/code/clang/s2o/sms/IT.dat";

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 파일 모니터 (단일 파일 전용)
    private FileMonitor fileMonitor;

    // 파일 구독자 관리
    private final Set<String> fileSubscribers = ConcurrentHashMap.newKeySet();

    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService cleanupScheduler = Executors.newSingleThreadScheduledExecutor();

    // 세션ID와 사용자ID를 매핑
    private final ConcurrentHashMap<String, String> sessionUserMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, List<String>> userServerMap = new ConcurrentHashMap<>();

    // 파일 WatchService 관련 변수
    private WatchService watchService;
    private Future<?> watchServiceTask;
    private AtomicBoolean watchServiceStopFlag = new AtomicBoolean(false);
    private Path monitoredDirectory;
    private Instant lastActivityTime = Instant.now();

    @PostConstruct
    public void init() {
        // 애플리케이션 시작 시 파일 모니터 초기화
        try {
            Path path = Paths.get(MONITORED_FILE_PATH);

            if (!Files.exists(path)) {
                logger.error("모니터링할 파일이 존재하지 않습니다: {}", MONITORED_FILE_PATH);
                return;
            }

            // TODO: Timezone 설정
            // TODO: backend coCode 불러오기
            // TODO: 라이센스 확인
            // TODO: INPUT IP CHECK
            // 인증 권한 체크 ->
            // 필터링 있음 / 필터링 없음 / ITSM 콘솔 권한

            monitoredDirectory = path.getParent();

            // 파일 모니터 생성
            fileMonitor = new FileMonitor(MONITORED_FILE_PATH, messagingTemplate);

            // WatchService 등록
            setupWatchService();

            // 파일 모니터링 시작
            watchExecutor.submit(() -> fileMonitor.startWatching());

            logger.info("단일 파일 모니터링 시작: {}", MONITORED_FILE_PATH);

            // 활동 시간 업데이트 스케줄러 설정
            cleanupScheduler.scheduleAtFixedRate(() -> lastActivityTime = Instant.now(), 5, 5, TimeUnit.MINUTES);

            logger.info("FileWebSocketController 초기화 완료 - 단일 파일 모니터링 시스템 시작됨");
            logger.info("5초마다 데이터 통계 로깅 스케줄러 설정됨");

            // 유저 서버 맵 예제 데이터 초기화
            List<String> user01 = new ArrayList<>();
            user01.add("itops05");
            user01.add("GM-ITSM");

            List<String> user02 = new ArrayList<>();
            user02.add("itops05");

            userServerMap.put("user01", user01);
            userServerMap.put("user02", user02);
        } catch (IOException e) {
            logger.error("파일 모니터 초기화 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * WatchService 설정
     */
    private void setupWatchService() throws IOException {
        watchService = FileSystems.getDefault().newWatchService();
        monitoredDirectory.register(watchService,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

        // 스레드 중지 플래그 초기화
        watchServiceStopFlag.set(false);

        // WatchService 폴링 스레드 시작
        watchServiceTask = watchExecutor.submit(() -> pollWatchService());

        logger.info("WatchService 등록 완료: {}", monitoredDirectory);
    }

    @PreDestroy
    public void cleanup() {
        logger.info("애플리케이션 종료 - 모니터 정리 중...");

        // 파일 모니터 중지
        if (fileMonitor != null) {
            fileMonitor.stop();
        }

        // 구독자 목록 정리
        fileSubscribers.clear();

        // WatchService 종료
        safelyCloseWatchService();

        // 스케줄러 종료
        try {
            watchExecutor.shutdownNow();
            watchExecutor.awaitTermination(5, TimeUnit.SECONDS);
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

        logger.info("모든 리소스 정리 완료");
    }

    @MessageMapping("/readFile")
    public void readFile(FileRequest fileRequest, SimpMessageHeaderAccessor headerAccessor) {
        String userId = fileRequest.getUserId();

        // 세션 ID 가져오기
        String sessionId = headerAccessor.getSessionId();

        // 세션 ID와 사용자 ID 매핑 저장
        sessionUserMap.put(sessionId, userId);

        try {
            // 고정된 파일 존재 확인
            Path path = Paths.get(MONITORED_FILE_PATH);
            if (!Files.exists(path)) {
                throw new IOException("모니터링 파일이 존재하지 않습니다: " + MONITORED_FILE_PATH);
            }

            // 사용자에게 초기 파일 내용 전송
            List<String> lastLines = readLastLines(20, userId);
            messagingTemplate.convertAndSend("/topic/fileContent/" + userId, lastLines);

            // 사용자를 파일 구독자 목록에 추가
            fileSubscribers.add(userId);

            // WatchService 재시작
            if (fileSubscribers.size() == 1 && (watchServiceTask == null || watchServiceTask.isDone())) {
                logger.info("구독자가 추가되어 WatchService를 재시작합니다.");
                watchServiceStopFlag.set(false);
                watchServiceTask = watchExecutor.submit(() -> pollWatchService());
            }

            // 활동 시간 업데이트
            lastActivityTime = Instant.now();

            logger.info("사용자가 파일 구독을 시작했습니다. 파일: {}, 사용자: {}", MONITORED_FILE_PATH, userId);

        } catch (IOException e) {
            // 클라이언트에 오류 알림
            messagingTemplate.convertAndSend("/topic/error/" + userId, "파일 읽기 오류: " + e.getMessage());
            logger.error("파일 읽기 오류: {}", e.getMessage(), e);
        }
    }

    /**
     * WatchService 이벤트 폴링 메서드
     */
    private void pollWatchService() {
        try {
            logger.info("WatchService 폴링 시작: {}", monitoredDirectory);

            while (!watchServiceStopFlag.get() && !Thread.currentThread().isInterrupted()) {
                // 구독자가 없으면 풀링 중단
                if (sessionUserMap.isEmpty() || fileSubscribers.isEmpty()) {
                    logger.info("구독자가 없어 WatchService 폴링 중지");
                    break;
                }
                WatchKey key;
                try {
                    // 100ms 타임아웃으로 poll 사용하여 중지 플래그 주기적 확인
                    key = watchService.poll(100, TimeUnit.MILLISECONDS);
                    if (key == null)
                        continue; // 타임아웃
                } catch (ClosedWatchServiceException e) {
                    logger.info("WatchService가 닫혔습니다. 폴링 종료.");
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.info("WatchService 폴링 중단됨");
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    // 중지 플래그 확인
                    if (watchServiceStopFlag.get())
                        break;

                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    // 파일 이름 얻기
                    @SuppressWarnings("unchecked")
                    WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                    Path fileName = pathEvent.context();
                    Path fullPath = monitoredDirectory.resolve(fileName);

                    // 모니터링 중인 파일인지 확인
                    if (fullPath.toString().equals(MONITORED_FILE_PATH)) {
                        if (kind == StandardWatchEventKinds.ENTRY_MODIFY && fileMonitor != null) {
                            fileMonitor.processFileChange();
                            lastActivityTime = Instant.now();
                        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            // 파일이 삭제된 경우 모든 구독자에게 알림
                            for (String userId : fileSubscribers) {
                                messagingTemplate.convertAndSend("/topic/error/" + userId,
                                        "감시 중인 파일이 삭제되었습니다: " + MONITORED_FILE_PATH);
                            }
                        }
                    }
                }

                // 키 재설정 (다음 이벤트를 받을 준비)
                boolean valid = key.reset();
                if (!valid) {
                    // 디렉토리가 더 이상 액세스할 수 없는 경우
                    logger.warn("디렉토리가 더 이상 접근할 수 없습니다: {}", monitoredDirectory);
                    break;
                }
            }
        } catch (Exception e) {
            if (e instanceof ClosedWatchServiceException) {
                logger.info("WatchService 폴링 종료 (서비스 닫힘)");
            } else {
                logger.error("WatchService 폴링 중 오류 발생: {}", e.getMessage(), e);
            }
        } finally {
            logger.info("WatchService 폴링 스레드 종료");
        }
    }

    /**
     * WatchService 안전하게 종료
     */
    private void safelyCloseWatchService() {
        try {
            // 중지 플래그 설정
            watchServiceStopFlag.set(true);
            logger.info("WatchService 중지 플래그 설정");

            // WatchService 닫기
            if (watchService != null) {
                watchService.close();
                logger.info("WatchService 닫힘");
            }

            // 폴링 스레드 종료 확인
            if (watchServiceTask != null) {
                // 스레드가 종료되기까지 최대 2초 대기
                try {
                    watchServiceTask.get(2, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    // 타임아웃 발생 시 작업 취소
                    watchServiceTask.cancel(true);
                    logger.warn("WatchService 폴링 스레드 강제 종료");
                } catch (Exception e) {
                    logger.info("WatchService 폴링 스레드 종료 확인 중 예외: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            logger.error("WatchService 종료 중 오류: {}", e.getMessage(), e);
        }
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
            userServerMap.remove(userId);
            logger.info("세션 {}의 연결이 종료되었습니다. 사용자: {}", sessionId, userId);
            // 해당 사용자의 파일 구독 해제
            fileSubscribers.remove(userId);
            logger.info("사용자 {}의 연결이 종료되어 파일 구독을 해제했습니다.", userId);

            // WatchService 중지
            if (sessionUserMap.isEmpty() || fileSubscribers.isEmpty()) {
                sessionUserMap.clear();
                fileSubscribers.clear();
                logger.info("구독자가 없어 WatchService를 중지합니다.");
                watchServiceStopFlag.set(true);
            }
        }
    }

    private List<String> readLastLines(int lineCount, String userId) throws IOException {
        List<String> result = new ArrayList<>();
        Path path = Paths.get(MONITORED_FILE_PATH);

        if (!Files.exists(path)) {
            throw new IOException("파일이 존재하지 않습니다: " + MONITORED_FILE_PATH);
        }

        // NIO를 사용하여 파일 읽기 최적화
        try {
            List<String> allLines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int startIndex = Math.max(0, allLines.size() - lineCount);

            for (int i = startIndex; i < allLines.size(); i++) {
                String line = allLines.get(i);
                if (!line.isEmpty()) {
                    ConsoleLog log = new ConsoleLog(line);
                    if (userId.equals("itops01") && logNoFilterUseConsole(log)) {
                        result.add(line);
                    } else {
                        if (logFilterUseConsole(userId, "ITOPS", log)) {
                            result.add(line);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.error("파일 읽기 오류: {}", e.getMessage(), e);
            throw e;
        }

        return result;
    }

    // 일반 콘솔, 필터링 있음
    private boolean logFilterUseConsole(String userId, String coCode, ConsoleLog log) {
        if (log.isInValid()) {
            return false;
        }

        userServerMap.putIfAbsent(userId, new ArrayList<>());
        List<String> serverMap = userServerMap.get(userId);
        String serverName = log.getServerName();
        String messageCode = log.getMessageCode();
        String logCoCode = log.getCoCode();
        char messageCodeType = messageCode.charAt(0);

        if (messageCodeType == 'G')
            return false;

        if (messageCodeType == 'p' && messageCode.charAt(5) == '1')
            return false;

        if (messageCodeType == 'P'
                && (messageCode.substring(0, 4).equals("PAVI") || messageCode.substring(0, 4).equals("POSI")))
            return false;

        if (!logCoCode.equals(coCode))
            return false;

        if (serverMap.contains(serverName)) {
            return true;
        }

        return false;

    }

    // 일반 콘솔, 필터링 없음
    private boolean logNoFilterUseConsole(ConsoleLog log) {
        if (log.isInValid()) {
            return false;
        }

        String messageCode = log.getMessageCode();
        char messageCodeType = messageCode.charAt(0);

        if (messageCodeType == 'G')
            return false;

        if (messageCodeType == 'p' && messageCode.charAt(5) == '1')
            return false;

        if (messageCodeType == 'P'
                && (messageCode.substring(0, 4).equals("PAVI") || messageCode.substring(0, 4).equals("POSI")))
            return false;

        return true;
    }

    // ITSM 콘솔 모드

    // 파일 모니터링을 위한 내부 클래스 - 단일 모니터
    private class FileMonitor {
        private final String filePath;
        private final SimpMessagingTemplate messagingTemplate;
        private volatile boolean running = true;
        private long lastSize;
        private final Path path;

        public FileMonitor(String filePath, SimpMessagingTemplate messagingTemplate) {
            this.filePath = filePath;
            this.messagingTemplate = messagingTemplate;
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
         * 파일 변경 처리 - 모든 구독자에게 데이터 전송
         */
        public void processFileChange() {
            if (!running)
                return;

            try {
                if (!Files.exists(path)) {
                    logger.warn("파일이 존재하지 않습니다: {}", filePath);
                    // 모든 구독자에게 오류 메시지 전송
                    for (String userId : fileSubscribers) {
                        messagingTemplate.convertAndSend("/topic/error/" + userId,
                                "파일이 존재하지 않거나 접근할 수 없습니다: " + filePath);
                    }
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

                        // 모든 구독자에게 새 내용 전송 (사용자별 필터링 적용)
                        for (String userId : fileSubscribers) {
                            // 각 사용자별 필터링된 라인 목록
                            List<String> filteredLines = new ArrayList<>();

                            for (String line : newContent.split("\\r?\\n")) {
                                if (!line.isEmpty()) {
                                    ConsoleLog log = new ConsoleLog(line);
                                    if (log.isInValid()) {
                                        logger.warn("로그 라인 파싱 오류: {}", line);
                                    }
                                    if (userId.equals("itops01") && logNoFilterUseConsole(log)) {
                                        filteredLines.add(line);
                                    } else {
                                        if (logFilterUseConsole(userId, "ITOPS", log)) {
                                            filteredLines.add(line);
                                        }
                                    }

                                }
                            }

                            if (!filteredLines.isEmpty()) {
                                messagingTemplate.convertAndSend("/topic/fileUpdate/" + userId, filteredLines);
                            }
                        }
                    }

                    // 마지막 위치 업데이트
                    lastSize = currentSize;

                } else if (currentSize < lastSize) {
                    // 파일이 줄어든 경우 (다시 시작)
                    lastSize = currentSize;

                    // 모든 구독자에게 전체 파일 다시 읽어서 전송
                    for (String userId : fileSubscribers) {
                        List<String> allLines = readLastLines(20, userId);
                        messagingTemplate.convertAndSend("/topic/fileContent/" + userId, allLines);
                    }
                    logger.info("파일 크기가 감소하여 전체 내용을 다시 읽습니다: {}", filePath);
                }
                // 파일 크기 변화가 없으면 아무 작업도 하지 않음

            } catch (IOException e) {
                logger.error("파일 변경 처리 중 오류: {}", e.getMessage(), e);
                // 모든 구독자에게 오류 메시지 전송
                for (String userId : fileSubscribers) {
                    messagingTemplate.convertAndSend("/topic/error/" + userId,
                            "파일 모니터링 오류: " + e.getMessage());
                }
            }
        }

    }

    /**
     * 5초마다 시스템 상태 로깅
     */
    @Scheduled(fixedRate = 5000)
    public void logDataStatistics() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String currentTime = LocalDateTime.now().format(formatter);

        logger.info("======== 파일 웹소켓 데이터 통계 ({}) ========", currentTime);
        logger.info("모니터링 파일: {}", MONITORED_FILE_PATH);
        logger.info("구독자 수: {}", fileSubscribers.size());

        // 구독자 목록
        if (!fileSubscribers.isEmpty()) {
            logger.info("구독자 목록:");
            fileSubscribers
                    .forEach(userId -> logger.info("  - 사용자: {}", userId + " (" + userServerMap.get(userId) + ")"));
        }

        // 세션-사용자 매핑 통계
        logger.info("활성 세션 수: {}", sessionUserMap.size());

        // 마지막 활동 시간
        long minutesAgo = java.time.Duration.between(lastActivityTime, Instant.now()).toMinutes();
        String formattedTime = LocalDateTime.ofInstant(lastActivityTime, ZoneId.systemDefault())
                .format(formatter);
        logger.info("마지막 활동 시간: {} ({}분 전)", formattedTime, minutesAgo);

        // 모니터 상태
        if (fileMonitor != null) {
            logger.info("파일 모니터 상태: {}", fileMonitor.isRunning() ? "실행 중" : "중지됨");
        } else {
            logger.info("파일 모니터 상태: 없음");
        }

        // WatchService 상태
        if (watchServiceTask == null || watchServiceTask.isDone()) {
            logger.info("WatchService 상태: 중지됨");
        } else {
            logger.info("WatchService 상태: 실행 중");
        }
        if (watchServiceStopFlag.get()) {
            logger.info("WatchService 중지 플래그: 설정됨");
        } else {
            logger.info("WatchService 중지 플래그: 해제됨");
        }
        if (watchService != null) {
            logger.info("WatchService 등록된 디렉토리: {}", monitoredDirectory);
        } else {
            logger.info("WatchService 등록된 디렉토리: 없음");
        }

        logger.info("===============================================");
    }

}
