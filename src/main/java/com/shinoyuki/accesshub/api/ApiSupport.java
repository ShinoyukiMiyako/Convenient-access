package com.shinoyuki.accesshub.api;

import java.io.BufferedReader;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 控制器共用的请求/响应样板。
 *
 * 既有的几个控制器各自复制了一份读体、写 JSON、取客户端 IP 的私有方法, 本类是新增控制器的落点,
 * 以免继续复制。旧控制器未一并迁移: 那属于与本次功能无关的改动面, 留待单独的清理提交。
 */
public final class ApiSupport {

    private static final Gson GSON = new Gson();
    private static final Gson GSON_WITH_NULLS = new GsonBuilder().serializeNulls().create();

    private ApiSupport() {
    }

    public static String readRequestBody(HttpServletRequest request) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        return body.toString();
    }

    /** 写出统一包装的 JSON 响应, 并把 ApiResponse.code 对齐到 HTTP 状态码。 */
    public static void sendJson(HttpServletResponse response, int status, ApiResponse<?> body) throws IOException {
        sendJson(response, status, body, GSON);
    }

    /** Writes pack wire DTOs whose nullable fields are part of the explicit frontend contract. */
    public static void sendJsonWithNulls(HttpServletResponse response, int status, ApiResponse<?> body)
            throws IOException {
        sendJson(response, status, body, GSON_WITH_NULLS);
    }

    private static void sendJson(HttpServletResponse response, int status, ApiResponse<?> body, Gson gson)
            throws IOException {
        response.setStatus(status);
        if (body != null) {
            body.setCode(status);
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(gson.toJson(body));
        response.getWriter().flush();
    }

    public static String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }
}
