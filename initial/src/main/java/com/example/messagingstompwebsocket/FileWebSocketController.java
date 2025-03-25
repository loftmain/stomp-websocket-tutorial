package com.example.messagingstompwebsocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Controller
public class FileWebSocketController {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, FileMonitor> activeMonitors = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

    @MessageMapping("/readFile")
    @SendTo("/topic/fileContent")
    public List<String> readFile(FileRequest fileRequest) throws IOException {
        String filePath = fileRequest.getFilePath();
        List<String> lastLines = readLastLines(filePath, 20);

        // 기존 모니터가 있으면 중지
        if (activeMonitors.containsKey(filePath)) {
            activeMonitors.get(filePath).stop();
            activeMonitors.remove(filePath);
        }

        // 새 모니터 시작
        FileMonitor monitor = new FileMonitor(filePath, messagingTemplate);
        activeMonitors.put(filePath, monitor);
        scheduler.scheduleWithFixedDelay(monitor, 1, 1, TimeUnit.SECONDS);

        return lastLines;
    }

    private List<String> readLastLines(String filePath, int lineCount) throws IOException {
        List<String> result = new ArrayList<>();
        File file = new File(filePath);

        if (!file.exists()) {
            throw new IOException("파일이 존재하지 않습니다: " + filePath);
        }

        List<String> allLines = Files.readAllLines(Paths.get(filePath));
        int startIndex = Math.max(0, allLines.size() - lineCount);

        for (int i = startIndex; i < allLines.size(); i++) {
            result.add(allLines.get(i));
        }

        return result;
    }

    // 파일 모니터링을 위한 내부 클래스
    private static class FileMonitor implements Runnable {
        private final String filePath;
        private final SimpMessagingTemplate messagingTemplate;
        private long lastPosition;
        private boolean running = true;

        public FileMonitor(String filePath, SimpMessagingTemplate messagingTemplate) {
            this.filePath = filePath;
            this.messagingTemplate = messagingTemplate;
            File file = new File(filePath);
            this.lastPosition = file.length();
        }

        public void stop() {
            running = false;
        }

        @Override
        public void run() {
            if (!running)
                return;

            try {
                File file = new File(filePath);
                long length = file.length();

                if (length > lastPosition) {
                    // 새로운 내용이 추가됨
                    List<String> newLines = new ArrayList<>();
                    RandomAccessFile raf = new RandomAccessFile(file, "r");
                    raf.seek(lastPosition);

                    String line;
                    while ((line = raf.readLine()) != null) {
                        // 바이트를 문자열로 변환 (인코딩 고려)
                        line = new String(line.getBytes("ISO-8859-1"), "UTF-8");
                        newLines.add(line);
                    }

                    lastPosition = raf.getFilePointer();
                    raf.close();

                    if (!newLines.isEmpty()) {
                        messagingTemplate.convertAndSend("/topic/fileUpdate", newLines);
                    }
                } else if (length < lastPosition) {
                    // 파일이 줄어든 경우 (다시 시작)
                    lastPosition = length;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
