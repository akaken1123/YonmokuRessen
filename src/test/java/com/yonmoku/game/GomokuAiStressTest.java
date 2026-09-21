package com.yonmoku.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * GomokuAiが特定の局面でハング（無応答）したり例外を投げたりしないことを、
 * 大量のランダム対局を通して検証する。ユーザー報告の「AIが停止した」事象の
 * 再現を狙ったピンポイントの再現テストでは特定の局面に依存しすぎるため、
 * より広く局面パターンをカバーするために用意した。DEFAULT・TESTの両レベルを検証する。
 */
class GomokuAiStressTest {

    @Test
    void defaultLevelNeverHangsOrThrowsOverManyRandomGames() {
        runStress(AiLevel.DEFAULT);
    }

    @Test
    void testLevelNeverHangsOrThrowsOverManyRandomGames() {
        runStress(AiLevel.TEST);
    }

    private void runStress(AiLevel level) {
        Random random = new Random(42);
        int gamesPlayed = 0;
        int aiMovesMade = 0;

        for (int g = 0; g < 150; g++) {
            GameRoom room = new GameRoom("STRESS" + g);
            room.configureAi(true, "W", level);

            for (int ply = 0; ply < 200; ply++) {
                GameStateSnapshot state = room.snapshot();
                if (state.gameOver()) break;

                String mover = state.currentPlayer();
                List<int[]> empties = new ArrayList<>();
                for (int r = 0; r < state.size(); r++) {
                    for (int c = 0; c < state.size(); c++) {
                        if (state.board()[r][c] == null) empties.add(new int[]{r, c});
                    }
                }
                if (empties.isEmpty()) break;

                if ("W".equals(mover)) {
                    // AIの手番のはずが、configureAi直後は自動着手されない経路もあるため直接呼ぶ。
                    int[] move = callWithTimeout(state, "W", level, g, ply);
                    room.placeStone(move[0], move[1]);
                    aiMovesMade++;
                } else {
                    int[] cell = empties.get(random.nextInt(empties.size()));
                    try {
                        room.placeStone(cell[0], cell[1]);
                    } catch (RuntimeException e) {
                        fail("game " + g + " ply " + ply + ": unexpected exception on human move "
                                + cell[0] + "," + cell[1] + ": " + e, e);
                    }
                }
            }
            gamesPlayed++;
        }

        System.out.println("[" + level + "] Completed " + gamesPlayed + " games, AI made " + aiMovesMade
                + " moves total, no hangs/exceptions.");
    }

    private int[] callWithTimeout(GameStateSnapshot state, String color, AiLevel level, int gameIdx, int ply) {
        CompletableFuture<int[]> future = CompletableFuture.supplyAsync(() -> GomokuAi.chooseMove(state, color, level));
        try {
            int[] move = future.get(3, TimeUnit.SECONDS);
            if (move == null) {
                fail("game " + gameIdx + " ply " + ply + ": AI returned null with empty cells available. pending="
                        + state.pending() + " hp=" + state.hp());
            }
            return move;
        } catch (TimeoutException e) {
            throw new AssertionError("game " + gameIdx + " ply " + ply + ": AI hung (>3s)! pending="
                    + state.pending() + " hp=" + state.hp(), e);
        } catch (Exception e) {
            throw new AssertionError("game " + gameIdx + " ply " + ply + ": AI threw an exception: " + e
                    + " pending=" + state.pending() + " hp=" + state.hp(), e);
        }
    }
}
