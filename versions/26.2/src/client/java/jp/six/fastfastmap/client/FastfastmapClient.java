package jp.six.fastfastmap.client;

import com.mojang.blaze3d.platform.InputConstants;
import jp.six.fastfastmap.map.FastMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class FastfastmapClient implements ClientModInitializer {

    public static KeyMapping fullscreenMapKey;

    @Override
    public void onInitializeClient() {
        FastMap map = FastMap.getInstance();

        // M キー: 全画面マップ
        fullscreenMapKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fastfastmap.fullscreen_map",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("fastfastmap", "keybindings"))
        ));

        // チャンクロード時にスキャン
        ClientChunkEvents.CHUNK_LOAD.register(map::onChunkLoad);

        // チャンクアンロード時にキャッシュ削除
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                map.onChunkUnload(chunk.getPos()));

        // 毎tick: プレイヤー位置追跡・初期化 + Mキー判定
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            map.onTick(client);

            while (fullscreenMapKey.consumeClick()) {
                if (client.gui.screen() == null) {
                    client.gui.setScreen(new FullscreenMapScreen());
                }
            }
        });

        // HUD 描画（BossBar の後に追加）
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR,
                Identifier.fromNamespaceAndPath("fastfastmap", "minimap"),
                (context, tickCounter) -> map.onHudRender(context)
        );
    }
}
