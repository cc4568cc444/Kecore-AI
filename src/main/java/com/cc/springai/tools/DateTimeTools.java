package com.cc.springai.tools;

import com.ethlo.time.DateTime;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class DateTimeTools {
    @Tool(name = "getDateTime", description = "查询当前的日期和时间")
    public String getDateTime(){
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formatTime = now.format(formatter);
        return formatTime;
    }
}
