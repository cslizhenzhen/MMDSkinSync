package com.tendoarisu.mmdskin.sync;

import com.opdent.mmdskin.sync.MMDSyncMod;
import com.opdent.mmdskin.sync.SyncManager;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = MMDSyncMod.MODID, value = Dist.CLIENT)
public class ClientGameEvents {

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        // Clear server URL override when logging out
        SyncManager.setServerUrlOverride(null);
    }

    @SubscribeEvent
    public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // Trigger sync when logging into a server
        // Delay sync slightly to allow packet to arrive
        Util.backgroundExecutor().execute(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {}
            SyncManager.startSync();
        });
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen().getClass().getName().equals("com.shiroha.mmdskin.ui.selector.ModelSelectorScreen")) {
            // 获取原始界面的关键布局参数
            int panelWidth = 140; // 对应 ModelSelectorScreen.PANEL_WIDTH
            int panelMargin = 4;  // 对应 ModelSelectorScreen.PANEL_MARGIN
            int footerHeight = 20; // 对应 ModelSelectorScreen.FOOTER_HEIGHT
            
            // 计算面板基准位置 (保持与原始类一致)
            int panelX = event.getScreen().width - panelWidth - panelMargin;
            int panelY = panelMargin;
            int panelH = event.getScreen().height - panelMargin * 2;
            int listBottom = panelY + panelH - footerHeight;

            // 原始刷新/确定按钮在 listBottom + 4
            // 我们将“上传模型”按钮放在 listBottom - 14 (即列表的最下方，按钮的上方)
            // 这样既不会被任务栏挡住，也符合操作逻辑
            int btnW = panelWidth - 8;
            int btnH = 14;
            int btnY = listBottom - btnH - 2; // 放在列表底部边缘上方 2 像素

            event.addListener(Button.builder(Component.literal("§6上传模型资源"), btn -> {
                String url = SyncManager.getServerUrl();
                if (url != null && !url.isEmpty()) {
                    Util.getPlatform().openUri(url);
                }
            }).bounds(panelX + 4, btnY, btnW, btnH).build());
        }
    }
}
