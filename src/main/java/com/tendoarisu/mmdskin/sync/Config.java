package com.tendoarisu.mmdskin.sync;

import com.opdent.mmdskin.sync.MMDSyncMod;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案 B: 自定义配置加载器
 * 保持 TOML 样式但采用自定义解析，支持 serverUrl 不加引号
 */
public class Config {
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("mmdsync-common.toml");

    // 配置项
    public static String SERVER_URL = "";
    public static boolean ENABLE_SERVER = false;
    public static int SERVER_PORT = 5000;
    public static double MAX_BANDWIDTH_MBPS = 0.0;
    public static boolean ENABLE_GZIP = true;

    public static void load() {
        if (!Files.exists(CONFIG_FILE)) {
            save();
            return;
        }

        try {
            List<String> lines = Files.readAllLines(CONFIG_FILE, StandardCharsets.UTF_8);
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;

                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    // 处理可能存在的引号（如果用户加了也兼容）
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }

                    switch (key) {
                        case "serverUrl" -> SERVER_URL = value;
                        case "enableServer" -> ENABLE_SERVER = Boolean.parseBoolean(value);
                        case "serverPort" -> {
                            try { SERVER_PORT = Integer.parseInt(value); } catch (NumberFormatException ignored) {}
                        }
                        case "maxBandwidthMbps" -> {
                            try { MAX_BANDWIDTH_MBPS = Double.parseDouble(value); } catch (NumberFormatException ignored) {}
                        }
                        case "enableGzip" -> ENABLE_GZIP = Boolean.parseBoolean(value);
                    }
                }
            }
        } catch (IOException e) {
            MMDSyncMod.LOGGER.error("加载配置文件失败", e);
        }
    }

    public static void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# MMDSync 配置文件");
        lines.add("");
        lines.add("[general]");
        lines.add("# 模型同步服务器地址 (作为服务端运行时的下发地址，客户端将优先使用服务器下发或自动检测的地址)");
        lines.add("# 方案 B 特色：此处地址可以不加引号！");
        lines.add("serverUrl = " + SERVER_URL);
        lines.add("");
        lines.add("# 是否开启内置同步服务器");
        lines.add("enableServer = " + ENABLE_SERVER);
        lines.add("");
        lines.add("# 内置服务器端口");
        lines.add("serverPort = " + SERVER_PORT);
        lines.add("");
        lines.add("# 内置服务器最大下行带宽 (Mbps)，0 为不限制");
        lines.add("maxBandwidthMbps = " + MAX_BANDWIDTH_MBPS);
        lines.add("");
        lines.add("# 是否启用 GZIP 压缩以节省带宽");
        lines.add("enableGzip = " + ENABLE_GZIP);

        try {
            Files.write(CONFIG_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            MMDSyncMod.LOGGER.error("保存配置文件失败", e);
        }
    }
}
