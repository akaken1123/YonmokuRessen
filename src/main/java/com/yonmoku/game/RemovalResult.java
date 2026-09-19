package com.yonmoku.game;

import java.util.List;

/** 4つ以上並んで除外されるときの集計結果。 */
record RemovalResult(int baseCount, int dmgBonus, int backBonus, int total, List<String> cells) {
}
