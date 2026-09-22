package com.yonmoku.game;

/** 対局終了時、両者のニックネームが設定されていた場合にのみ生成される、レーティング更新用の情報。 */
record RatingUpdate(String blackNickname, String whiteNickname, String winner) {
}
