package com.yonmoku.game;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** クライアントへ送信する対局状態のスナップショット（イミュータブル）。 */
public record GameStateSnapshot(
        String id,
        int size,
        Stone[][] board,
        Set<String> dmgMarks,
        Map<String, Boolean> removalEchoes,
        String currentPlayer,
        Map<String, Integer> hp,
        Pending pending,
        boolean gameOver,
        String winner,
        int plyCount,
        List<String> log,
        AiLevel blackAiLevel,
        AiLevel whiteAiLevel,
        LastMove lastMove,
        String blackNickname,
        String whiteNickname
) {
}
