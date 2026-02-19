package com.tendoarisu.mmdskin.sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.opdent.mmdskin.sync.MMDSyncMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class EmbeddedServer {
    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static final Map<Path, CacheEntry> MD5_CACHE = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final String md5;
        final long lastModified;

        CacheEntry(String md5, long lastModified) {
            this.md5 = md5;
            this.lastModified = lastModified;
        }
    }

    public static void start() {
        if (!Config.ENABLE_SERVER) return;

        // 异步初始化并启动服务器，避免阻塞游戏主线程（尤其是在加载大缓存时）
        CompletableFuture.runAsync(() -> {
            loadCache();

            try {
                int port = Config.SERVER_PORT;
                server = HttpServer.create(new InetSocketAddress(port), 0);
                
                // 路由配置
                server.createContext("/", new IndexHandler());
                server.createContext("/api/sync", new SyncHandler());
                server.createContext("/download/", new DownloadHandler());
                server.createContext("/upload", new UploadHandler());

                // 创建专门的线程池处理 HTTP 请求，防止单个请求阻塞
                serverExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                server.setExecutor(serverExecutor);
                
                server.start();
                MMDSyncMod.LOGGER.info("MMDSync 内置服务器已启动（异步），端口: {}", port);
            } catch (IOException e) {
                MMDSyncMod.LOGGER.error("启动内置服务器失败", e);
            }
        });
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdown();
            serverExecutor = null;
        }
        saveCache();
    }

    // 静态页面处理器
    static class IndexHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            InputStream is = MMDSyncMod.class.getResourceAsStream("/assets/mmdsync/web/index.html");
            if (is == null) {
                String error = "Index file not found in resources";
                exchange.sendResponseHeaders(404, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
                return;
            }
            byte[] response = is.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            OutputStream os = exchange.getResponseBody();
            os.write(response);
            os.close();
        }
    }

    // 资源列表处理器
    static class SyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Path gameDir = FMLPaths.GAMEDIR.get();
            JsonObject response = new JsonObject();
            response.add("pmx", scanFolders(gameDir.resolve("3d-skin/EntityPlayer")));
            response.add("vmd", scanFolders(gameDir.resolve("3d-skin/StageAnim")));

            byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private JsonArray scanFolders(Path dir) {
            JsonArray array = new JsonArray();
            if (!Files.exists(dir)) return array;
            
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isDirectory).forEach(folder -> {
                    JsonObject obj = new JsonObject();
                    String folderName = folder.getFileName().toString();
                    obj.addProperty("name", folderName);
                    // 计算文件夹内所有文件的综合 MD5
                    obj.addProperty("md5", getFolderMD5(folder));
                    array.add(obj);
                });
            } catch (IOException e) {
                MMDSyncMod.LOGGER.error("扫描文件夹失败: {}", dir, e);
            }
            return array;
        }

        private String getFolderMD5(Path folder) {
            try (Stream<Path> stream = Files.walk(folder)) {
                StringBuilder combined = new StringBuilder();
                stream.filter(Files::isRegularFile)
                      .sorted()
                      .forEach(p -> combined.append(getCachedMD5(p)));
                
                MessageDigest digest = MessageDigest.getInstance("MD5");
                byte[] hash = digest.digest(combined.toString().getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }

    // 下载处理器
    static class DownloadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = URLDecoder.decode(exchange.getRequestURI().getPath(), StandardCharsets.UTF_8);
            // 路径格式: /download/pmx/folder_name 或 /download/vmd/folder_name
            String[] parts = path.split("/", 4);
            if (parts.length < 4) {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }

            String zone = parts[2];
            String folderName = parts[3];

            Path baseDir = FMLPaths.GAMEDIR.get().resolve(zone.equals("pmx") ? "3d-skin/EntityPlayer" : "3d-skin/StageAnim");
            Path targetFolder = baseDir.resolve(folderName);

            if (Files.exists(targetFolder) && Files.isDirectory(targetFolder)) {
                // 将文件夹打包为 ZIP
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
                    zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION);
                    Files.walk(targetFolder).forEach(p -> {
                        if (Files.isRegularFile(p)) {
                            String rel = targetFolder.relativize(p).toString().replace(File.separatorChar, '/');
                            try {
                                zos.putNextEntry(new java.util.zip.ZipEntry(rel));
                                Files.copy(p, zos);
                                zos.closeEntry();
                            } catch (IOException ignored) {}
                        }
                    });
                }
                
                byte[] zipBytes = bos.toByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.sendResponseHeaders(200, zipBytes.length);
                exchange.getResponseBody().write(zipBytes);
            } else {
                exchange.sendResponseHeaders(404, 0);
            }
            exchange.close();
        }
    }

    // 上传处理器 (支持 ZIP 并自动处理嵌套文件夹)
    static class UploadHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, 0);
                exchange.close();
                return;
            }

            // 获取上传类型和文件名（通过 Query 参数或 Header）
            Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());
            String zone = params.getOrDefault("zone", "pmx"); // pmx 或 vmd
            String originalName = params.getOrDefault("name", "upload.zip");

            // 基础目录：3d-skin/EntityPlayer/ 或 3d-skin/StageAnim/
            // 注意：MMDSkin 只支持一级子目录，所以日期将作为文件夹名的前缀
            String datePrefix = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
            Path baseDir = FMLPaths.GAMEDIR.get().resolve(zone.equals("pmx") ? "3d-skin/EntityPlayer" : "3d-skin/StageAnim");
            Files.createDirectories(baseDir);

            try (InputStream is = exchange.getRequestBody()) {
                if (originalName.toLowerCase().endsWith(".zip")) {
                    processZipUpload(is, baseDir, datePrefix, originalName);
                } else {
                    // 处理单文件或目录上传
                    Path targetFile;
                    String normalizedName = originalName.replace('\\', '/');
                    
                    if (normalizedName.contains("/")) {
                        // 如果是目录上传 (由 webkitRelativePath 传入)
                        // 格式通常是: TopDir/SubDir/file.ext
                        int firstSlash = normalizedName.indexOf('/');
                        String topDir = normalizedName.substring(0, firstSlash);
                        String remaining = normalizedName.substring(firstSlash + 1);
                        
                        // 使用 "日期_顶级目录" 作为模型文件夹
                        String folderName = datePrefix + "_" + topDir;
                        targetFile = baseDir.resolve(folderName).resolve(remaining.replace('/', File.separatorChar));
                    } else {
                        // 真正的单文件上传 (没有文件夹结构)
                        // 必须包裹在文件夹中，否则 MMDSkin 无法识别
                        String nameWithoutExt = originalName;
                        int lastDot = originalName.lastIndexOf('.');
                        if (lastDot != -1) {
                            nameWithoutExt = originalName.substring(0, lastDot);
                        }
                        
                        String folderName = datePrefix + "_" + nameWithoutExt;
                        Path targetFolder = baseDir.resolve(folderName);
                        
                        // 如果文件夹已存在，则尝试添加短随机后缀（仅对单文件包裹有效，目录上传不加后缀以保持一致性）
                        if (Files.exists(targetFolder)) {
                            String suffix = Integer.toHexString(new java.util.Random().nextInt(0x10000));
                            targetFolder = baseDir.resolve(folderName + "_" + suffix);
                        }
                        
                        targetFile = targetFolder.resolve(originalName);
                    }
                    
                    // 核心修复：确保父目录存在
                    Files.createDirectories(targetFile.getParent());
                    Files.copy(is, targetFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                MMDSyncMod.LOGGER.error("处理上传文件失败", e);
                String error = "Upload failed: " + e.getMessage();
                exchange.sendResponseHeaders(500, error.length());
                exchange.getResponseBody().write(error.getBytes());
                exchange.close();
                return;
            }

            String response = "Upload successful";
            exchange.sendResponseHeaders(200, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        }

        private void processZipUpload(InputStream is, Path targetBaseDir, String datePrefix, String originalName) throws IOException {
            // 先保存到临时文件以便遍历分析
            Path tempZip = Files.createTempFile("mmdsync_", ".zip");
            Files.copy(is, tempZip, StandardCopyOption.REPLACE_EXISTING);

            try {
                // 1. 获取所有有效的 ZipEntry 路径
                List<String> entryNames = new ArrayList<>();
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        entryNames.add(entry.getName());
                    }
                }

                // 2. 递归寻找资源根目录
                String commonPrefix = "";
                while (true) {
                    String currentPrefix = commonPrefix;
                    String firstTopDir = null;
                    boolean allMatch = true;
                    boolean hasFileInCurrentLevel = false;

                    for (String name : entryNames) {
                        if (!name.startsWith(currentPrefix)) continue;
                        String relative = name.substring(currentPrefix.length());
                        if (relative.isEmpty()) continue;

                        int slashIndex = relative.indexOf('/');
                        if (slashIndex == -1) {
                            hasFileInCurrentLevel = true;
                        } else {
                            String topDir = relative.substring(0, slashIndex + 1);
                            if (firstTopDir == null) {
                                firstTopDir = topDir;
                            } else if (!firstTopDir.equals(topDir)) {
                                allMatch = false;
                            }
                        }
                    }

                    if (!hasFileInCurrentLevel && firstTopDir != null && allMatch) {
                        commonPrefix += firstTopDir;
                    } else {
                        break;
                    }
                }

                // 3. 决定是否需要额外的包裹层
                // 如果剥离后的根层级有多个项，或者只有一个项且是文件，则需要用 ZIP 名包裹
                boolean needsExtraWrap = false;
                String firstItem = null;
                int itemCount = 0;
                for (String name : entryNames) {
                    if (!name.startsWith(commonPrefix)) continue;
                    String relative = name.substring(commonPrefix.length());
                    if (relative.isEmpty()) continue;
                    
                    int slashIndex = relative.indexOf('/');
                    String item = (slashIndex == -1) ? relative : relative.substring(0, slashIndex);
                    if (firstItem == null) {
                        firstItem = item;
                        itemCount = 1;
                    } else if (!firstItem.equals(item)) {
                        itemCount++;
                        break;
                    }
                }
                
                // 如果有多个顶级项，或者顶级项是一个文件（没有斜杠），则需要包裹
                final String finalCommonPrefix = commonPrefix;
                final String finalFirstItem = firstItem;
                if (itemCount > 1 || (finalFirstItem != null && !entryNames.stream().anyMatch(n -> n.equals(finalCommonPrefix + finalFirstItem + "/")))) {
                    needsExtraWrap = true;
                }

                final String wrapNameBase = originalName.toLowerCase().endsWith(".zip") 
                        ? originalName.substring(0, originalName.length() - 4) 
                        : originalName;

                // 4. 执行解压
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(tempZip))) {
                    ZipEntry entry;
                    Map<String, String> dirRenames = new java.util.HashMap<>();
                    
                    while ((entry = zis.getNextEntry()) != null) {
                        String name = entry.getName();
                        if (!name.startsWith(finalCommonPrefix)) continue;
                        
                        String relativePath = name.substring(finalCommonPrefix.length());
                        if (relativePath.isEmpty()) continue;

                        String finalPath;
                        if (needsExtraWrap) {
                            // 使用 wrapName 作为顶级目录
                            String topName = dirRenames.computeIfAbsent("_wrap_", k -> {
                                String target = datePrefix + "_" + wrapNameBase;
                                while (Files.exists(targetBaseDir.resolve(target))) {
                                    String suffix = Integer.toHexString(new java.util.Random().nextInt(0x10000));
                                    target = datePrefix + "_" + wrapNameBase + "_" + suffix;
                                }
                                return target;
                            });
                            finalPath = topName + "/" + relativePath;
                        } else {
                            // 原有的逻辑：处理顶级文件夹冲突
                            int firstSlash = relativePath.indexOf('/');
                            String topName = (firstSlash == -1) ? relativePath : relativePath.substring(0, firstSlash);
                            String remaining = (firstSlash == -1) ? "" : relativePath.substring(firstSlash);
                            
                            String newTopName = dirRenames.computeIfAbsent(topName, k -> {
                                String target = datePrefix + "_" + k;
                                while (Files.exists(targetBaseDir.resolve(target))) {
                                    String suffix = Integer.toHexString(new java.util.Random().nextInt(0x10000));
                                    target = datePrefix + "_" + k + "_" + suffix;
                                }
                                return target;
                            });
                            finalPath = newTopName + remaining;
                        }

                        Path targetFile = targetBaseDir.resolve(finalPath.replace('/', File.separatorChar));
                        if (entry.isDirectory()) {
                            Files.createDirectories(targetFile);
                        } else {
                            Files.createDirectories(targetFile.getParent());
                            Files.copy(zis, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        }
                        zis.closeEntry();
                    }
                }
            } finally {
                Files.deleteIfExists(tempZip);
            }
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> result = new java.util.HashMap<>();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        result.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                    }
                }
            }
            return result;
        }
    }

    private static String getCachedMD5(Path path) {
        try {
            if (!Files.exists(path)) return "";
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            CacheEntry entry = MD5_CACHE.get(path);
            
            if (entry != null && entry.lastModified == lastModified) {
                return entry.md5;
            }
            
            // 缓存失效或不存在，重新计算
            String md5 = getFileMD5(path.toFile());
            if (!md5.isEmpty()) {
                MD5_CACHE.put(path, new CacheEntry(md5, lastModified));
                // 异步保存，避免阻塞当前请求
                CompletableFuture.runAsync(EmbeddedServer::saveCache);
            }
            return md5;
        } catch (IOException e) {
            return "";
        }
    }

    private static void loadCache() {
        Path cacheFile = FMLPaths.CONFIGDIR.get().resolve("mmdsync_cache.json");
        if (!Files.exists(cacheFile)) return;

        try (Reader reader = Files.newBufferedReader(cacheFile, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                try {
                    Path path = Path.of(entry.getKey());
                    JsonObject data = entry.getValue().getAsJsonObject();
                    String md5 = data.get("md5").getAsString();
                    long lastModified = data.get("lastModified").getAsLong();
                    MD5_CACHE.put(path, new CacheEntry(md5, lastModified));
                } catch (Exception ignored) {}
            }
            MMDSyncMod.LOGGER.info("已加载 {} 条 MD5 缓存记录", MD5_CACHE.size());
        } catch (Exception e) {
            MMDSyncMod.LOGGER.error("加载 MD5 缓存失败", e);
        }
    }

    private static synchronized void saveCache() {
        Path cacheFile = FMLPaths.CONFIGDIR.get().resolve("mmdsync_cache.json");
        JsonObject json = new JsonObject();
        for (Map.Entry<Path, CacheEntry> entry : MD5_CACHE.entrySet()) {
            JsonObject data = new JsonObject();
            data.addProperty("md5", entry.getValue().md5);
            data.addProperty("lastModified", entry.getValue().lastModified);
            json.add(entry.getKey().toString(), data);
        }

        try (Writer writer = Files.newBufferedWriter(cacheFile, StandardCharsets.UTF_8)) {
            writer.write(json.toString());
        } catch (Exception e) {
            MMDSyncMod.LOGGER.error("保存 MD5 缓存失败", e);
        }
    }

    private static String getFileMD5(File file) {
        try (InputStream fis = Files.newInputStream(file.toPath())) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[65536]; // 提升至 64KB 缓冲区，更适合 100MB+ 大文件
            int n;
            while ((n = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            byte[] digestBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digestBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
