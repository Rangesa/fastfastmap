package jp.six.fastfastmap.client;

import jp.six.fastfastmap.map.FastMap;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;


import org.lwjgl.glfw.GLFW;

public class FastfastmapClient implements ClientModInitializer {

    public static KeyBinding fullscreenMapKey;

    @Override
    public void onInitializeClient() {
        FastMap map = FastMap.getInstance();

        // M キー: 全画面マップ
        fullscreenMapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.fastfastmap.fullscreen_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                KeyBinding.Category.create(Identifier.of("fastfastmap", "keybindings"))
        ));

        // チャンクロード時にスキャン
        ClientChunkEvents.CHUNK_LOAD.register(map::onChunkLoad);

        // チャンクアンロード時にキャッシュ削除
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) ->
                map.onChunkUnload(chunk.getPos()));

        // 毎tick: プレイヤー位置追跡・初期化 + Mキー判定
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            map.onTick(client);

            while (fullscreenMapKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new FullscreenMapScreen());
                }
            }
        });

        // HUD 描画（BossBar の後に追加）
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR,
                Identifier.of("fastfastmap", "minimap"),
                (context, tickCounter) -> map.onHudRender(context)
        );
    }
}
