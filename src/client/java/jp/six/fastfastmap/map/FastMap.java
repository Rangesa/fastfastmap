package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FastFastMap のメインコーディネーター。
 *
 * ─ アンカーベーステクスチャ（HUDミニマップ用）──────────────────────
 * テクスチャ(512x512)はワールド座標に固定したアンカーチャンクを基準に描画。
 * プレイヤーの移動はテクスチャを書き換えず、renderer に渡す UV オフセットだけで表現。
 * アンカーからプレイヤーが REANCHOR_THRESHOLD チャンク以上離れたら再アンカー。
 *
 * ─ Slippy Mapタイルキャッシュ（全画面マップ用）────────────────────
 * ワールド座標固定のタイル（zoom 1〜5、256×256px）を非同期レンダリング。
 * チャンク更新時に該当タイルのみ無効化・再レンダリング。
 *
 * ─ スレッドモデル ───────────────────────────────────────────────
 *   メインスレッド  : イベント受信 → scanExecutor に投げる。プレイヤー位置追跡。
 *   ワーカースレッド : scanChunk（水面透過・マルチ方向シェーディング込み）+ タイルレンダリング
 *   レンダースレッド : pendingUploads 処理 → NativeImage → GPU。
 */
@Environment(EnvType.CLIENT)
public class FastMap {

    private static final FastMap INSTANCE = new FastMap();
    public static FastMap getInstance() { return INSTANCE; }

    private static final int REANCHOR_THRESHOLD = 8;
    private static final int VISIBLE_RANGE      = MinimapTexture.SIZE / 16 / 2; // 16 chunks
    private static final long RESCAN_COOLDOWN_MS = 1000L;

    // チャンク座標(long) → ARGB色配列[256]（全機能で共有）
    private final ConcurrentHashMap<Long, int[]> colorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long>  lastScanTime = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long>    pendingUploads = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean                  pendingClear   = new AtomicBoolean(false);

    /**
     * ワールド切り替え時にインクリメントする世代カウンタ。
     * スキャンタスクは投入時の世代をキャプチャし、コミット前に照合することで
     * ワールド切り替え後の古い結果によるキャッシュ汚染を防ぐ。
     */
    private final AtomicInteger worldGeneration = new AtomicInteger(0);

