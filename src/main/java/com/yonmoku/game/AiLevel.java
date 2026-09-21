package com.yonmoku.game;

/**
 * AI対戦で使う探索の強さ。DEFAULT は2手先読み（安定版）、TEST は3手先読み、TEST2 は5手先読みの
 * 実験版（いずれも各深さの候補手数を固定した全展開ミニマックス）。TEST3 はアルファベータ枝刈り＋
 * 反復深化で、持ち時間（1手あたり約1.2秒）の許す限りできるだけ深く読む実験版。
 * 新しいAIの調整はまずTEST系に入れ、十分検証できてからDEFAULTに昇格させる想定。
 */
public enum AiLevel {
    DEFAULT,
    TEST,
    TEST2,
    TEST3;

    static AiLevel fromParam(String raw) {
        if (raw == null) return DEFAULT;
        String v = raw.trim().toUpperCase();
        if ("TEST".equals(v)) return TEST;
        if ("TEST2".equals(v)) return TEST2;
        if ("TEST3".equals(v)) return TEST3;
        return DEFAULT;
    }
}
