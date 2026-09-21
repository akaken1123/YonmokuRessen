package com.yonmoku.game;

/**
 * AI対戦で使う探索の強さ。DEFAULT は2手先読み（安定版）、TEST は3手先読みの実験版。
 * 新しいAIの調整はまずTESTに入れ、十分検証できてからDEFAULTに昇格させる想定。
 */
public enum AiLevel {
    DEFAULT,
    TEST;

    static AiLevel fromParam(String raw) {
        if (raw == null) return DEFAULT;
        String v = raw.trim().toUpperCase();
        if ("TEST".equals(v)) return TEST;
        return DEFAULT;
    }
}
