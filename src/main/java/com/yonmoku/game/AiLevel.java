package com.yonmoku.game;

/**
 * AI対戦で使う探索の強さ。DEFAULT は2手先読み（安定版）、TEST は3手先読みの実験版、
 * TEST2 は5手先読みのさらなる実験版。
 * 新しいAIの調整はまずTEST/TEST2に入れ、十分検証できてからDEFAULTに昇格させる想定。
 */
public enum AiLevel {
    DEFAULT,
    TEST,
    TEST2;

    static AiLevel fromParam(String raw) {
        if (raw == null) return DEFAULT;
        String v = raw.trim().toUpperCase();
        if ("TEST".equals(v)) return TEST;
        if ("TEST2".equals(v)) return TEST2;
        return DEFAULT;
    }
}
