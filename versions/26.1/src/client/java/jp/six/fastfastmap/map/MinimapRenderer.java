package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

@Environment(EnvType.CLIENT)
public class MinimapRenderer {

    public static final int DISPLAY_SIZE = 160;
    private static final int MARGIN      = 10;
    private static final int BORDER      = 1;

    private static final int BORDER_COLOR = 0xFF555555;
    private static final int ARROW_WHITE  = 0xFFFFFFFF;
    private static final int ARROW_RED    = 0xFFFF3333;

    static final float UV_BASE = (MinimapTexture.SIZE - DISPLAY_SIZE) / 2.0f;

    private final MinimapTexture texture;

    public MinimapRenderer(MinimapTexture texture) {
        this.texture = texture;
    }

    public void render(GuiGraphicsExtractor ctx, float uvDeltaX, float uvDeltaZ) {
        Minecraft client = Minecraft.getInstance();
        int windowW = client.getWindow().getGuiScaledWidth();
        int windowH = client.getWindow().getGuiScaledHeight();

        // 右下に配置
        int screenX = windowW - DISPLAY_SIZE - MARGIN;
        int screenY = windowH - DISPLAY_SIZE - MARGIN;

        float maxOffset = MinimapTexture.SIZE - DISPLAY_SIZE;
        float uStart = Math.max(0, Math.min(maxOffset, UV_BASE + uvDeltaX));
        float vStart = Math.max(0, Math.min(maxOffset, UV_BASE + uvDeltaZ));

        // 枠線
        ctx.fill(
                screenX - BORDER, screenY - BORDER,
                screenX + DISPLAY_SIZE + BORDER, screenY + DISPLAY_SIZE + BORDER,
                BORDER_COLOR
        );

        // マップテクスチャ
        ctx.blit(
                RenderPipelines.GUI_TEXTURED,
                texture.getId(),
                screenX, screenY,
                uStart, vStart,
                DISPLAY_SIZE, DISPLAY_SIZE,
                MinimapTexture.SIZE, MinimapTexture.SIZE
        );

        // プレイヤー方向矢印（マップ中央 = プレイヤー位置）
        int cx = screenX + DISPLAY_SIZE / 2;
        int cy = screenY + DISPLAY_SIZE / 2;
        drawPlayerArrow(ctx, cx, cy, FastMap.getInstance().getPlayerYaw());
    }

    /**
     * プレイヤーの向きを示す矢印をマトリクス変換なしで描画する。
     *
     * Minecraft yaw: 0=south(+Z=下), 90=west(-X=左), 180=north(-Z=上), -90=east(+X=右)
     * マップは北が上なので:
     *   dirX = -sin(yaw)  (+east = +right on map)
     *   dirZ = +cos(yaw)  (+south = +down on map)
     *
     * 矢印の構成:
     *   先端 (tip)    : center + dir * 9
     *   左基点 (base1): center + dir * 2 + perp * 4
     *   右基点 (base2): center + dir * 2 - perp * 4
     */
    private static void drawPlayerArrow(GuiGraphicsExtractor ctx, int cx, int cy, float yaw) {
        double rad = Math.toRadians(yaw);
        float dirX  = -(float) Math.sin(rad);
        float dirZ  =  (float) Math.cos(rad);
        float perpX = -dirZ;   // 方向に直交するベクトル
        float perpZ =  dirX;

        int tx  = cx + Math.round(dirX * 9);
        int tz  = cy + Math.round(dirZ * 9);
        int b1x = cx + Math.round(dirX * 2 + perpX * 4);
        int b1z = cy + Math.round(dirZ * 2 + perpZ * 4);
        int b2x = cx + Math.round(dirX * 2 - perpX * 4);
        int b2z = cy + Math.round(dirZ * 2 - perpZ * 4);

        // 白アウトライン（先にエッジを白で描く）
        drawLine(ctx, tx, tz, b1x, b1z, ARROW_WHITE);
        drawLine(ctx, tx, tz, b2x, b2z, ARROW_WHITE);
        drawLine(ctx, b1x, b1z, b2x, b2z, ARROW_WHITE);

        // 赤フィル（base辺上の各点から先端へスキャンライン）
        for (int i = 0; i <= 10; i++) {
            float t  = i / 10.0f;
            int  sx  = Math.round(b1x + (b2x - b1x) * t);
            int  sz  = Math.round(b1z + (b2z - b1z) * t);
            drawLine(ctx, sx, sz, tx, tz, ARROW_RED);
        }

        // プレイヤードット（中心に白縁・赤丸）
        ctx.fill(cx - 2, cy - 2, cx + 3, cy + 3, ARROW_WHITE);
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, ARROW_RED);
    }

    /** Bresenhamの直線アルゴリズムで1pxの線を描く */
    private static void drawLine(GuiGraphicsExtractor ctx, int x1, int y1, int x2, int y2, int color) {
        int dx =  Math.abs(x2 - x1), sx = x1 < x2 ? 1 : -1;
        int dy = -Math.abs(y2 - y1), sy = y1 < y2 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            ctx.fill(x1, y1, x1 + 1, y1 + 1, color);
            if (x1 == x2 && y1 == y2) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x1 += sx; }
            if (e2 <= dx) { err += dx; y1 += sy; }
        }
    }
}
