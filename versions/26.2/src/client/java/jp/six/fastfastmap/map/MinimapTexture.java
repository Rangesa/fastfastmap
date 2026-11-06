package jp.six.fastfastmap.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * MinimapTexture: 512x512 の NativeImage バックドテクスチャ。
 *
 * - writeChunk() はアンカーチャンク座標基準でピクセル位置を計算する。
 * - writeChunk() / clear() / upload() はレンダースレッドのみから呼ぶこと。
 */
@Environment(EnvType.CLIENT)
public class MinimapTexture implements AutoCloseable {

    public static final int SIZE = 512;

    private final NativeImage image;
    private final DynamicTexture backendTexture;
    private final Identifier id;

    public MinimapTexture() {
        image = new NativeImage(SIZE, SIZE, false);
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                image.setPixel(x, y, 0xFF000000);
            }
        }
        backendTexture = new DynamicTexture(() -> "fastfastmap_minimap", image);
        id = Identifier.fromNamespaceAndPath("fastfastmap", "minimap");
        Minecraft.getInstance().getTextureManager().register(id, backendTexture);
    }

    /**
     * チャンクの 16x16 ピクセルをテクスチャに書き込む。
     * アンカーチャンク座標を基準にテクスチャ上の位置を決定する。
     *
     * @param cx       書き込むチャンクの X 座標
     * @param cz       書き込むチャンクの Z 座標
     * @param argbColors ARGB 色配列[256] (x + z*16 のレイアウト)
     * @param anchorCX テクスチャ中心 (256, 256) に対応するアンカーチャンクの X
     * @param anchorCZ テクスチャ中心 (256, 256) に対応するアンカーチャンクの Z
     */
    public void writeChunk(int cx, int cz, int[] argbColors, int anchorCX, int anchorCZ) {
        int px = SIZE / 2 + (cx - anchorCX) * 16;
        int pz = SIZE / 2 + (cz - anchorCZ) * 16;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int tx = px + x;
                int tz = pz + z;
                if (tx >= 0 && tx < SIZE && tz >= 0 && tz < SIZE) {
                    image.setPixel(tx, tz, argbColors[x + z * 16]);
                }
            }
        }
    }

    /**
     * テクスチャ全体を不透明な黒でクリアする（再アンカー時に呼ぶ）。
     */
    public void clear() {
        image.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
    }

    public void upload() {
        backendTexture.upload();
    }

    public Identifier getId() {
        return id;
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(id);
        backendTexture.close();
    }
}
