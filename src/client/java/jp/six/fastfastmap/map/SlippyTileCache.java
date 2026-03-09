package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slippy Mapのタイルを管理する。
 *
 * ── チラつき防止の設計 ──────────────────────────────────────────
 * チャンク更新時に古い GPU タイルを即削除しない。
 * 新しいレンダリングが完了するまで古いタイルを表示し続け、
 * 完了後に pendingGpuUpdate キュー経由でシームレスに差し替える。
 *
 * ── スレッドモデル ──────────────────────────────────────────────
 *   ワーカースレッド : renderTile() で int[] を生成 → tilePixels に格納 → pendingGpuUpdate に通知
 *   レンダースレッド : getOrRequest() で GPU テクスチャを取得/アップロード/差し替え
 *   任意スレッド    : invalidateChunk() は tilePixels のみ削除（GPU タイルはそのまま）
 */
@Environment(EnvType.CLIENT)
public class SlippyTileCache {

    private static final int MAX_GPU_TILES = 128;

    /** int[] ピクセルキャッシュ（任意スレッドから安全） */
    private final ConcurrentHashMap<TileCoord, int[]> tilePixels = new ConcurrentHashMap<>();

    /** GPU テクスチャキャッシュ（レンダースレッドのみ、LRU 自動削除） */
    private final LinkedHashMap<TileCoord, GpuTile> gpuTiles =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<TileCoord, GpuTile> eldest) {
                    if (size() > MAX_GPU_TILES) {
                        eldest.getValue().close();
                        return true;
                    }
                    return false;
                }
            };

    /** レンダー中のタイル（重複サブミット防止） */
    private final Set<TileCoord> rendering =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * 新しい int[] が tilePixels に入ったタイル（GPU 差し替えが必要）。
     * ワーカースレッドから書き込み、レンダースレッドで読み出す。
     */
    private final ConcurrentLinkedQueue<TileCoord> pendingGpuUpdate = new ConcurrentLinkedQueue<>();

    private final ConcurrentHashMap<Long, int[]> colorCache;
    private final ExecutorService executor;
    private final AtomicInteger texSerial = new AtomicInteger();

    public SlippyTileCache(ConcurrentHashMap<Long, int[]> colorCache, ExecutorService executor) {
        this.colorCache = colorCache;
        this.executor = executor;
    }

    // ── レンダースレッドから呼ぶ ──────────────────────────────────

    /**
     * タイルの GPU テクスチャ ID を返す。
     * - GPU キャッシュにあれば即返す（古いデータでも表示し続けてチラつきを防ぐ）
     * - 新データが pendingGpuUpdate にあれば差し替えてから返す
     * - 未レンダリングなら非同期レンダリングを要求して null を返す
     */
    public Identifier getOrRequest(TileCoord coord) {
        // 新しいレンダリングが完了したタイルを GPU に差し替え
        processPendingGpuUpdates();

        // GPU キャッシュヒット（古いデータでも表示 → チラつきなし）
        GpuTile gpu = gpuTiles.get(coord);
        if (gpu != null) {
            // RAM が空（invalidate済み）かつ未レンダリングなら再レンダリングを要求
            // GPU タイルは引き続き表示 → チラつきなし
            if (!tilePixels.containsKey(coord) && !rendering.contains(coord)) {
                requestRender(coord);
            }
            return gpu.id;
        }

        // RAM キャッシュヒット → 初回 GPU アップロード
        int[] pixels = tilePixels.get(coord);
        if (pixels != null) {
            GpuTile newGpu = uploadToGpu(pixels);
            gpuTiles.put(coord, newGpu);
            return newGpu.id;
        }

        // まだレンダリングされていない → 非同期レンダリングを要求
        requestRender(coord);
        return null;
    }

    /**
     * 低解像フォールバックの Identifier と UV 座標を返す。
     * タイルが未準備のとき、一段低 zoom のタイルで代替表示する。
     */
    public Identifier getFallback(TileCoord coord, int[] uvOut) {
        for (int fz = coord.zoom() - 1; fz >= TileCoord.MIN_ZOOM; fz--) {
            int b  = TileCoord.blocksPerTile(fz);
            int tx = Math.floorDiv(coord.blockXStart(), b);
            int tz = Math.floorDiv(coord.blockZStart(), b);
            TileCoord fallback = new TileCoord(fz, tx, tz);
            Identifier id = getOrRequest(fallback);
            if (id != null) {
                int ratio  = TileCoord.blocksPerTile(fz) / TileCoord.blocksPerTile(coord.zoom());
                int localX = coord.x() - tx * ratio;
                int localZ = coord.z() - tz * ratio;
                uvOut[0] = localX * TileCoord.TILE_PX / ratio;
                uvOut[1] = localZ * TileCoord.TILE_PX / ratio;
                uvOut[2] = TileCoord.TILE_PX / ratio;
                return id;
            }
        }
        return null;
    }

    /** ワールドアンロード時などに全タイルを破棄する（レンダースレッドから呼ぶ）。 */
    public void clear() {
        tilePixels.clear();
        rendering.clear();
        pendingGpuUpdate.clear();
        for (GpuTile gpu : gpuTiles.values()) gpu.close();
        gpuTiles.clear();
    }

    // ── 任意スレッドから呼ぶ ──────────────────────────────────────

    /**
     * チャンクが更新されたとき、そのチャンクを含む全ズームレベルの
     * RAM キャッシュを削除して再レンダリングを促す。
     *
     * GPU タイルは削除しない。古い表示を維持しながら、
     * 新レンダリング完了後に pendingGpuUpdate 経由でシームレスに差し替える。
     */
    public void invalidateChunk(int cx, int cz) {
        for (int zoom = TileCoord.MIN_ZOOM; zoom <= TileCoord.MAX_ZOOM; zoom++) {
            int b    = TileCoord.blocksPerTile(zoom);
            int txMin = Math.floorDiv(cx * 16,      b);
            int txMax = Math.floorDiv(cx * 16 + 15, b);
            int tzMin = Math.floorDiv(cz * 16,      b);
            int tzMax = Math.floorDiv(cz * 16 + 15, b);
            for (int tx = txMin; tx <= txMax; tx++) {
                for (int tz = tzMin; tz <= tzMax; tz++) {
                    // RAM のみ削除。GPU タイルはそのまま（チラつき防止）
                    tilePixels.remove(new TileCoord(zoom, tx, tz));
                }
            }
        }
    }

    // ── 内部実装 ──────────────────────────────────────────────────

    /**
     * pendingGpuUpdate のタイルを GPU に差し替える（レンダースレッドのみ）。
     * 古い GPU テクスチャを閉じてから新しいものを登録する。
     */
    private void processPendingGpuUpdates() {
        TileCoord tc;
        while ((tc = pendingGpuUpdate.poll()) != null) {
            int[] pixels = tilePixels.get(tc);
            if (pixels == null) continue; // まだ再レンダリング中（次フレームで再試行）
            GpuTile newGpu = uploadToGpu(pixels);
            GpuTile oldGpu = gpuTiles.put(tc, newGpu);
            if (oldGpu != null) oldGpu.close();
        }
    }

    private void requestRender(TileCoord coord) {
        if (rendering.add(coord)) {
            executor.submit(() -> {
                try {
                    int[] pixels = renderTile(coord);
                    tilePixels.put(coord, pixels);
                    pendingGpuUpdate.offer(coord); // GPU 差し替えを通知
                } finally {
                    rendering.remove(coord);
                }
            });
        }
    }

    private GpuTile uploadToGpu(int[] pixels) {
        NativeImage img = new NativeImage(TileCoord.TILE_PX, TileCoord.TILE_PX, false);
        for (int i = 0; i < pixels.length; i++) {
            img.setColorArgb(i % TileCoord.TILE_PX, i / TileCoord.TILE_PX, pixels[i]);
        }
        NativeImageBackedTexture tex = new NativeImageBackedTexture(() -> "fastfastmap_tile", img);
        Identifier id = Identifier.of("fastfastmap", "tile_" + texSerial.getAndIncrement());
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
        tex.upload();
        return new GpuTile(id, tex);
    }

    // ── タイルレンダリング（ワーカースレッド）────────────────────

    private int[] renderTile(TileCoord coord) {
        return coord.zoom() >= 1 ? renderTileHiRes(coord) : renderTileLowRes(coord);
    }

    /** zoom >= 1: 1ブロック = ppb×ppb ピクセル */
    private int[] renderTileHiRes(TileCoord coord) {
        int zoom = coord.zoom();
        int b    = TileCoord.blocksPerTile(zoom);
        int ppb  = TileCoord.pixelsPerBlock(zoom);
        int chunksPerSide = b / 16;

        int[] pixels = new int[TileCoord.TILE_PX * TileCoord.TILE_PX];
        int chunkX0 = coord.x() * chunksPerSide;
        int chunkZ0 = coord.z() * chunksPerSide;

        for (int dz = 0; dz < chunksPerSide; dz++) {
            for (int dx = 0; dx < chunksPerSide; dx++) {
                int cx = chunkX0 + dx;
                int cz = chunkZ0 + dz;
                int[] chunkColors = colorCache.get(ChunkPos.toLong(cx, cz));

                int basePixX = dx * 16 * ppb;
                int basePixZ = dz * 16 * ppb;

                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int color = (chunkColors != null)
                                ? chunkColors[lx + lz * 16]
                                : 0xFF000000;
                        int px = basePixX + lx * ppb;
                        int pz = basePixZ + lz * ppb;
                        for (int py = 0; py < ppb; py++) {
                            for (int ppx = 0; ppx < ppb; ppx++) {
                                pixels[(px + ppx) + (pz + py) * TileCoord.TILE_PX] = color;
                            }
                        }
                    }
                }
            }
        }
        return pixels;
    }

    /**
     * zoom <= 0: 複数ブロックを平均して 1ピクセル（ズームアウト用）。
     * bpp = blocksPerPixel(zoom)、ppc = 16 / bpp（チャンクあたりのピクセル数辺）
     */
    private int[] renderTileLowRes(TileCoord coord) {
        int zoom = coord.zoom();
        int bpp  = TileCoord.blocksPerPixel(zoom);
        int ppc  = 16 / bpp;
        int chunksPerSide = TileCoord.blocksPerTile(zoom) / 16;

        int[] pixels = new int[TileCoord.TILE_PX * TileCoord.TILE_PX];
        int chunkX0 = coord.x() * chunksPerSide;
        int chunkZ0 = coord.z() * chunksPerSide;

        for (int dz = 0; dz < chunksPerSide; dz++) {
            for (int dx = 0; dx < chunksPerSide; dx++) {
                int cx = chunkX0 + dx;
                int cz = chunkZ0 + dz;
                int[] chunkColors = colorCache.get(ChunkPos.toLong(cx, cz));

                int basePixX = dx * ppc;
                int basePixZ = dz * ppc;

                for (int pz = 0; pz < ppc; pz++) {
                    for (int px = 0; px < ppc; px++) {
                        int color = averageBlockColors(chunkColors, px * bpp, pz * bpp, bpp);
                        pixels[(basePixX + px) + (basePixZ + pz) * TileCoord.TILE_PX] = color;
                    }
                }
            }
        }
        return pixels;
    }

    private static int averageBlockColors(int[] chunkColors, int startX, int startZ, int size) {
        if (chunkColors == null) return 0xFF000000;
        long r = 0, g = 0, b = 0;
        int count = 0;
        int endX = Math.min(startX + size, 16);
        int endZ = Math.min(startZ + size, 16);
        for (int z = startZ; z < endZ; z++) {
            for (int x = startX; x < endX; x++) {
                int c = chunkColors[x + z * 16];
                r += (c >> 16) & 0xFF;
                g += (c >>  8) & 0xFF;
                b +=  c        & 0xFF;
                count++;
            }
        }
        if (count == 0) return 0xFF000000;
        return 0xFF000000 | ((int)(r / count) << 16) | ((int)(g / count) << 8) | (int)(b / count);
    }

    // ── GPU タイル（レンダースレッドのみ） ────────────────────────

    private record GpuTile(Identifier id, NativeImageBackedTexture texture) {
        void close() {
            MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
            texture.close();
        }
    }
}
