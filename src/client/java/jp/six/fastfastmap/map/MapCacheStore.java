package jp.six.fastfastmap.map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * チャンクカラーキャッシュのディスク永続化。
 *
 * 保存先: config/fastfastmap/cache/<key>/chunks.dat
 *   key = "sp_<ワールド名>"  (シングルプレイ)
 *       = "mp_<サーバーアドレス>" (マルチプレイ)
 *
 * ファイルフォーマット:
 *   [4 bytes] magic = 0x46464D43 ("FFMC")
 *   [4 bytes] version = 1
 *   [4 bytes] chunk count N
 *   N × { [8 bytes] chunkKey (long)
 *          [256 × 4 bytes] ARGB colors (int[256]) }
 */
@Environment(EnvType.CLIENT)
public class MapCacheStore {

    private static final Path BASE_DIR = FabricLoader.getInstance()
            .getConfigDir().resolve("fastfastmap").resolve("cache");

    private static final int MAGIC   = 0x46464D43; // "FFMC"
    private static final int VERSION = 1;

    // ── キャッシュキー生成 ───────────────────────────────────────────

    /**
     * 現在の接続先に対応するキャッシュキーを返す。
     * シングルプレイはワールド名、マルチプレイはサーバーアドレスで識別。
     */
    public static String getCacheKey(MinecraftClient client) {
        if (client.isInSingleplayer() && client.getServer() != null) {
            String levelName = client.getServer().getSaveProperties().getLevelName();
            return "sp_" + sanitize(levelName);
        }
        ServerInfo info = client.getCurrentServerEntry();
        return "mp_" + sanitize(info != null ? info.address : "unknown");
    }

    // ── 保存 ────────────────────────────────────────────────────────

    /**
     * colorCache をディスクに書き込む。ワーカースレッドから呼ぶ。
     * 失敗しても例外をスローせず無視する（次回起動時に再スキャンで補完される）。
     */
    public static void save(String key, ConcurrentHashMap<Long, int[]> colorCache) {
        if (colorCache.isEmpty()) return;
        Path file = BASE_DIR.resolve(key).resolve("chunks.dat");
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(file)))) {
                // スナップショット的にコピーしてからイテレート
                var snapshot = new java.util.ArrayList<>(colorCache.entrySet());
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(snapshot.size());
                for (java.util.Map.Entry<Long, int[]> entry : snapshot) {
                    out.writeLong(entry.getKey());
                    for (int c : entry.getValue()) out.writeInt(c);
                }
            }
        } catch (IOException ignored) {
            // 保存失敗は無視
        }
    }

    // ── 読み込み ─────────────────────────────────────────────────────

    /**
     * ディスクから colorCache に読み込む。メインスレッドから呼ぶ。
     * ファイルが存在しない場合や破損している場合は何もしない。
     */
    public static void load(String key, ConcurrentHashMap<Long, int[]> colorCache) {
        Path file = BASE_DIR.resolve(key).resolve("chunks.dat");
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC)   return; // 不正ファイル
            if (in.readInt() != VERSION) return; // バージョン不一致
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long chunkKey = in.readLong();
                int[] colors = new int[256];
                for (int j = 0; j < 256; j++) colors[j] = in.readInt();
                colorCache.put(chunkKey, colors);
            }
        } catch (IOException ignored) {
            // 読み込み失敗は無視（再スキャンで補完される）
        }
    }

    // ── ユーティリティ ────────────────────────────────────────────────

    /** ファイルシステムで安全な文字のみに変換する。 */
    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9._\\-]", "_").toLowerCase();
    }
}
