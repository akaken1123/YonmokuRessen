package com.yonmoku.game;

/** 1回の学習バッチ（自己対戦N局）の結果。 */
public record TrainingSummary(
        int generation,
        int games,
        int challengerWins,
        int championWins,
        int draws,
        double challengerScore,
        boolean promoted,
        AiWeights currentWeights
) {
}
