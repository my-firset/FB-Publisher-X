package com.faset.publisherx;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dependency-free client for Meta's official Facebook Pages API.
 * It requires a Page access token and only reports success after Meta returns
 * an object ID for the created Page post/media.
 */
public final class FacebookPagesClient {
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MEDIA_READ_TIMEOUT_MS = 180000;

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
                return failure("نص المنشور فارغ");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("message", message.trim());
            fields.put("published", "true");
            fields.put("access_token", pageToken);
            Response response = formRequest("POST", graphUrl(graphVersion, "/" + safePageId(pageId) + "/feed"), fields);
            return parsePostResult(response, "تم إنشاء المنشور النصي في Meta");
        } catch (Exception e) {
            return failure(readableException(e));
        }
    }

    /**
     * Publishes text, one or more images, or one video to a Page.
     * Mixed image/video batches are rejected because Meta exposes separate
     * official publishing flows for them.
     */
    public static PublishResult publishPost(
            String pageId,
            String pageToken,
            String message,
            List<Uri> mediaUris,
            ContentResolver resolver,
            String graphVersion) {
        try {
            validateCredentials(pageId, pageToken);
            if (mediaUris == null || mediaUris.isEmpty()) {
                return publishTextPost(pageId, pageToken, message, graphVersion);
            }
            if (resolver == null) return failure("تعذر الوصول إلى ملفات الوسائط");

            boolean hasImage = false;
            boolean hasVideo = false;
            for (Uri uri : mediaUris) {
                String mime = resolver.getType(uri);
                if (mime != null && mime.toLowerCase().startsWith("video/")) {
                    hasVideo = true;
                } else if (mime != null && mime.toLowerCase().startsWith("image/")) {
                    hasImage = true;
                } else {
                    return failure("نوع ملف غير مدعوم: " + fileName(uri, resolver));
                }
            }
            if (hasImage && hasVideo) {
                return failure("اختر صوراً فقط أو فيديو واحداً، ولا تخلط النوعين في منشور واحد");
            }
            if (hasVideo) {
                if (mediaUris.size() != 1) return failure("يمكن نشر فيديو واحد في كل حملة");
                return publishVideo(pageId, pageToken, message, mediaUris.get(0), resolver, graphVersion);
            }
            return publishImages(pageId, pageToken, message, mediaUris, resolver, graphVersion);
        } catch (Exception e) {
            return failure(readableException(e));
        }
    }

    private static PublishResult publishImages(
            String pageId,
            String pageToken,
            String message,
            List<Uri> mediaUris,
            ContentResolver resolver,
            String graphVersion) {
        try {
            if (mediaUris.size() == 1) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("access_token", pageToken);
                fields.put("published", "true");
                if (message != null && !message.trim().isEmpty()) fields.put("caption", message.trim());
                Response response = uploadMultipart(
                        graphUrl(graphVersion, "/" + safePageId(pageId) + "/photos"),
                        fields, "source", mediaUris.get(0), resolver);
                JSONObject json = new JSONObject(response.body);
                String postId = json.optString("post_id", "");
                if (response.code >= 200 && response.code < 300 && !postId.isEmpty()) {
                    return new PublishResult(true, postId, "تم نشر الصورة مع النص عبر Meta");
                }
                return failure(graphError(json, response.code));
            }

            java.util.ArrayList<String> photoIds = new java.util.ArrayList<>();
            for (Uri uri : mediaUris) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("access_token", pageToken);
                fields.put("published", "false");
                fields.put("temporary", "true");
                Response response = uploadMultipart(
                        graphUrl(graphVersion, "/" + safePageId(pageId) + "/photos"),
                        fields, "source", uri, resolver);
                JSONObject json = new JSONObject(response.body);
                String photoId = json.optString("id", "");
                if (response.code < 200 || response.code >= 300 || photoId.isEmpty()) {
                    return failure("تعذر رفع الصورة " + fileName(uri, resolver) + ": " + graphError(json, response.code));
                }
                photoIds.add(photoId);
            }

            Map<String, String> feed = new LinkedHashMap<>();
            feed.put("access_token", pageToken);
            feed.put("published", "true");
            if (message != null && !message.trim().isEmpty()) feed.put("message", message.trim());
            for (int i = 0; i < photoIds.size(); i++) {
                feed.put("attached_media[" + i + "]", "{\"media_fbid\":\"" + jsonEscape(photoIds.get(i)) + "\"}");
            }
            Response response = formRequest("POST", graphUrl(graphVersion, "/" + safePageId(pageId) + "/feed"), feed);
            return parsePostResult(response, "تم نشر الصور مع النص عبر Meta");
        } catch (Exception e) {
            return failure(readableException(e));
        }
    }

    private static PublishResult publishVideo(
            String pageId,
            String pageToken,
            String message,
            Uri videoUri,
            ContentResolver resolver,
            String graphVersion) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("access_token", pageToken);
            fields.put("published", "true");
            if (message != null && !message.trim().isEmpty()) fields.put("description", message.trim());
            fields.put("title", fileName(videoUri, resolver));
            Response response = uploadMultipart(
                    videoGraphUrl(graphVersion, "/" + safePageId(pageId) + "/videos"),
                    fields, "source", videoUri, resolver);
            JSONObject json = new JSONObject(response.body);
            String videoId = json.optString("id", "");
            if (response.code >= 200 && response.code < 300 && !videoId.isEmpty()) {
                return new PublishResult(true, videoId, "تم رفع ونشر الفيديو عبر Meta");
            }
            return failure(graphError(json, response.code));
        } catch (Exception e) {
            return failure(readableException(e));
        }
    }

    private static Response uploadMultipart(
            String urlString,
            Map<String, String> fields,
            String fileField,
            Uri uri,
            ContentResolver resolver) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(MEDIA_READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        String boundary = "----PublisherX" + UUID.randomUUID().toString().replace("-", "");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setChunkedStreamingMode(16 * 1024);

        String mime = resolver.getType(uri);
        if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
        String name = fileName(uri, resolver);
        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = connection.getOutputStream()) {
            if (input == null) throw new IOException("تعذر فتح ملف الوسائط");
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                writeAscii(output, "--" + boundary + "\r\n");
                writeAscii(output, "Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n");
                writeUtf8(output, entry.getValue());
                writeAscii(output, "\r\n");
            }
            writeAscii(output, "--" + boundary + "\r\n");
            writeAscii(output, "Content-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + safeFileName(name) + "\"\r\n");
            writeAscii(output, "Content-Type: " + mime + "\r\n\r\n");
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            writeAscii(output, "\r\n--" + boundary + "--\r\n");
            output.flush();
        }
        return readResponse(connection);
    }

    private static Response formRequest(String method, String urlString, Map<String, String> fields) throws IOException {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (form.length() > 0) form.append('&');
            form.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return request(method, urlString, form.toString());
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
        return readResponse(connection);
    }

    private static Response readResponse(HttpURLConnection connection) throws IOException {
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

    private static PublishResult parsePostResult(Response response, String successMessage) {
        try {
            JSONObject json = new JSONObject(response.body);
            String postId = json.optString("id", "");
            if (response.code >= 200 && response.code < 300 && !postId.isEmpty()) {
                return new PublishResult(true, postId, successMessage);
            }
            return failure(graphError(json, response.code));
        } catch (Exception e) {
            return failure(readableException(e));
        }
    }

    private static PublishResult failure(String message) {
        return new PublishResult(false, "", message);
    }

    private static String graphUrl(String version, String path) {
        String safeVersion = version == null || version.trim().isEmpty() ? "v26.0" : version.trim();
        return "https://graph.facebook.com/" + safeVersion + path;
    }

    private static String videoGraphUrl(String version, String path) {
        String safeVersion = version == null || version.trim().isEmpty() ? "v26.0" : version.trim();
        return "https://graph-video.facebook.com/" + safeVersion + path;
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
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
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
        if (exception instanceof SocketTimeoutException) return "انتهت مهلة رفع الوسائط إلى Meta";
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? "تعذر الاتصال بـ Meta" : message;
    }

    private static String fileName(Uri uri, ContentResolver resolver) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String value = cursor.getString(index);
                    if (value != null && !value.trim().isEmpty()) return value.trim();
                }
            }
        } catch (Exception ignored) {}
        String segment = uri == null ? "media" : uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "media" : segment;
    }

    private static String safeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "media";
        return name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
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
