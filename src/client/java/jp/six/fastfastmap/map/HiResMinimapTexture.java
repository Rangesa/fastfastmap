package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

/**
 * 高解像テクスチャ（全画面マップのズームイン時用）。
 *
 * 1ブロック = PIXELS_PER_BLOCK ピクセルで描画するため、
 * 通常テクスチャの4倍の解像度で128×128ブロック範囲をカバーする。
 *
 * 通常テクスチャ: 1px/block × 512 = 512ブロック範囲
 * 高解像テクスチャ: 4px/block × 128 = 128ブロック範囲（±4チャンク）
 */
@Environment(EnvType.CLIENT)
public class HiResMinimapTexture implements AutoCloseable {

    public static final int SIZE             = 512;
    public static final int PIXELS_PER_BLOCK = 4;
    /** カバーするブロック数: SIZE / PIXELS_PER_BLOCK = 128 */
    public static final int BLOCKS_COVERED   = SIZE / PIXELS_PER_BLOCK;
    /** カバーするチャンク数: BLOCKS_COVERED / 16 / 2 = ±4チャンク */
    public static final int CHUNK_RANGE      = BLOCKS_COVERED / 16 / 2;
    /** 1チャンクあたりのピクセル数: 16 * PIXELS_PER_BLOCK = 64 */
    public static final int CHUNK_SIZE_PX    = 16 * PIXELS_PER_BLOCK;

    private final NativeImage image;
    private final NativeImageBackedTexture backendTexture;
    private final Identifier id;

    public HiResMinimapTexture() {
        image = new NativeImage(SIZE, SIZE, false);
        image.fillRect(0, 0, SIZE, SIZE, 0xFF000000);
        backendTexture = new NativeImageBackedTexture(() -> "fastfastmap_hiresmap", image);
        id = Identifier.of("fastfastmap", "hiresmap");
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, backendTexture);
    }

    /**
     * チャンクの色配列を高解像テクスチャに書き込む。
     * 各ブロックを PIXELS_PER_BLOCK × PIXELS_PER_BLOCK ピクセルで描画する。
     */
    public void writeChunk(int cx, int cz, int[] argbColors, int anchorCX, int anchorCZ) {
        int px = SIZE / 2 + (cx - anchorCX) * CHUNK_SIZE_PX;
        int pz = SIZE / 2 + (cz - anchorCZ) * CHUNK_SIZE_PX;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int color = argbColors[x + z * 16];
                int bx = px + x * PIXELS_PER_BLOCK;
                int bz = pz + z * PIXELS_PER_BLOCK;
                for (int py = 0; py < PIXELS_PER_BLOCK; py++) {
                    for (int ppx = 0; ppx < PIXELS_PER_BLOCK; ppx++) {
                        int tx = bx + ppx;
                        int tz = bz + py;
                        if (tx >= 0 && tx < SIZE && tz >= 0 && tz < SIZE) {
                            image.setColorArgb(tx, tz, color);
                        }
                    }
                }
            }
        }
    }

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
        MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
        backendTexture.close();
    }
}
