package com.yonmoku.game;

import java.util.List;

/**
 * GameRoom.applyTurn()の結果。ログ整形前の、盤面と数値だけの純粋な1手分の処理結果。
 * moveResolutionは除外・ダメージの詳細（ログ整形用）、markPlacementInfoはダメージ増加マークが
 * 設置された場合の「どちらの色に何個」の説明（ログ整形用）。
 */
record TurnResult(
        MoveResolution moveResolution,
        Pending pendingAfter,
        boolean gameOver,
        String winner,
        int plyCount,
        int markEventCount,
        int markPerSide,
        int nextMarkEventTurn,
        String nextPlayer,
        List<String> markPlacementInfo
) {
}
