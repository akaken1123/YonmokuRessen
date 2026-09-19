package com.yonmoku.game;

/** 保留ダメージ（相殺判定を待っている状態）。 */
public record Pending(String source, String target, int amount) {
}
