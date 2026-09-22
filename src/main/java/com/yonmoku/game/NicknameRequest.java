package com.yonmoku.game;

/** ニックネーム設定リクエストのボディ（color: "B"または"W"）。 */
public record NicknameRequest(String color, String nickname) {
}
