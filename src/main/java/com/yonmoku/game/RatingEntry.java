package com.yonmoku.game;

/** ニックネームごとのレーティング（Eloレーティング）と対戦成績。ratings.jsonへそのまま保存される。 */
public record RatingEntry(String nickname, double rating, int wins, int losses, int draws) {
}
