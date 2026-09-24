package com.yonmoku.game;

import java.util.Map;
import java.util.Set;

/**
 * MCTS等の外部（YonmokuRessen-Neural-Networkリポジトリ）から、実際の対局とは独立に
 * 「もしここでこう打ったら」を何度も試すためのステートレスな盤面表現。GameRoomのインスタンス
 * フィールドのうち、1手をシミュレートするのに必要な部分だけを持つ（id・ログ・棋譜・AI設定・
 * ニックネームなどは含まない）。
 */
public record SimulationState(
        Stone[][] board,
        Set<String> dmgMarks,
        Map<String, Boolean> removalEchoes,
        Map<String, Integer> hp,
        Pending pending,
        String currentPlayer,
        boolean gameOver,
        String winner,
        int plyCount,
        int markEventCount,
        int markPerSide,
        int nextMarkEventTurn
) {
}
