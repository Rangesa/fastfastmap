package jp.six.fastfastmap.client;

import jp.six.fastfastmap.map.FastMap;
import jp.six.fastfastmap.map.SlippyTileCache;
import jp.six.fastfastmap.map.TileCoord;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Mキーで開く全画面マップ（Slippy Map方式）。
 *
 * - 右クリックドラッグでパン
 * - スクロールホイールでズーム（マウス位置を中心に）
 * - スムーズズームアニメーション
 * - ズームレベルに応じて自動的に高解像タイルを使用（GoogleMap式LOD）
 * - 低解像フォールバック: 高解像タイル未準備時に低解像タイルで代替表示
 * - Rキーでプレイヤー位置に再センタリング
 */
@Environment(EnvType.CLIENT)
public class FullscreenMapScreen extends Screen {

    private static final int BG_COLOR     = 0xFF101010;
    private static final int BORDER_COLOR = 0xFF888888;
    private static final int PLAYER_COLOR = 0xFFFF3333;

    private static final float DEFAULT_VIEW_SIZE = 256f;  // 表示するブロック数
    private static final float MIN_VIEW_SIZE     = 16f;   // 最大ズームイン: 16ブロック幅
    private static final float MAX_VIEW_SIZE     = 1e9f;  // 実質無制限（zoom-3で4096blocks/tile）
    private static final float ZOOM_FACTOR       = 1.15f;
    private static final float ZOOM_LERP         = 0.18f;

    /** 表示中心のワールドブロック座標（サブブロック精度）*/
    private float viewCenterBlockX;
    private float viewCenterBlockZ;

    /** ズームのターゲット（表示するブロック幅）と現在値（アニメーション用）*/
    private float targetViewSize = DEFAULT_VIEW_SIZE;
    private float viewSize       = DEFAULT_VIEW_SIZE;

    private int mapX, mapY, mapSize;

    public FullscreenMapScreen() {
        super(Text.translatable("screen.fastfastmap.fullscreen_map"));
    }

    @Override
    protected void init() {
        super.init();
        mapSize = (int) (Math.min(this.width, this.height) * 0.90f);
        mapX    = (this.width  - mapSize) / 2;
        mapY    = (this.height - mapSize) / 2;
        centerOnPlayer();
    }

    // ── 描画 ─────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // スムーズズームアニメーション
        viewSize += (targetViewSize - viewSize) * ZOOM_LERP;
        if (Math.abs(viewSize - targetViewSize) < 0.2f) viewSize = targetViewSize;

        ctx.fill(0, 0, this.width, this.height, BG_COLOR);

        SlippyTileCache tiles = FastMap.getInstance().getTileCache();
        if (tiles == null) { super.render(ctx, mouseX, mouseY, delta); return; }

        // 表示パラメータ
        int zoom              = zoomForViewSize(viewSize);
        int b                 = TileCoord.blocksPerTile(zoom);
        float screenPxPerBlock = (float) mapSize / viewSize;
        float screenCenterX   = mapX + mapSize / 2.0f;
        float screenCenterZ   = mapY + mapSize / 2.0f;

        // 枠線（タイルより先に描く）
        ctx.fill(mapX - 1, mapY - 1, mapX + mapSize + 1, mapY + mapSize + 1, BORDER_COLOR);