    private final ExecutorService scanExecutor = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            r -> { Thread t = new Thread(r, "fastfastmap-scan"); t.setDaemon(true); return t; }
    );

    // HUDミニマップ用
    private MinimapTexture  texture;
    private MinimapRenderer renderer;

    // 全画面Slippy Mapタイル用
    private SlippyTileCache tileCache;

    private volatile long  playerChunkPos = ChunkPos.toLong(0, 0);
    private volatile long  playerExactPos = 0L;
    private volatile long  anchorChunkPos = ChunkPos.toLong(0, 0);
    private volatile float playerYaw      = 0f;

    /** 現在セッションのキャッシュキー（null = セッション外）。 */
    private String currentCacheKey = null;
    /** キャッシュが変更されたかどうか（自動保存の判定用）。 */
    private final AtomicBoolean cacheModified = new AtomicBoolean(false);
    private static final int AUTO_SAVE_TICKS = 1200; // 60秒 @ 20TPS
    private int autoSaveTicks = 0;

    private FastMap() {}

    // ---- メインスレッドから呼ばれる ----

    public void onChunkLoad(ClientWorld world, WorldChunk chunk) {
        scheduleScan(world, chunk);
    }

    public void onBlockUpdate(ClientWorld world, BlockPos pos) {
        WorldChunk chunk = world.getWorldChunk(pos);
        if (chunk == null) return;
        long key = chunk.getPos().toLong();
        long now = System.currentTimeMillis();
        Long last = lastScanTime.get(key);
        if (last != null && now - last < RESCAN_COOLDOWN_MS) return;
        scheduleScan(world, chunk);
    }

    private void scheduleScan(ClientWorld world, WorldChunk chunk) {
        long key = chunk.getPos().toLong();
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        lastScanTime.put(key, System.currentTimeMillis());
        int generation = worldGeneration.get(); // 投入時の世代をキャプチャ
        scanExecutor.submit(() -> {
            int[] colors = scanChunk(world, chunk);
            // ワールド切り替えが発生していたら結果を捨てる
            if (worldGeneration.get() != generation) return;
            colorCache.put(key, colors);
            pendingUploads.offer(key);
            cacheModified.set(true);
            // Slippy Mapタイルを無効化（colorCacheへのput後に実行）
            SlippyTileCache tc = tileCache;
            if (tc != null) tc.invalidateChunk(cx, cz);
        });
    }

    public void onChunkUnload(ChunkPos pos) {
        // colorCache は削除しない。
        // アンロードされたチャンクの色データを保持し続けることで、
        // 全画面マップのズームアウト表示やミニマップ再アンカー時の再描画に使える。
        // ワールド離脱時に colorCache ごとクリアされる。
        lastScanTime.remove(pos.toLong());
    }

    public void onTick(MinecraftClient client) {
        if (texture == null && client.world != null) {
            // ディスクキャッシュを読み込む（ワールド/サーバー別）
            currentCacheKey = MapCacheStore.getCacheKey(client);
            MapCacheStore.load(currentCacheKey, colorCache);
            cacheModified.set(false);
            autoSaveTicks = 0;

            texture = new MinimapTexture();
            renderer = new MinimapRenderer(texture);
            tileCache = new SlippyTileCache(colorCache, scanExecutor);
            if (client.player != null) {
                ChunkPos cp = client.player.getChunkPos();
                long pos = ChunkPos.toLong(cp.x, cp.z);
                playerChunkPos = pos;
                anchorChunkPos = pos;
                packExactPos(client.player.getX(), client.player.getZ());
            }
        }

        if (texture != null && client.world == null) {
            worldGeneration.incrementAndGet(); // 飛行中スキャンタスクの結果を無効化
            // ディスクキャッシュに保存（変更があった場合のみ）
            if (currentCacheKey != null && cacheModified.get()) {
                String key = currentCacheKey;
                ConcurrentHashMap<Long, int[]> snapshot = new ConcurrentHashMap<>(colorCache);
                scanExecutor.submit(() -> MapCacheStore.save(key, snapshot));
            }
            currentCacheKey = null;
            texture.close();
            texture = null;
            renderer = null;
            tileCache.clear();
            tileCache = null;
            colorCache.clear();
            lastScanTime.clear();
            pendingUploads.clear();
            pendingClear.set(false);
        }

        // 定期自動保存（60秒ごと、変更があった場合のみ）
        if (currentCacheKey != null && ++autoSaveTicks >= AUTO_SAVE_TICKS) {
            autoSaveTicks = 0;
            if (cacheModified.compareAndSet(true, false)) {
                String key = currentCacheKey;
                ConcurrentHashMap<Long, int[]> snapshot = new ConcurrentHashMap<>(colorCache);
                scanExecutor.submit(() -> MapCacheStore.save(key, snapshot));
            }
        }

        if (client.player != null) {
            playerYaw = client.player.getYaw();
            packExactPos(client.player.getX(), client.player.getZ());

            ChunkPos cp = client.player.getChunkPos();
            long newPos = ChunkPos.toLong(cp.x, cp.z);
            if (newPos != playerChunkPos) {
                playerChunkPos = newPos;
                long anchor = anchorChunkPos;
                int acx = ChunkPos.getPackedX(anchor);
                int acz = ChunkPos.getPackedZ(anchor);
                if (Math.abs(cp.x - acx) > REANCHOR_THRESHOLD
                        || Math.abs(cp.z - acz) > REANCHOR_THRESHOLD) {
                    anchorChunkPos = newPos;
                    pendingClear.set(true);
                }
            }
        }
    }

    private void packExactPos(double x, double z) {
        float px = (float) x;
        float pz = (float) z;
        playerExactPos = (Float.floatToRawIntBits(px) & 0xFFFFFFFFL)
                | ((long) Float.floatToRawIntBits(pz) << 32);
    }

    // ---- レンダースレッドから呼ばれる ----

    public void onHudRender(DrawContext ctx) {
        if (texture == null || renderer == null) return;

        long exactPacked = playerExactPos;
        float playerX = Float.intBitsToFloat((int) (exactPacked & 0xFFFFFFFFL));
        float playerZ = Float.intBitsToFloat((int) (exactPacked >>> 32));

        long anchorPacked = anchorChunkPos;
        int acx = ChunkPos.getPackedX(anchorPacked);
        int acz = ChunkPos.getPackedZ(anchorPacked);

        boolean needsUpload = false;

        if (pendingClear.compareAndSet(true, false)) {
            texture.clear();
            colorCache.forEach((key, colors) -> {
                ChunkPos pos = new ChunkPos(key);
                if (Math.abs(pos.x - acx) <= VISIBLE_RANGE
                        && Math.abs(pos.z - acz) <= VISIBLE_RANGE) {
                    texture.writeChunk(pos.x, pos.z, colors, acx, acz);
                }
            });
            needsUpload = true;
        }

        Long key;
        while ((key = pendingUploads.poll()) != null) {
            int[] colors = colorCache.get(key);
            if (colors != null) {
                ChunkPos pos = new ChunkPos(key);
                texture.writeChunk(pos.x, pos.z, colors, acx, acz);
                needsUpload = true;
            }
        }

        if (needsUpload) texture.upload();

        float uvDeltaX = playerX - acx * 16.0f;
        float uvDeltaZ = playerZ - acz * 16.0f;
        renderer.render(ctx, uvDeltaX, uvDeltaZ);
    }

    // ---- 公開 API ----

    public Identifier getTextureId()      { return texture   != null ? texture.getId()   : null; }
    public SlippyTileCache getTileCache() { return tileCache; }
    public long  getPlayerExactPos()      { return playerExactPos; }
    public long  getAnchorChunkPos()      { return anchorChunkPos; }
    public float getPlayerYaw()           { return playerYaw; }

    // ---- チャンクスキャン（ワーカースレッド）----

    /**
     * チャンクの各列を走査して ARGB 色配列を返す。
     *
     * 品質向上:
     *   - 水面透過（bathymetry）: 水の下の地形を水色でブレンド + 深さで暗化
     *   - 連続シェーディング: N/NW/W 3方向平均で高さ差→連続明度係数
     *   - バイオームティント: 草・葉・水の色をバイオーム別に適用
     */
    private static int[] scanChunk(ClientWorld world, WorldChunk chunk) {
        int[] colors = new int[256];
        int[] topY   = new int[256];

        ChunkPos chunkPos = chunk.getPos();
        int cx = chunkPos.x;
        int cz = chunkPos.z;

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        BlockColors blockColors = MinecraftClient.getInstance().getBlockColors();

        // 第1パス: WORLD_SURFACE 高さマップ取得
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                topY[x + z * 16] = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
            }
        }

        // 第2パス: 色計算
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int idx      = x + z * 16;
                int surfaceY = topY[idx];

                mutable.set(cx * 16 + x, surfaceY, cz * 16 + z);
                BlockState state = chunk.getBlockState(mutable);

                // ── 水面透過（水のみ、溶岩は除外）──────────────
                int waterDepth = 0;
                int waterArgb  = -1;

                FluidState fs = state.getFluidState();
                boolean isWater = fs.isOf(Fluids.WATER) || fs.isOf(Fluids.FLOWING_WATER);
                if (isWater) {
                    int tint = blockColors.getColor(state, world, mutable, 0);
                    waterArgb = (tint != -1) ? (tint | 0xFF000000) : 0xFF3F76E4;

                    for (int d = 1; d <= 32; d++) {
                        mutable.setY(surfaceY - d);
                        BlockState below = chunk.getBlockState(mutable);
                        if (below.getFluidState().isEmpty() && !below.isAir()) {
                            state = below;
                            waterDepth = d;
                            break;
                        }
                    }
                    if (waterDepth == 0) {
                        waterDepth = 16;
                        state = chunk.getBlockState(mutable.setY(surfaceY));
                    }
                }

                // ── MapColor + 連続シェーディング ─────────────
                MapColor mapColor = state.getMapColor(chunk, mutable);

                int northY = (z > 0)            ? topY[x       + (z - 1) * 16] : surfaceY;
                int westY  = (x > 0)            ? topY[(x - 1) +  z      * 16] : surfaceY;
                int nwY    = (x > 0 && z > 0)   ? topY[(x - 1) + (z - 1) * 16] : surfaceY;
                float avgNeighbor = (northY + westY + nwY) / 3.0f;

                float heightDiff  = surfaceY - avgNeighbor;
                float shadeFactor = Math.max(0.75f, Math.min(1.20f, 1.0f + heightDiff * 0.025f));

                int argb = mapColor.getRenderColor(MapColor.Brightness.NORMAL);
                int sr = Math.min(255, (int)(((argb >> 16) & 0xFF) * shadeFactor));
                int sg = Math.min(255, (int)(((argb >>  8) & 0xFF) * shadeFactor));
                int sb = Math.min(255, (int)((argb         & 0xFF) * shadeFactor));
                argb = (argb & 0xFF000000) | (sr << 16) | (sg << 8) | sb;

                // ── バイオームティント（草・葉など）──────────
                int tint = blockColors.getColor(state, world, mutable, 0);
                if (tint != -1) {
                    argb = multiplyColors(argb, tint | 0xFF000000);
                }

                // ── 水ブレンド（深さ依存）────────────────────
                if (waterArgb != -1) {
                    float alpha = Math.min(0.85f, 0.35f + waterDepth * 0.05f);
                    float dark  = Math.max(0.25f, 1.0f  - waterDepth * 0.045f);

                    int bR = (argb      >> 16) & 0xFF;
                    int bG = (argb      >>  8) & 0xFF;
                    int bB =  argb             & 0xFF;
                    int wR = (waterArgb >> 16) & 0xFF;
                    int wG = (waterArgb >>  8) & 0xFF;
                    int wB =  waterArgb        & 0xFF;

                    int r = Math.min(255, (int)((wR * alpha + bR * (1 - alpha)) * dark));
                    int g = Math.min(255, (int)((wG * alpha + bG * (1 - alpha)) * dark));
                    int b = Math.min(255, (int)((wB * alpha + bB * (1 - alpha)) * dark));
                    argb = 0xFF000000 | (r << 16) | (g << 8) | b;
                }

                colors[idx] = argb;
            }
        }

        return colors;
    }

    private static int multiplyColors(int base, int tint) {
        int a = ((base >> 24) & 0xFF);
        int r = ((base >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255;
        int g = ((base >>  8) & 0xFF) * ((tint >>  8) & 0xFF) / 255;
        int b = ( base        & 0xFF) * ( tint        & 0xFF) / 255;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
