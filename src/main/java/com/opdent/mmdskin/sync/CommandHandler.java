package com.opdent.mmdskin.sync;

import com.mojang.brigadier.CommandDispatcher;
import com.tendoarisu.mmdskin.sync.EmbeddedServer;
import com.tendoarisu.mmdskin.sync.Config;
import com.opdent.mmdskin.sync.network.SyncUrlPacket;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.UUID;

public class CommandHandler {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mmdsync")
            .then(Commands.literal("reload")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§a正在重新加载配置并同步到所有玩家..."), true);
                    
                    // 1. 重新加载本地自定义配置
                    Config.load();

                    // 2. 重启内置服务器
                    EmbeddedServer.stop();
                    EmbeddedServer.start();

                    // 3. 核心修复：向所有在线玩家重新下发最新的服务器地址
                    if (!context.getSource().getLevel().isClientSide) {
                        String serverUrl = Config.SERVER_URL.trim();
                        // 如果服务端配置留空，则下发 ":端口" 以告知客户端使用自动 IP + 此端口
                        final String finalServerUrl = serverUrl.isEmpty() ? ":" + Config.SERVER_PORT : serverUrl;
                        
                        SyncUrlPacket packet = new SyncUrlPacket(finalServerUrl);
                        for (net.minecraft.server.level.ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                            PacketDistributor.sendToPlayer(player, packet);
                        }
                        context.getSource().sendSuccess(() -> Component.literal("§a已向所有在线玩家同步最新服务器地址: " + finalServerUrl), true);
                    }

                    context.getSource().sendSuccess(() -> Component.literal("§a配置重载完成，内置服务器已重启。"), true);
                    return 1;
                })
            )
            .then(Commands.literal("sync")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal("§a正在全服同步模型..."), true);
                    try {
                        // 1. 下发最新的服务器地址，确保客户端下载地址正确
                        String serverUrl = Config.SERVER_URL.trim();
                        final String finalServerUrl = serverUrl.isEmpty() ? ":" + Config.SERVER_PORT : serverUrl;
                        SyncUrlPacket urlPacket = new SyncUrlPacket(finalServerUrl);
                        for (net.minecraft.server.level.ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                            PacketDistributor.sendToPlayer(player, urlPacket);
                        }

                        // 2. 重新广播所有在线玩家的模型选择，触发客户端下载
                        // 动态获取附件类型和包类，避免编译时强依赖
                        Object attachmentType = Class.forName("com.shiroha.mmdskin.neoforge.register.MmdSkinAttachments")
                                .getField("PLAYER_MMD_MODEL").get(null);
                        java.util.function.Supplier<?> supplier = (java.util.function.Supplier<?>) attachmentType;
                        net.neoforged.neoforge.attachment.AttachmentType<String> type = (net.neoforged.neoforge.attachment.AttachmentType<String>) supplier.get();

                        Class<?> packetClass = Class.forName("com.shiroha.mmdskin.neoforge.network.MmdSkinNetworkPack");
                        java.lang.reflect.Method withAnimId = packetClass.getMethod("withAnimId", int.class, UUID.class, String.class);

                        int count = 0;
                        for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                            String modelName = player.getData(type);
                            if (modelName != null && !modelName.isEmpty()) {
                                Object packet = withAnimId.invoke(null, 3, player.getUUID(), modelName);
                                PacketDistributor.sendToAllPlayers((net.minecraft.network.protocol.common.custom.CustomPacketPayload) packet);
                                count++;
                            }
                        }
                        final int finalCount = count;
                        context.getSource().sendSuccess(() -> Component.literal("§a已为全服 " + finalCount + " 名玩家重新同步模型并更新下载地址。"), true);
                    } catch (Exception e) {
                        context.getSource().sendFailure(Component.literal("§c同步失败: " + e.getMessage()));
                        e.printStackTrace();
                    }
                    return 1;
                })
            )
        );
    }
}