        // マップエリアにクリッピング
        ctx.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);

        // 可視タイル範囲
        float blockLeft  = viewCenterBlockX - viewSize / 2.0f;
        float blockTop   = viewCenterBlockZ - viewSize / 2.0f;
        float blockRight = blockLeft + viewSize;
        float blockBot   = blockTop  + viewSize;

        int tileXMin = Math.floorDiv((int) Math.floor(blockLeft),  b);
        int tileXMax = Math.floorDiv((int) Math.floor(blockRight), b);
        int tileZMin = Math.floorDiv((int) Math.floor(blockTop),   b);
        int tileZMax = Math.floorDiv((int) Math.floor(blockBot),   b);

        for (int tz = tileZMin; tz <= tileZMax; tz++) {
            for (int tx = tileXMin; tx <= tileXMax; tx++) {
                TileCoord tc = new TileCoord(zoom, tx, tz);

                // タイルスクリーン座標
                int tsX = (int) (screenCenterX + (tx * b - viewCenterBlockX) * screenPxPerBlock);
                int tsZ = (int) (screenCenterZ + (tz * b - viewCenterBlockZ) * screenPxPerBlock);
                // +1 でサブピクセルギャップを防止
                int tsW = (int) (b * screenPxPerBlock) + 1;
                int tsH = (int) (b * screenPxPerBlock) + 1;

                // 最高解像度タイルを試みる
                Identifier texId = tiles.getOrRequest(tc);

                if (texId != null) {
                    drawTile(ctx, texId, tsX, tsZ, tsW, tsH, 0, 0, TileCoord.TILE_PX);
                } else {
                    // フォールバック: 低解像タイルの一部を表示
                    int[] uvOut = new int[3]; // [uStart, vStart, regionSize]
                    Identifier fbId = tiles.getFallback(tc, uvOut);
                    if (fbId != null) {
                        drawTile(ctx, fbId, tsX, tsZ, tsW, tsH, uvOut[0], uvOut[1], uvOut[2]);
                    }
                    // fbId == null → 黒背景のまま（チャンク未ロード）
                }
            }
        }

        ctx.disableScissor();

        // プレイヤードット
        drawPlayerDot(ctx, screenCenterX, screenCenterZ, screenPxPerBlock);

        // 操作ヒント
        ctx.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.translatable("screen.fastfastmap.close_hint"),
                this.width / 2,
                mapY + mapSize + 6,
                0xAAFFFFFF
        );

        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawTile(DrawContext ctx, Identifier texId,
                          int screenX, int screenZ, int screenW, int screenH,
                          int uStart, int vStart, int regionSize) {
        ctx.drawTexture(
                RenderPipelines.GUI_TEXTURED,
                texId,
                screenX, screenZ,
                uStart, vStart,
                screenW, screenH,
                regionSize, regionSize,
                TileCoord.TILE_PX, TileCoord.TILE_PX
        );
    }

    private void drawPlayerDot(DrawContext ctx, float screenCenterX, float screenCenterZ,
                               float screenPxPerBlock) {
        FastMap map = FastMap.getInstance();
        long exactPacked = map.getPlayerExactPos();
        float playerX = Float.intBitsToFloat((int) (exactPacked & 0xFFFFFFFFL));
        float playerZ = Float.intBitsToFloat((int) (exactPacked >>> 32));

        int dotX = (int) (screenCenterX + (playerX - viewCenterBlockX) * screenPxPerBlock);
        int dotZ = (int) (screenCenterZ + (playerZ - viewCenterBlockZ) * screenPxPerBlock);

        if (dotX >= mapX && dotX < mapX + mapSize && dotZ >= mapY && dotZ < mapY + mapSize) {
            ctx.fill(dotX - 3, dotZ - 3, dotX + 3, dotZ + 3, PLAYER_COLOR);
            ctx.fill(dotX - 1, dotZ - 1, dotX + 1, dotZ + 1, 0xFFFFFFFF);
        }
    }

    // ── マウス操作 ───────────────────────────────────────

    @Override
    public boolean mouseDragged(Click ctx, double deltaX, double deltaY) {
        if (ctx.button() == 1) { // 右クリックドラッグ
            float blocksPerPx = viewSize / mapSize;
            viewCenterBlockX -= (float) deltaX * blocksPerPx;
            viewCenterBlockZ -= (float) deltaY * blocksPerPx;
            return true;
        }
        return super.mouseDragged(ctx, deltaX, deltaY);
    }

    /**
     * スクロールホイールでズーム。
     * マウス下のブロックがズーム前後で同じ画面位置に留まるよう viewCenter を調整する。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (verticalAmount == 0) return false;

        float screenCenterX = mapX + mapSize / 2.0f;
        float screenCenterZ = mapY + mapSize / 2.0f;

        // マウス下のワールドブロック座標
        float blockUnderMouseX = viewCenterBlockX + ((float) mouseX - screenCenterX) * viewSize / mapSize;
        float blockUnderMouseZ = viewCenterBlockZ + ((float) mouseY - screenCenterZ) * viewSize / mapSize;

        // ターゲットviewSizeを変更
        float prevTarget = targetViewSize;
        if (verticalAmount > 0) {
            targetViewSize = Math.max(MIN_VIEW_SIZE, targetViewSize / ZOOM_FACTOR);
        } else {
            targetViewSize = Math.min(MAX_VIEW_SIZE, targetViewSize * ZOOM_FACTOR);
        }

        // マウス下のブロックがターゲットズーム後も同じ画面位置に来るよう中心を補正
        // 条件: viewCenter' + (mouseRel) * targetViewSize / mapSize = blockUnderMouse
        if (targetViewSize != prevTarget) {
            float relX = ((float) mouseX - screenCenterX) / mapSize;
            float relZ = ((float) mouseY - screenCenterZ) / mapSize;
            viewCenterBlockX = blockUnderMouseX - relX * targetViewSize;
            viewCenterBlockZ = blockUnderMouseZ - relZ * targetViewSize;
        }

        return true;
    }

    // ── キー操作 ─────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyInput key) {
        if (FastfastmapClient.fullscreenMapKey != null
                && FastfastmapClient.fullscreenMapKey.matchesKey(key)) {
            this.close();
            return true;
        }
        if (key.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_R) {
            centerOnPlayer();
            targetViewSize = DEFAULT_VIEW_SIZE;
            return true;
        }
        return super.keyPressed(key);
    }

    // ── ユーティリティ ────────────────────────────────────

    private void centerOnPlayer() {
        FastMap map = FastMap.getInstance();
        long exactPacked = map.getPlayerExactPos();
        viewCenterBlockX = Float.intBitsToFloat((int) (exactPacked & 0xFFFFFFFFL));
        viewCenterBlockZ = Float.intBitsToFloat((int) (exactPacked >>> 32));
    }

    /**
     * viewSize（表示ブロック数）から最適なズームレベルを決定する。
     * 各レベルで表示されるタイル数が適切な範囲（2〜6枚程度）になるよう設定。
     */
    /**
     * viewSize（表示するブロック幅）から最適なズームレベルを決定する。
     * 各レベルで画面に表示されるタイル数が 2〜6 枚程度になるよう閾値を設定。
     *
     * ズームアウト方向（zoom <= 0）はブロック平均化タイルを使用するため、
     * 未踏エリアは黒だがローカル周辺のみ表示する運用でも快適に動作する。
     */
    private static int zoomForViewSize(float viewSize) {
        // ── ズームアウト（低解像） ──
        if (viewSize > 6144) return -3; // 16blocks/px, 4096blocks/tile
        if (viewSize > 3072) return -2; //  8blocks/px, 2048blocks/tile
        if (viewSize > 1536) return -1; //  4blocks/px, 1024blocks/tile
        if (viewSize >  768) return  0; //  2blocks/px,  512blocks/tile
        // ── ズームイン（高解像） ──
        if (viewSize >  384) return  1; //  1px/block,  256blocks/tile
        if (viewSize >  192) return  2; //  2px/block,  128blocks/tile
        if (viewSize >   96) return  3; //  4px/block,   64blocks/tile
        if (viewSize >   48) return  4; //  8px/block,   32blocks/tile
        return 5;                       // 16px/block,   16blocks/tile (1チャンク=1タイル)
    }

    @Override
    public boolean shouldPause() { return false; }
}
