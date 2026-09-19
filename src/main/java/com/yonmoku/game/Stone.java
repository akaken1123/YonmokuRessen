package com.yonmoku.game;

/** 盤上の石1つ分の状態。着手後は不変。 */
public record Stone(String color, boolean dmgFlag, int backAttackBonus) {
}
