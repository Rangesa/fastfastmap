package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Slippy Mapのタイル座標。
 *
 * ズームレベル定義（ズームアウト方向）:
 *   zoom -3 → 16 blocks/px, 4096 blocks/tile (256チャンク/tile)  ← 最もズームアウト
 *   zoom -2 →  8 blocks/px, 2048 blocks/tile
 *   zoom -1 →  4 blocks/px, 1024 blocks/tile
 *   zoom  0 →  2 blocks/px,  512 blocks/tile
 *   zoom  1 →  1 px/block,   256 blocks/tile
 *   zoom  2 →  2 px/block,   128 blocks/tile
 *   zoom  3 →  4 px/block,    64 blocks/tile
 *   zoom  4 →  8 px/block,    32 blocks/tile
 *   zoom  5 → 16 px/block,    16 blocks/tile (1チャンク = 1タイル) ← 最もズームイン
 */
@Environment(EnvType.CLIENT)
public record TileCoord(int zoom, int x, int z) {

    public static final int TILE_PX  = 256;
    public static final int MIN_ZOOM = -3;
    public static final int MAX_ZOOM = 5;

    /**
     * ズームレベル z の 1ブロックあたりのピクセル数。
     * zoom >= 1: 2^(z-1)   (1, 2, 4, 8, 16)
     * zoom <= 0: 1          (ブロックよりピクセルが粗い領域では1未満になるため常に1を返す)
     */
    public static int pixelsPerBlock(int zoom) {
        return zoom >= 1 ? (1 << (zoom - 1)) : 1;
    }

    /**
     * ズームレベル z の 1ピクセルあたりのブロック数。
     * zoom >= 1: 1
     * zoom <= 0: 2^(1-z)  (2, 4, 8, 16 for z=0,-1,-2,-3)
     */
    public static int blocksPerPixel(int zoom) {
        return zoom <= 0 ? (1 << (1 - zoom)) : 1;
    }

    /**
     * ズームレベル z の 1タイルあたりのブロック数。
     * zoom >= 1: 256 >> (z-1)        (256, 128, 64, 32, 16)
     * zoom <= 0: 256 << (1-z)  (512, 1024, 2048, 4096 for z=0,-1,-2,-3)
     */
    public static int blocksPerTile(int zoom) {
        return zoom >= 1 ? (TILE_PX >> (zoom - 1)) : (TILE_PX << (1 - zoom));
    }

    /** ワールドブロック座標が属するタイル座標 */
    public static TileCoord fromBlock(int zoom, int blockX, int blockZ) {
        int b = blocksPerTile(zoom);
        return new TileCoord(zoom, Math.floorDiv(blockX, b), Math.floorDiv(blockZ, b));
    }

    /** このタイルがカバーするワールドブロックX座標の開始値 */
    public int blockXStart() { return x * blocksPerTile(zoom); }
    /** このタイルがカバーするワールドブロックZ座標の開始値 */
    public int blockZStart() { return z * blocksPerTile(zoom); }
}
