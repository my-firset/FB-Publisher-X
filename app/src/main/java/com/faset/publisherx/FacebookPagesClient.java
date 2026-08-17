package com.faset.publisherx;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Small, dependency-free client for Meta's official Facebook Pages API.
 * It deliberately requires a Page access token and a server-confirmed post ID.
 */
public final class FacebookPagesClient {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 25000;

    private FacebookPagesClient() {}

    public static final class PageResult {
        public final boolean success;
        public final String name;
        public final String message;

        private PageResult(boolean success, String name, String message) {
            this.success = success;
            this.name = name == null ? "" : name;
            this.message = message == null ? "" : message;
        }
    }

    public static final class PublishResult {
        public final boolean success;
        public final String postId;
        public final String message;

        private PublishResult(boolean success, String postId, String message) {
            this.success = success;
            this.postId = postId == null ? "" : postId;
            this.message = message == null ? "" : message;
        }
    }

    public static PageResult verifyPage(String pageId, String pageToken, String graphVersion) {
        try {
            validateCredentials(pageId, pageToken);
            String path = "/" + safePageId(pageId) + "?fields=id,name&access_token=" + encode(pageToken);
            Response response = request("GET", graphUrl(graphVersion, path), null);
            JSONObject json = new JSONObject(response.body);
            if (response.code >= 200 && response.code < 300 && json.optString("id", "").length() > 0) {
                return new PageResult(true, json.optString("name", pageId), "تم التحقق من الصفحة بنجاح");
            }
            return new PageResult(false, "", graphError(json, response.code));
        } catch (Exception e) {
            return new PageResult(false, "", readableException(e));
        }
    }

    public static PublishResult publishTextPost(String pageId, String pageToken, String message, String graphVersion) {
        try {
            validateCredentials(pageId, pageToken);
            if (message == null || message.trim().isEmpty()) {
                return new PublishResult(false, "", "نص المنشور فارغ");
            }
            String form = "message=" + encode(message)
                    + "&published=true&access_token=" + encode(pageToken);
            Response response = request("POST", graphUrl(graphVersion, "/" + safePageId(pageId) + "/feed"), form);
            JSONObject json = new JSONObject(response.body);
            String postId = json.optString("id", "");
            if (response.code >= 200 && response.code < 300 && !postId.isEmpty()) {
                return new PublishResult(true, postId, "تم إنشاء المنشور في Meta");
            }
            return new PublishResult(false, "", graphError(json, response.code));
        } catch (Exception e) {
            return new PublishResult(false, "", readableException(e));
        }
    }

    private static Response request(String method, String urlString, String form) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if (form != null) {
            byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = readBody(stream);
        connection.disconnect();
        return new Response(code, body);
    }

    private static String readBody(InputStream stream) throws IOException {
        if (stream == null) return "{}";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.length() == 0 ? "{}" : out.toString();
    }

    private static String graphUrl(String version, String path) {
        String safeVersion = version == null || version.trim().isEmpty() ? "v26.0" : version.trim();
        return "https://graph.facebook.com/" + safeVersion + path;
    }

    private static String safePageId(String pageId) {
        if (pageId == null || !pageId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("Page ID غير صالح");
        }
        return pageId;
    }

    private static void validateCredentials(String pageId, String token) {
        safePageId(pageId);
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("Page access token مفقود");
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String graphError(JSONObject json, int statusCode) {
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String message = error.optString("message", "Meta رفض الطلب");
            int code = error.optInt("code", 0);
            return code > 0 ? message + " [Meta code " + code + "]" : message;
        }
        return "Meta أعاد HTTP " + statusCode;
    }

    private static String readableException(Exception exception) {
        if (exception instanceof java.net.SocketTimeoutException) return "انتهت مهلة الاتصال بـ Meta";
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "تعذر الاتصال بـ Meta" : message;
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }
}
