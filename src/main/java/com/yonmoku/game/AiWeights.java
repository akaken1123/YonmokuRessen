package com.yonmoku.game;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 評価関数（evaluateWeighted系）の各項の重み。学習AI（AiLevel.LEARN）が使う。
 * defaults()の値は、DEFAULT/TEST/TEST2/TEST3が使う固定の評価関数（evaluate/heuristicScore/lineWeight）
 * にもともと埋め込まれていた定数と同じで、ここから自己対戦を通じて少しずつ調整されていく想定。
 */
public record AiWeights(
        double hpWeight,          // HP差1点あたりの価値
        double pendingWeight,     // 保留ダメージ1点あたりの見込み価値
        double boardScoreWeight,  // 盤面ポテンシャル(heuristicScoreの合計)の重み
        double centerWeight,      // 盤の中央に近いマスへのボーナス
        double dmgMarkBonus,      // ダメージ増加マークが乗ったマスへのボーナス
        double echoMarkBonus,     // 除外あとマークが乗ったマスへのボーナス
        double openTwoFactor,     // 両端が開いているラインの倍率
        double openOneFactor,     // 片端だけ開いているラインの倍率
        double closedFactor       // 両端とも塞がっているラインの倍率
) {
    static AiWeights defaults() {
        return new AiWeights(50.0, 8.0, 0.02, 0.6, 3.0, 1.5, 1.0, 0.45, 0.12);
    }

    /** 各項を±15%程度ランダムに揺らした新しい重みセットを返す（自己対戦での学習の1世代分の変異）。 */
    AiWeights mutated(ThreadLocalRandom random) {
        return new AiWeights(
                jitter(hpWeight, random),
                jitter(pendingWeight, random),
                jitter(boardScoreWeight, random),
                jitter(centerWeight, random),
                jitter(dmgMarkBonus, random),
                jitter(echoMarkBonus, random),
                jitter(openTwoFactor, random),
                jitter(openOneFactor, random),
                jitter(closedFactor, random)
        );
    }

    private static double jitter(double value, ThreadLocalRandom random) {
        return value * (0.85 + random.nextDouble() * 0.30); // 0.85〜1.15倍
    }
}
