package com.yonmoku.game;

/**
 * AI対戦で使う探索の強さ。DEFAULT は2手先読み（安定版）、TEST は3手先読み、TEST2 は5手先読みの
 * 実験版（いずれも各深さの候補手数を固定した全展開ミニマックス）。TEST3 はアルファベータ枝刈り＋
 * 反復深化で、持ち時間（1手あたり約1.2秒）の許す限りできるだけ深く読む実験版。TEST4 はTEST3に
 * 置換表（transposition table）を加えた版で、同じ局面の再評価を省略できる分、同じ持ち時間でも
 * より深く読める。また、ダメージ増加マスの評価上の重要性もTEST3より高くしてある（盤面の石数の
 * 優位だけでは、ダメージ増加マスを押さえられて一方的にダメージを取られる展開に弱かったため）。
 * TEST5 は探索エンジン（置換表付きアルファベータ＋反復深化）はTEST4と共通だが、評価関数が
 * 「一撃でどれだけ大きな除外（ダメージ）を出せるか」を主軸にした一撃必殺型。HP差の安全策を弱める分、
 * 被弾リスクは高くなるが、一撃で大きく削り切る手を積極的に選ぶ。
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
    TEST4,
    TEST5,
    LEARN,
    NEURAL;

    static AiLevel fromParam(String raw) {
        if (raw == null) return DEFAULT;
        String v = raw.trim().toUpperCase();
        if ("TEST".equals(v)) return TEST;
        if ("TEST2".equals(v)) return TEST2;
        if ("TEST3".equals(v)) return TEST3;
        if ("TEST4".equals(v)) return TEST4;
        if ("TEST5".equals(v)) return TEST5;
        if ("LEARN".equals(v)) return LEARN;
        if ("NEURAL".equals(v)) return NEURAL;
        return DEFAULT;
    }
}
