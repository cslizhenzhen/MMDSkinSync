package com.opdent.mmdskin.sync;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;

import com.tendoarisu.mmdskin.sync.Config;
import com.tendoarisu.mmdskin.sync.EmbeddedServer;
import com.opdent.mmdskin.sync.network.SyncUrlPacket;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(MMDSyncMod.MODID)
public class MMDSyncMod {
    public static final String MODID = "mmdsync";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MMDSyncMod(IEventBus modEventBus, ModContainer modContainer) {
        // 加载自定义配置
        Config.load();
        
        // 启动内置服务器
        EmbeddedServer.start();
        
        // 注册网络包
        modEventBus.addListener(this::registerPayloads);
        
        // 注册命令
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // 监听玩家加入
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(MODID);
        registrar.commonToClient(SyncUrlPacket.TYPE, SyncUrlPacket.STREAM_CODEC, (payload, context) -> {
            context.enqueueWork(() -> {
                SyncManager.setServerUrlOverride(payload.url());
            });
        });
    }

    private void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!event.getEntity().level().isClientSide) {
            String serverUrl = Config.SERVER_URL.trim();
            // 如果服务端配置留空，则下发 ":端口" 以告知客户端使用自动 IP + 此端口
            if (serverUrl.isEmpty()) {
                serverUrl = ":" + Config.SERVER_PORT;
            }
            
            // 下发地址给客户端
            PacketDistributor.sendToPlayer((net.minecraft.server.level.ServerPlayer) event.getEntity(), new SyncUrlPacket(serverUrl));
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        CommandHandler.register(event.getDispatcher());
    }
}
