package com.yonmoku.game;

/**
 * 1回の着手における、ログ整形前の核となる状態遷移の結果。
 * 実際の対局（GameRoom.placeStone）とAIの先読みシミュレーション（GomokuAi）の
 * 両方から使われる、ルールの単一の実装。
 */
record MoveResolution(
        RemovalResult removalResult,
        Pending pendingAfter,
        String damagedColor,
        int damageAmount,
        boolean dmgFlag,
        int backAttackBonus
) {
}
