# MMDSync

MMDSync 是一个专为 Minecraft 设计的 MMD 资源同步模组（目前基于 NeoForge）。它旨在解决多人游戏中 MMD 模型（PMX）和动作数据（VMD）分发困难的问题，提供了内置 Web 服务器、增量同步、智能压缩等高级功能。

## ✨ 主要功能

*   **内置 Web 管理界面**：无需通过 FTP 或繁琐的文件传输，直接在浏览器中（默认端口 5000）拖拽上传模型文件。
*   **智能整包同步**：自动识别模型文件夹，支持将整个模型目录打包为 ZIP 进行一次性下载，大幅减少 HTTP 请求次数，提升跨运营商网络下的同步速度。
*   **增量更新**：基于 MD5 校验，客户端仅下载变更或缺失的文件/文件夹，节省带宽。
*   **高性能传输**：
    *   支持 GZIP 压缩（智能排除已压缩文件），减少传输体积。
    *   64KB 大缓冲区设计，优化高延迟网络体验。
    *   支持服务器端下行带宽限制（QoS）。
*   **无缝体验**：客户端下载完成后自动解压并归档到正确目录（`3d-skin/EntityPlayer` 或 `3d-skin/StageAnim`），无需手动干预。

## 🛠️ 安装与使用

1.  **安装**：将模组 `.jar` 文件放入服务端和客户端的 `mods` 文件夹。
2.  **配置**：启动一次游戏后，配置文件生成于 `config/mmdsync-common.toml`。
3.  **上传资源**：
    *   服务端启动后，访问 `http://<服务器IP>:5000`。
    *   选择上传类型（模型 PMX / 动作 VMD）。
    *   拖入 `.zip` 压缩包或直接选择模型文件夹上传。
4.  **客户端同步**：
    *   玩家进入服务器后，输入指令 `/mmdsync sync` 开始同步。
    *   同步完成后，MMDSkin 模组即可直接读取最新的模型资源。

## ⚙️ 配置文件

```toml
[general]
# 模型同步服务器地址
serverUrl = ""

# 是否开启内置同步服务器 (服务端建议开启)
enableServer = true

# 内置服务器端口
serverPort = 5000

# 最大下行带宽 (Mbps)，0 为不限制
maxBandwidthMbps = 0.0

# 是否启用 GZIP 压缩
enableGzip = true
```

## 🏗️ 开发构建

```bash
# Windows
./gradlew build

# Linux / macOS
./gradlew build
```

## 📄 开源协议

MIT License
