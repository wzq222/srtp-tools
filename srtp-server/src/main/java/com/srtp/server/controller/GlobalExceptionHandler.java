package com.srtp.server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 静态资源未找到（如 favicon.ico）返回 404，而不是 500。
     * 这避免了浏览器控制台将缺失资源显示为服务器错误。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public Map<String, Object> handleNoResource(NoResourceFoundException e) {
        log.debug("Static resource not found: {}", e.getResourcePath());
        return Map.of("ok", false, "error", "资源未找到", "path", e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public Map<String, Object> handleAll(Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String stack = sw.toString();
        log.error("Server error: {}", stack);
        return Map.of("ok", false, "error", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                "detail", stack.substring(0, Math.min(500, stack.length())));
    }
}
