package com.yonmoku.game;

import java.util.Map;
import java.util.Set;

/**
 * 盤面をニューラルネット入力用の13チャンネルテンソル(NCHW: 1x13xSIZExSIZE)に変換する。
 * YonmokuRessen-Neural-Networkリポジトリの yonmoku_nn/encoding.py と、チャンネルの意味・
 * 並び順を完全に一致させる必要がある（ここが食い違うと、学習済みモデルの出力は意味を持たない）。
 *
 * チャンネル一覧：
 *  0: 自分の石                1: 自分の石(ダメージ増加マーク付き)
 *  2: 相手の石                3: 相手の石(ダメージ増加マーク付き)
 *  4: 自分の石(バックアタック補正あり)  5: 相手の石(バックアタック補正あり)
 *  6: 空きマスのダメージ増加マーク     7: 空きマスの除外あとマーク
 *  8: 空きマスの除外あとマーク(マーク付き＝+2相当)
 *  9: 自分のHP/6(定数面)      10: 相手のHP/6(定数面)
 * 11: 自分への保留ダメージ量/10(定数面) 12: 相手への保留ダメージ量/10(定数面)
 */
final class NeuralEncoder {

    static final int NUM_PLANES = 13;
    private static final double MAX_HP = 6.0;
    private static final double PENDING_NORMALIZER = 10.0;

    private NeuralEncoder() {
    }

    /** perspective視点（自分の色）で正規化した入力テンソル。長さ NUM_PLANES*size*size のfloat配列。 */
    static float[] encode(GameStateSnapshot state, String perspective) {
        return encode(state.board(), state.dmgMarks(), state.removalEchoes(), state.hp(), state.pending(),
                perspective);
    }

    /**
     * 上と同じ変換を、GameStateSnapshotではなく生のフィールドから行う版。NeuralMcts（対局時の先読み）は
     * 実対局のインスタンスを介さない仮の局面（SimBoard）を大量に扱うため、こちらを使う。
     */
    static float[] encode(Stone[][] board, Set<String> dmgMarks, Map<String, Boolean> removalEchoes,
                          Map<String, Integer> hp, Pending pending, String perspective) {
        String opponent = "B".equals(perspective) ? "W" : "B";
        int size = board.length;

        float[] planes = new float[NUM_PLANES * size * size];

        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                Stone cell = board[r][c];
                if (cell != null) {
                    boolean isOwn = cell.color().equals(perspective);
                    set(planes, size, isOwn ? 0 : 2, r, c, 1f);
                    if (cell.dmgFlag()) {
                        set(planes, size, isOwn ? 1 : 3, r, c, 1f);
                    }
                    if (cell.backAttackBonus() > 0) {
                        set(planes, size, isOwn ? 4 : 5, r, c, 1f);
                    }
                } else {
                    String k = r + "," + c;
                    if (dmgMarks.contains(k)) {
                        set(planes, size, 6, r, c, 1f);
                    }
                    if (removalEchoes.containsKey(k)) {
                        set(planes, size, 7, r, c, 1f);
                        if (Boolean.TRUE.equals(removalEchoes.get(k))) {
                            set(planes, size, 8, r, c, 1f);
                        }
                    }
                }
            }
        }

        float ownHp = (float) (hp.getOrDefault(perspective, 6) / MAX_HP);
        float oppHp = (float) (hp.getOrDefault(opponent, 6) / MAX_HP);
        fill(planes, size, 9, ownHp);
        fill(planes, size, 10, oppHp);

        if (pending != null) {
            float amount = (float) (pending.amount() / PENDING_NORMALIZER);
            if (pending.target().equals(perspective)) {
                fill(planes, size, 11, amount);
            } else {
                fill(planes, size, 12, amount);
            }
        }

        return planes;
    }

    private static void set(float[] planes, int size, int plane, int r, int c, float value) {
        planes[plane * size * size + r * size + c] = value;
    }

    private static void fill(float[] planes, int size, int plane, float value) {
        int base = plane * size * size;
        for (int i = 0; i < size * size; i++) {
            planes[base + i] = value;
        }
    }
}
