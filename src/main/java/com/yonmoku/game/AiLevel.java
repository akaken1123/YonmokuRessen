package com.yonmoku.game;

/**
 * AI対戦で使う探索の強さ。DEFAULT は2手先読み（安定版）、TEST は3手先読み、TEST2 は5手先読みの
 * 実験版（いずれも各深さの候補手数を固定した全展開ミニマックス）。TEST3 はアルファベータ枝刈り＋
 * 反復深化で、持ち時間（1手あたり約1.2秒）の許す限りできるだけ深く読む実験版。
 * LEARN は評価関数の重み（AiWeights）を自己対戦で少しずつ調整していく学習AI。探索の深さはDEFAULTと
 * 同じ（2手先読み）だが、評価関数の各項の重みがLearningServiceによる自己対戦の結果を反映して変化する。
 * NEURAL は、別リポジトリ（YonmokuRessen-Neural-Network）でPyTorchにより学習しONNX形式で
 * 書き出したニューラルネットワークの方策ヘッドをそのまま使う（探索は行わず、盤面を読み込んで
 * 出力されたロジットが最大のマスへ着手する）。モデルファイルが設定されていない場合は選択できない。
 * 新しいAIの調整はまずTEST系に入れ、十分検証できてからDEFAULTに昇格させる想定。
 */
public enum AiLevel {
    DEFAULT,
    TEST,
    TEST2,
    TEST3,
    LEARN,
    NEURAL;

    static AiLevel fromParam(String raw) {
        if (raw == null) return DEFAULT;
        String v = raw.trim().toUpperCase();
        if ("TEST".equals(v)) return TEST;
        if ("TEST2".equals(v)) return TEST2;
        if ("TEST3".equals(v)) return TEST3;
        if ("LEARN".equals(v)) return LEARN;
        if ("NEURAL".equals(v)) return NEURAL;
        return DEFAULT;
    }
}
