package com.yonmoku.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GameRoom.applyTurn（ステートレスな1手シミュレーション。SimulationControllerが使うのと同じ経路）を
 * 戻り値だけで連鎖させた場合に、実際の対局（GameRoomのインスタンスがplaceStoneを呼ぶ通常の対局）と
 * 完全に同じ結果になることを、ランダムな対局を通して検証する。これは、MCTS（別リポジトリ側）が
 * HTTP経由でapplyTurnを何度も呼び出すだけで対局全体を正しく追跡できることの裏付けになる。
 */
class GameRoomSimulationTest {

    @Test
    void chainedApplyTurnMatchesARealGameRoomOverManyRandomGames() {
        Random random = new Random(123);

        for (int g = 0; g < 30; g++) {
            GameRoom room = new GameRoom("SIM" + g);

            // シミュレーション側の状態（GameRoomのインスタンスを一切使わず、戻り値だけで引き継ぐ）
            Stone[][] simBoard = new Stone[GameRoom.SIZE][GameRoom.SIZE];
            Set<String> simDmgMarks = new LinkedHashSet<>();
            Map<String, Boolean> simRemovalEchoes = new LinkedHashMap<>();
            Map<String, Integer> simHp = new HashMap<>();
            simHp.put("B", 6);
            simHp.put("W", 6);
            Pending simPending = null;
            int simPlyCount = 0;
            int simMarkEventCount = 0;
            int simMarkPerSide = 1;
            int simNextMarkEventTurn = 10;
            String simCurrentPlayer = "B";

            for (int ply = 0; ply < 300; ply++) {
                GameStateSnapshot state = room.snapshot();
                if (state.gameOver()) break;

                List<int[]> empties = new ArrayList<>();
                for (int r = 0; r < state.size(); r++) {
                    for (int c = 0; c < state.size(); c++) {
                        if (state.board()[r][c] == null) empties.add(new int[]{r, c});
                    }
                }
                if (empties.isEmpty()) break;
                int[] cell = empties.get(random.nextInt(empties.size()));

                assertEquals(state.currentPlayer(), simCurrentPlayer,
                        "game " + g + " ply " + ply + ": 手番が食い違っている");

                // ダメージ増加マーク設置は乱数を使うため、実対局側とシミュレーション側に
                // 同じシードの乱数源を渡して、この手で選ばれる結果が一致するようにする。
                long markSeed = random.nextLong();
                room.setRandomForTesting(new Random(markSeed));
                room.placeStone(cell[0], cell[1]);

                TurnResult turn = GameRoom.applyTurn(simBoard, simDmgMarks, simRemovalEchoes, simHp, simPending,
                        simPlyCount, simMarkEventCount, simMarkPerSide, simNextMarkEventTurn,
                        simCurrentPlayer, cell[0], cell[1], new Random(markSeed));
                simPending = turn.pendingAfter();
                if (!turn.gameOver()) {
                    simPlyCount = turn.plyCount();
                    simMarkEventCount = turn.markEventCount();
                    simMarkPerSide = turn.markPerSide();
                    simNextMarkEventTurn = turn.nextMarkEventTurn();
                    simCurrentPlayer = turn.nextPlayer();
                }

                GameStateSnapshot afterReal = room.snapshot();
                for (int r = 0; r < state.size(); r++) {
                    for (int c = 0; c < state.size(); c++) {
                        assertEquals(afterReal.board()[r][c], simBoard[r][c],
                                "game " + g + " ply " + ply + ": (" + r + "," + c + ")の石が食い違っている");
                    }
                }
                assertEquals(afterReal.dmgMarks(), simDmgMarks,
                        "game " + g + " ply " + ply + ": ダメージ増加マークが食い違っている");
                assertEquals(afterReal.removalEchoes(), simRemovalEchoes,
                        "game " + g + " ply " + ply + ": 除外あとマークが食い違っている");
                assertEquals(afterReal.hp(), simHp, "game " + g + " ply " + ply + ": HPが食い違っている");
                assertEquals(afterReal.pending(), simPending,
                        "game " + g + " ply " + ply + ": 保留ダメージが食い違っている");
                assertEquals(afterReal.gameOver(), turn.gameOver(),
                        "game " + g + " ply " + ply + ": 終了判定が食い違っている");
                assertEquals(afterReal.winner(), turn.winner(),
                        "game " + g + " ply " + ply + ": 勝者が食い違っている");
            }
        }
    }
}
