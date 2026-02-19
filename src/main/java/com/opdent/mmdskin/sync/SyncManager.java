package com.opdent.mmdskin.sync;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tendoarisu.mmdskin.sync.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SyncManager {
    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .executor(java.util.concurrent.Executors.newFixedThreadPool(4)) // 限制 HTTP 客户端内部线程池
            .build();
    private static String serverUrlOverride = null;

    public static void setServerUrlOverride(String url) {
        serverUrlOverride = url;
        if (url != null && !url.isEmpty()) {
            MMDSyncMod.LOGGER.info("收到服务器下发的同步地址: {}", url);
        }
    }

    public static String getServerUrl() {
        String serverUrl = serverUrlOverride;
        
        // 始终遵循：服务器下发 > 自动检测
        // 方案 B：不再读取本地 Config.SERVER_URL，因为那是服务端用的
        
        // 如果没有服务器下发的地址，则进入自动检测逻辑（视为留空）
        if (serverUrl == null || serverUrl.isEmpty()) {
            serverUrl = ""; // 强制进入下方的自动检测逻辑
        }
        
        // 如果留空或只填了端口（如 :5000），尝试获取当前连接的服务器 IP
        if (serverUrl.isEmpty() || serverUrl.startsWith(":")) {
            net.minecraft.client.multiplayer.ServerData serverData = Minecraft.getInstance().getConnection() != null 
                    ? Minecraft.getInstance().getConnection().getServerData() 
                    : null;
            if (serverData != null) {
                String ip = serverData.ip;
                // 去掉 IP 中原有的端口号
                if (ip.contains(":")) {
                    ip = ip.substring(0, ip.indexOf(":"));
                }
                
                if (serverUrl.startsWith(":")) {
                    // 如果用户只填了 :端口，则使用用户填的端口
                    serverUrl = ip + serverUrl;
                } else {
                    // 如果完全留空，使用配置的端口
                    serverUrl = ip + ":" + Config.SERVER_PORT;
                }
            } else {
                return null;
            }
        }
        
        // 自动处理协议前缀
        if (!serverUrl.toLowerCase().startsWith("http://") && !serverUrl.toLowerCase().startsWith("https://")) {
            serverUrl = "http://" + serverUrl;
        }
        
        if (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        return serverUrl;
    }

    public static void startSync() {
        final String baseUrl = getServerUrl();
        if (baseUrl == null) {
            MMDSyncMod.LOGGER.warn("配置 serverUrl 为空且未连接到服务器，跳过同步。");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                notifyUser("正在从服务器同步 MMD 模型资源...", false);

                // Fetch manifest
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/sync"))
                        .GET()
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    notifyUser("连接资源服务器失败: " + response.statusCode(), true);
                    return;
                }

                JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
                JsonArray pmxFiles = json.getAsJsonArray("pmx");
                JsonArray vmdFiles = json.getAsJsonArray("vmd");

                Path gameDir = FMLPaths.GAMEDIR.get();
                Path pmxDir = gameDir.resolve("3d-skin/EntityPlayer");
                Path vmdDir = gameDir.resolve("3d-skin/StageAnim");

                int downloadedCount = 0;
                downloadedCount += syncZone(baseUrl, "pmx", pmxDir, pmxFiles);
                downloadedCount += syncZone(baseUrl, "vmd", vmdDir, vmdFiles);

                if (downloadedCount > 0) {
                    notifyUser("MMD 资源同步完成，共更新 " + downloadedCount + " 个文件。", false);
                } else {
                    notifyUser("MMD 资源文件已是最新。", false);
                }

            } catch (Exception e) {
                MMDSyncMod.LOGGER.error("同步文件失败", e);
                notifyUser("MMD 资源同步出错: " + e.getMessage(), true);
            }
        });
    }

    private static int syncZone(String baseUrl, String zone, Path localDir, JsonArray folders) throws IOException, InterruptedException {
        int count = 0;
        if (folders == null) return 0;

        for (JsonElement element : folders) {
            if (!element.isJsonObject()) continue;
            JsonObject folderObj = element.getAsJsonObject();
            
            // 安全获取字段，防止 NPE
            JsonElement nameElem = folderObj.get("name");
            JsonElement md5Elem = folderObj.get("md5");
            if (nameElem == null || md5Elem == null) continue;
            
            String folderName = nameElem.getAsString();
            String serverMd5 = md5Elem.getAsString();
            
            Path folderPath = localDir.resolve(folderName);
            
            boolean needsDownload = false;
            if (!Files.exists(folderPath)) {
                needsDownload = true;
            } else {
                // 计算本地文件夹 MD5
                String localMd5 = getFolderMD5(folderPath);
                if (!serverMd5.equalsIgnoreCase(localMd5)) {
                    needsDownload = true;
                }
            }

            if (needsDownload) {
                // 对文件夹名进行编码
                String encodedName = URLEncoder.encode(folderName, StandardCharsets.UTF_8).replace("+", "%20");
                String downloadUrl = baseUrl + "/download/" + zone + "/" + encodedName;
                
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(downloadUrl))
                            .GET()
                            .build();
                    
                    HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    if (response.statusCode() == 200) {
                        // 下载的是 ZIP，直接解压到目标目录
                        try (ZipInputStream zis = new ZipInputStream(response.body())) {
                            ZipEntry entry;
                            while ((entry = zis.getNextEntry()) != null) {
                                Path target = folderPath.resolve(entry.getName().replace("/", File.separator));
                                if (entry.isDirectory()) {
                                    Files.createDirectories(target);
                                } else {
                                    Files.createDirectories(target.getParent());
                                    Files.copy(zis, target, StandardCopyOption.REPLACE_EXISTING);
                                }
                                zis.closeEntry();
                            }
                        }
                        count++;
                    } else {
                        MMDSyncMod.LOGGER.error("无法下载资源包 {}: {}", downloadUrl, response.statusCode());
                    }
                } catch (Exception e) {
                    MMDSyncMod.LOGGER.error("同步资源包异常: " + downloadUrl, e);
                }
            }
        }
        return count;
    }

    private static String getFolderMD5(Path folder) {
        try (Stream<Path> stream = Files.walk(folder)) {
            StringBuilder combined = new StringBuilder();
            stream.filter(Files::isRegularFile)
                  .sorted()
                  .forEach(p -> combined.append(getFileMD5(p.toFile())));
            
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(combined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String getFileMD5(File file) {
        try (InputStream fis = Files.newInputStream(file.toPath())) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[65536]; // 客户端也同步升级为 64KB 缓冲区
            int n;
            while ((n = fis.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void notifyUser(String message, boolean isError) {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal((isError ? "§c" : "§a") + "[MMDSync] " + message), 
                    false
                );
            }
        });
    }
}
