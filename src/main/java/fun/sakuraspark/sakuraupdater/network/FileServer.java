package fun.sakuraspark.sakuraupdater.network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import fun.sakuraspark.sakuraupdater.config.DataConfig;
import fun.sakuraspark.sakuraupdater.config.DataConfig.Data;
import fun.sakuraspark.sakuraupdater.config.DataConfig.FileData;
import fun.sakuraspark.sakuraupdater.config.DataConfig.PathData;
import fun.sakuraspark.sakuraupdater.config.ServerConfig;
import fun.sakuraspark.sakuraupdater.config.StandaloneServerConfig;
import fun.sakuraspark.sakuraupdater.utils.ReleasePublisher;
import fun.sakuraspark.sakuraupdater.utils.ServerCommandsHelper;



public class FileServer {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileServer.class);

    /** 读取不到配置时的线程池大小 */
    private static final int DEFAULT_MAX_THREADS = 32;

    private final int port;
    private HttpServer httpServer;
    private boolean isRunning = false;

    // 存储所有可用文件信息
    private final Map<String, File> availableFiles = new HashMap<>();

    /** 发布目录（commit 时把源目录镜像到这里）；启动时解析一次。 */
    private volatile Path releaseRoot;

    public FileServer(int port) {
        this.port = port;
    }

    /**
     * 启动文件服务器
     */
    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(port), 0);
            
            // 创建不同的处理器
            httpServer.createContext("/heartbeat", new HeartBeatHandler());
            httpServer.createContext("/updateList", new UpdateListHandler());
            httpServer.createContext("/changelog", new ChangelogHandler());
            httpServer.createContext("/file", new FileDownloadHandler());
            //httpServer.createContext("/upload", new FileUploadHandler());
            
            // 每个进行中的下载都会占住一个线程，所以线程数至少要能覆盖
            // "客户端 download_connections × 同时在更新的玩家数"
            int maxThreads = getConfiguredMaxThreads();
            httpServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(maxThreads));
            
            httpServer.start();
            isRunning = true;
            LOGGER.info("File server started on port: {} (thread pool size: {})", port, maxThreads);

            // 发布目录：commit 会把手写的同步目录镜像到这里，文件服务器只从这里发文件。
            // 还没有发布目录时先尝试升级自动迁移（只有源目录和最新版本完全一致才会动手）；
            // 仍然没有的话保持旧行为——直接从同步目录发，并提示去 commit 一次。
            releaseRoot = ReleasePublisher.releaseRoot();
            ReleasePublisher.refreshPublishedState();
            if (!ReleasePublisher.isPublished()) {
                ServerCommandsHelper.autoMigrateSnapshot();
            }
            if (ReleasePublisher.isPublished()) {
                LOGGER.info("Published snapshot directory: {}", releaseRoot);
                warnAboutMissingPublishedFiles();
            } else {
                LOGGER.warn("No published snapshot found at {}. Serving files directly from the sync directories "
                        + "(legacy behaviour), so deleting or overwriting a synced file before committing can still "
                        + "leave clients stuck in an endless update. Run 'commit' (or 'data repair <version>') once to "
                        + "switch to snapshot publishing.", releaseRoot);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to start file server", e);
            shutdown();
        }
    }

    /**
     * 启动时体检：最新版本的名单里有没有"发布目录里根本没有"的文件。
     * 这正是会让客户端无限更新的状态；这里只告警，不自动改数据。
     */
    private void warnAboutMissingPublishedFiles() {
        Data lastData = DataConfig.getLastData();
        if (lastData == null || lastData.paths == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (PathData pathData : lastData.paths) {
            if (pathData.files == null) {
                continue;
            }
            for (FileData fileData : pathData.files) {
                try {
                    // 发布目录按 targetPath 摆放，体检也按 targetPath 对
                    if (!Files.isRegularFile(ReleasePublisher.resolveInRelease(releaseRoot, fileData.targetPath))) {
                        missing.add(fileData.targetPath);
                    }
                } catch (IOException e) {
                    missing.add(fileData.targetPath);
                }
            }
        }
        if (!missing.isEmpty()) {
            LOGGER.warn("Version {} lists {} file(s) that are missing from the published snapshot at {}. Clients that "
                    + "need them will keep failing to update. Run 'data repair {}' on the server to rebuild the list "
                    + "from the snapshot. Missing: {}", lastData.version, missing.size(), releaseRoot, lastData.version,
                    missing);
        }
    }

    /**
     * 读取配置文件里的线程池大小。独立模式和 mod 模式用各自的配置类，
     * 这里按运行模式选择，避免在独立模式下加载依赖 Forge 的 ServerConfig。
     */
    private static int getConfiguredMaxThreads() {
        try {
            if (StandaloneServerConfig.isStandalone()) {
                return StandaloneServerConfig.getMaxThreads();
            }
            return ServerConfig.getMaxThreads();
        } catch (Throwable t) {
            LOGGER.warn("Failed to read max_threads from config, falling back to {}", DEFAULT_MAX_THREADS, t);
            return DEFAULT_MAX_THREADS;
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 关闭文件服务器
     */
    public void shutdown() {
        isRunning = false;
        if (httpServer != null) {
            httpServer.stop(0);
        }
        LOGGER.info("File server stopped.");
    }


    /**
     * 心跳处理器
     */
    private class HeartBeatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                // acceptRanges 用来告诉新版客户端可以按 Range 分块下载；
                // 旧版客户端只看 HTTP 状态码，响应体改成 JSON 不影响它
                String response = "{\"status\":\"ok\",\"acceptRanges\":true}";
                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
                
                LOGGER.debug("Heartbeat received and responded.");
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    /**
     * 更新列表处理器
     */
    private class UpdateListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = readRequestBody(exchange);
                    JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();
                    String version = jsonRequest.has("version") ? jsonRequest.get("version").getAsString() : null; //获取版本号
                    
                    Data data = null;
                    if (version == null || version.isEmpty()) {
                        data = DataConfig.getLastData();
                    } else {
                        data = DataConfig.getDataByVersion(version);
                    }
                    
                    String response;
                    if (data == null) {
                        response = "{}";
                    } else {
                        response = new Gson().toJson(data);
                    }
                    
                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                    
                    LOGGER.debug("Sent update list for version: {}", version);
                } catch (Exception e) {
                    LOGGER.error("Error processing update list request", e);
                    sendError(exchange, 400, "Invalid request format");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    /**
     * 更新日志处理器：返回晚于客户端当前版本的所有版本记录（按时间升序）
     */
    private class ChangelogHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = readRequestBody(exchange);
                    JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();
                    String version = jsonRequest.has("version") ? jsonRequest.get("version").getAsString() : null;

                    List<Data> changelog = DataConfig.getDataAfter(version);

                    String response;
                    if (changelog == null) {
                        response = "[]";
                    } else {
                        response = new Gson().toJson(changelog);
                    }

                    byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);

                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }

                    LOGGER.debug("Sent changelog for version: {}", version);
                } catch (Exception e) {
                    LOGGER.error("Error processing changelog request", e);
                    sendError(exchange, 400, "Invalid request format");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    /** {@link #locate} 的结果：file 非 null 表示可以发送；否则 status/message 是该返回的错误。 */
    public static final class Located {
        public final File file;
        public final int status;
        public final String message;

        private Located(File file, int status, String message) {
            this.file = file;
            this.status = status;
            this.message = message;
        }
    }

    /**
     * 客户端请求 {@code requested}（名单里的 sourcePath）时该发哪个文件。
     * 先在最新版本的名单里查；发布模式下用名单项的 targetPath 去发布目录取（并防止路径逃逸），
     * 否则沿用旧行为直接从同步目录发。抽成静态方法是为了不用起 HTTP 服务和数据库就能测试。
     */
    public static Located locate(Data lastData, String requested, Path releaseRoot, boolean published) {
        FileData matched = null;
        if (lastData != null && lastData.paths != null) {
            for (PathData pathData : lastData.paths) {
                if (pathData.files == null) {
                    continue;
                }
                for (FileData fileData : pathData.files) {
                    if (fileData.sourcePath.equals(requested)) {
                        matched = fileData;
                        break;
                    }
                }
                if (matched != null) {
                    break;
                }
            }
        }
        if (matched == null) {
            return new Located(null, 403, "File not found in list");
        }
        File file;
        if (published) {
            // 名单的 md5 就是按发布副本记的，所以"名单里有"必然"发得出来"。
            // 客户端回传的是 sourcePath（源目录里的原始路径），发布目录按 targetPath 摆放，所以要换算
            try {
                file = ReleasePublisher.resolveInRelease(releaseRoot, matched.targetPath).toFile();
            } catch (IOException e) {
                return new Located(null, 403, "Invalid file path");
            }
        } else {
            file = new File(requested); // 还没有发布目录，沿用旧行为直接从同步目录发
        }
        if (!file.exists() || !file.isFile()) {
            return new Located(null, 404, "File not found in server");
        }
        return new Located(file, 200, null);
    }

    /**
     * 文件下载处理器
     */
    private class FileDownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = readRequestBody(exchange);
                    JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();
                    String fileName = jsonRequest.has("file") ? jsonRequest.get("file").getAsString() : null;
                    
                    if (fileName == null || fileName.isEmpty()) {
                        sendError(exchange, 400, "Missing file parameter");
                        return;
                    }
                    
                    // 名单校验 + 取文件路径（发布模式下按 targetPath 去发布目录取）
                    Located located = locate(DataConfig.getLastData(), fileName, releaseRoot,
                            ReleasePublisher.isPublished());
                    if (located.file == null) {
                        if (located.status == 404) {
                            LOGGER.warn("This file is in the list but not present in the server, please don't forget "
                                    + "commit: {}", fileName);
                        } else if (located.status == 403) {
                            LOGGER.debug("Rejected download request for {}: {}", fileName, located.message);
                        }
                        sendError(exchange, located.status, located.message);
                        return;
                    }
                    File file = located.file;

                    try {
                        long fileSize = file.length();

                        // 解析 Range：null = 忽略该头（语法不合法/多区间）按整文件返回，{-1,-1} = 不可满足
                        String rangeHeader = exchange.getRequestHeaders().getFirst("Range");
                        long[] range = null;
                        if (fileSize > 0 && rangeHeader != null) {
                            long[] parsed = parseRangeHeader(rangeHeader, fileSize);
                            if (parsed == null) {
                                LOGGER.debug("Ignoring unsupported Range header for {}: {}", fileName, rangeHeader);
                            } else if (parsed[0] < 0) {
                                exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileSize);
                                sendError(exchange, 416, "Requested Range Not Satisfiable");
                                return;
                            } else {
                                range = parsed;
                            }
                        }

                        long start = range == null ? 0 : range[0];
                        long end = range == null ? fileSize - 1 : range[1];
                        long contentLength = fileSize == 0 ? 0 : end - start + 1;

                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                        exchange.getResponseHeaders().set("Content-Length", String.valueOf(contentLength));
                        String encodedFileName = java.net.URLEncoder.encode(file.getName(), StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
                        if (range != null) {
                            exchange.getResponseHeaders().set("Content-Range",
                                    "bytes " + start + "-" + end + "/" + fileSize);
                        }
                        exchange.sendResponseHeaders(range == null ? 200 : 206, contentLength);

                        try (OutputStream os = exchange.getResponseBody();
                             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                            raf.seek(start);
                            byte[] buffer = new byte[8192];
                            long remaining = contentLength;
                            while (remaining > 0) {
                                int bytesRead = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                                if (bytesRead == -1) {
                                    break;
                                }
                                os.write(buffer, 0, bytesRead);
                                remaining -= bytesRead;
                            }
                            os.flush();
                        }

                        LOGGER.debug("Send file success: {} ({})", fileName,
                                range == null ? "full" : start + "-" + end + "/" + fileSize);
                    } catch (Exception e) {
                        LOGGER.error("Send file failed: {}", fileName, e);
                        if (!exchange.getResponseHeaders().containsKey("Content-Type")) {
                            sendError(exchange, 500, "Internal Server Error");
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Error processing file download request", e);
                    sendError(exchange, 400, "Invalid request format");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    /**
     * 文件上传处理器
     */
    private class FileUploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
                try {
                    String requestBody = readRequestBody(exchange);
                    JsonObject jsonRequest = JsonParser.parseString(requestBody).getAsJsonObject();
                    String fileName = jsonRequest.has("file") ? jsonRequest.get("file").getAsString() : null;
                    String fileContent = jsonRequest.has("content") ? jsonRequest.get("content").getAsString() : null;
                    
                    if (fileName == null || fileName.isEmpty()) {
                        sendError(exchange, 400, "Missing file parameter");
                        return;
                    }
                    
                    if (fileContent == null) {
                        sendError(exchange, 400, "Missing content parameter");
                        return;
                    }
                    
                    saveUploadedFileFromJson(fileName, fileContent);
                    
                    JsonObject response = new JsonObject();
                    response.addProperty("status", "success");
                    response.addProperty("message", "File uploaded successfully");
                    response.addProperty("file", fileName);
                    
                    String responseStr = new Gson().toJson(response);
                    byte[] responseBytes = responseStr.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
                    exchange.sendResponseHeaders(200, responseBytes.length);
                    
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(responseBytes);
                    }
                    
                    LOGGER.info("File uploaded successfully: {}", fileName);
                } catch (Exception e) {
                    LOGGER.error("Failed to upload file", e);
                    sendError(exchange, 500, "Failed to upload file");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }
    }

    /**
     * 解析单区间 Range 请求头（形如 bytes=start-end / bytes=start- / bytes=-suffix）。
     *
     * @return null 表示应当忽略该头并返回完整内容（格式不合法、多区间、或无法解析）；
     *         返回 {-1, -1} 表示区间格式合法但不可满足（应回 416）；
     *         否则返回闭区间 {start, end}，已按文件大小夹取。
     */
    private static long[] parseRangeHeader(String header, long fileSize) {
        String value = header.trim();
        if (!value.startsWith("bytes=")) {
            return null;
        }
        value = value.substring("bytes=".length()).trim();
        // 多区间（bytes=0-99,200-299）不支持，按整文件返回
        if (value.isEmpty() || value.indexOf(',') >= 0) {
            return null;
        }
        int dash = value.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startStr = value.substring(0, dash).trim();
        String endStr = value.substring(dash + 1).trim();
        try {
            if (startStr.isEmpty()) {
                // bytes=-N：最后 N 个字节
                if (endStr.isEmpty()) {
                    return null;
                }
                long suffix = Long.parseLong(endStr);
                if (suffix <= 0) {
                    return new long[] { -1, -1 };
                }
                return new long[] { Math.max(0, fileSize - suffix), fileSize - 1 };
            }
            long start = Long.parseLong(startStr);
            long end = endStr.isEmpty() ? fileSize - 1 : Long.parseLong(endStr);
            if (start < 0 || start >= fileSize) {
                return new long[] { -1, -1 };
            }
            if (end >= fileSize) {
                end = fileSize - 1;
            }
            if (end < start) {
                return new long[] { -1, -1 };
            }
            return new long[] { start, end };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 发送错误响应
     */
    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String response = message;
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=utf-8");
        exchange.sendResponseHeaders(code, responseBytes.length);
        
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    /**
     * 保存上传的文件（从JSON base64内容）
     */
    private void saveUploadedFileFromJson(String fileName, String fileContent) {
        try {
            File file = new File(fileName);
            // 确保父目录存在
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            
            // 解析 Base64 编码的内容
            byte[] decodedContent = java.util.Base64.getDecoder().decode(fileContent);
            
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(decodedContent);
            }
            
            // 更新可用文件列表
            availableFiles.put(fileName, file);
            LOGGER.debug("已保存上传的文件: {}", fileName);
        } catch (Exception e) {
            LOGGER.error("保存上传文件失败: {}", fileName, e);
        }
    }

    /**
     * 读取请求体
     */
    private String readRequestBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }
}
