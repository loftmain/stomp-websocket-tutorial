package com.example.messagingstompwebsocket;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConsoleLog {
    private String messageCode;
    private String coCode;
    private String date;
    private String serverName;
    private String message;

    public ConsoleLog(String log) {
        // 정규 표현식으로 문자열 파싱
        String regex = "^(.+?) (.+?) (.+?) (.+?) (.+?) (.+?) (.+)$";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(log);

        if (matcher.find()) {
            this.messageCode = matcher.group(1);
            this.coCode = matcher.group(2);
            this.date = matcher.group(3) + " " + matcher.group(4) + " " + matcher.group(5);
            this.serverName = matcher.group(6);
            this.message = matcher.group(7);
        } else {
            this.messageCode = "N/A";
            this.coCode = "N/A";
            this.date = "N/A";
            this.serverName = "N/A";
            this.message = "N/A";
        }
    }

    public boolean isValid() {
        return this.messageCode != "N/A" && this.coCode != "N/A" && this.date != "N/A" && this.serverName != "N/A"
                && this.message != "N/A";
    }

    public boolean isInValid() {
        return !isValid();
    }

    // Getters
    public String getMessageCode() {
        return messageCode;
    }

    public String getCoCode() {
        return coCode;
    }

    public String getDate() {
        return date;
    }

    public String getServerName() {
        return serverName;
    }

    public String getMessage() {
        return message;
    }

    // Setters
    public void setMessageCode(String messageCode) {
        this.messageCode = messageCode;
    }

    public void setCoCode(String coCode) {
        this.coCode = coCode;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
