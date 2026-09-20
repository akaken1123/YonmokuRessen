package com.yonmoku.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内蔵AI。2手先（自分の着手 → 相手の最善応手）までを読む簡易ミニマックス探索で着手を選ぶ。
 * 判断基準は主に2つ：
 *  1. 相手が取れる最善の応手を仮定し、その結果できるだけ被ダメージが少ない（できれば逆転できる）手を選ぶ。
 *  2. 相殺・除外の応酬が終わったタイミングの盤面（残りの石の配置）が自分に有利かを評価する。
 * 深い探索木を全展開すると重いため、各手番ではヒューリスティックで有望な候補手だけに絞り込んで探索する。
 */
final class GomokuAi {

    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
    private static final int TOP_LEVEL_CANDIDATES = 14;
    private static final int RESPONSE_CANDIDATES = 10;

    private GomokuAi() {
    }

    static int[] chooseMove(GameStateSnapshot state, String aiColor) {
        SimState root = SimState.fromSnapshot(state);
        String opponent = other(aiColor);

        List<int[]> myCandidates = rankedCandidates(root, aiColor, opponent, TOP_LEVEL_CANDIDATES);
        if (myCandidates.isEmpty()) return null;

        double bestValue = Double.NEGATIVE_INFINITY;
        List<int[]> best = new ArrayList<>();

        for (int[] cell : myCandidates) {
            SimState afterMine = root.copy();
            applyMove(afterMine, cell[0], cell[1], aiColor);

            double value;
            if (afterMine.gameOver) {
                value = terminalValue(afterMine, aiColor, opponent);
            } else {
                value = worstCaseAfterOpponentResponse(afterMine, aiColor, opponent);
            }
            value += ThreadLocalRandom.current().nextDouble() * 0.01;

            if (value > bestValue) {
                bestValue = value;
                best.clear();
                best.add(cell);
            } else if (value == bestValue) {
                best.add(cell);
            }
        }
        return best.get(ThreadLocalRandom.current().nextInt(best.size()));
    }

    /** 相手が最も自分に不利な応手を選ぶと仮定し、その中での最悪値（＝相手の最善応手後の局面価値）を返す。 */
    private static double worstCaseAfterOpponentResponse(SimState afterMine, String aiColor, String opponent) {
        List<int[]> responses = rankedCandidates(afterMine, opponent, aiColor, RESPONSE_CANDIDATES);
        if (responses.isEmpty()) {
            return evaluate(afterMine, aiColor, opponent);
        }
        double worst = Double.POSITIVE_INFINITY;
        for (int[] cell : responses) {
            SimState afterResponse = afterMine.copy();
            applyMove(afterResponse, cell[0], cell[1], opponent);
            double value = afterResponse.gameOver
                    ? terminalValue(afterResponse, aiColor, opponent)
                    : evaluate(afterResponse, aiColor, opponent);
            worst = Math.min(worst, value);
        }
        return worst;
    }

    private static double terminalValue(SimState s, String aiColor, String opponent) {
        if (aiColor.equals(s.winner)) return 1_000_000;
        if (opponent.equals(s.winner)) return -1_000_000;
        return 0;
    }

    private static void applyMove(SimState s, int r, int c, String color) {
        MoveResolution res = GameRoom.resolveMove(s.board, s.dmgMarks, s.removalEchoes, s.hp, s.pending, r, c, color);
        s.pending = res.pendingAfter();
        if (s.hp.get("B") <= 0 && s.hp.get("W") <= 0) {
            s.gameOver = true;
            s.winner = "draw";
        } else if (s.hp.get("B") <= 0) {
            s.gameOver = true;
            s.winner = "W";
        } else if (s.hp.get("W") <= 0) {
            s.gameOver = true;
            s.winner = "B";
        }
    }

    /**
     * 有望な候補手を絞り込む。「自分が除外を起こせる手」「相手が除外を起こせてしまう手（＝要ブロック）」は
     * ヒューリスティックの順位に関わらず必ず候補に含め、残り枠をcellFor視点のヒューリスティックで埋める。
     */
    private static List<int[]> rankedCandidates(SimState s, String cellFor, String against, int limit) {
        List<int[]> empties = emptyCells(s.board);
        if (empties.size() <= limit) return empties;

        List<int[]> forced = new ArrayList<>();
        Set<String> forcedKeys = new HashSet<>();
        for (int[] cell : empties) {
            boolean critical = wouldRemove(s.board, cell[0], cell[1], cellFor)
                    || wouldRemove(s.board, cell[0], cell[1], against);
            if (critical) {
                forced.add(cell);
                forcedKeys.add(key(cell[0], cell[1]));
            }
        }

        List<int[]> rest = new ArrayList<>();
        for (int[] cell : empties) {
            if (!forcedKeys.contains(key(cell[0], cell[1]))) rest.add(cell);
        }
        rest.sort(Comparator.comparingDouble(
                (int[] cell) -> heuristicScore(s.board, s.dmgMarks, s.removalEchoes, cell[0], cell[1], cellFor)
                        + heuristicScore(s.board, s.dmgMarks, s.removalEchoes, cell[0], cell[1], against)
        ).reversed());

        List<int[]> result = new ArrayList<>(forced);
        for (int[] cell : rest) {
            if (result.size() >= limit) break;
            result.add(cell);
        }
        return result;
    }

    /** (r,c)に color の石を置いた場合に、除外（4つ以上並び）が発生するかどうかだけを判定する軽量チェック。 */
    private static boolean wouldRemove(Stone[][] board, int r, int c, String color) {
        int size = board.length;
        Stone[][] copy = new Stone[size][];
        for (int i = 0; i < size; i++) copy[i] = board[i].clone();
        copy[r][c] = new Stone(color, false, 0);
        return GameRoom.computeRemoval(copy, r, c, color) != null;
    }

    private static List<int[]> emptyCells(Stone[][] board) {
        List<int[]> empties = new ArrayList<>();
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board.length; c++) {
                if (board[r][c] == null) empties.add(new int[]{r, c});
            }
        }
        return empties;
    }

    /**
     * ある局面（すでに次の一手が終わった後）の、AI視点での価値。
     * HPの差分を主軸に、保留ダメージが残っていれば「次の手番でほぼ確定するダメージ」として見込み、
     * 残りの石の配置（ライン形成のポテンシャル）を軽めに加味する。
     */
    private static double evaluate(SimState s, String aiColor, String opponent) {
        double value = (s.hp.get(aiColor) - s.hp.get(opponent)) * 50.0;

        if (s.pending != null) {
            double expected = s.pending.amount() * 8.0;
            value += s.pending.target().equals(aiColor) ? -expected : expected;
        }

        double boardScore = 0;
        for (int r = 0; r < s.board.length; r++) {
            for (int c = 0; c < s.board.length; c++) {
                if (s.board[r][c] == null) {
                    boardScore += heuristicScore(s.board, s.dmgMarks, s.removalEchoes, r, c, aiColor);
                    boardScore -= heuristicScore(s.board, s.dmgMarks, s.removalEchoes, r, c, opponent);
                }
            }
        }
        value += boardScore * 0.02;
        return value;
    }

    private static double heuristicScore(Stone[][] board, Set<String> dmgMarks, Map<String, Boolean> removalEchoes,
                                          int r, int c, String color) {
        double score = 0;
        for (int[] d : DIRS) {
            score += lineWeight(board, r, c, d[0], d[1], color);
        }
        int size = board.length;
        int center = size / 2;
        double distance = Math.hypot(r - center, c - center);
        score += (size - distance) * 0.6;
        String k = key(r, c);
        if (dmgMarks.contains(k)) score += 3;
        if (removalEchoes.containsKey(k)) score += 1.5;
        return score;
    }

    /** (r,c)に color の石を置いたと仮定した場合の、この方向の連なりの強さ（開いている端ほど価値が高い）。 */
    private static double lineWeight(Stone[][] board, int r, int c, int dr, int dc, String color) {
        int size = board.length;
        int forward = 0;
        int rr = r + dr, cc = c + dc;
        while (inBounds(rr, cc, size) && board[rr][cc] != null && board[rr][cc].color().equals(color)) {
            forward++;
            rr += dr;
            cc += dc;
        }
        boolean forwardOpen = inBounds(rr, cc, size) && board[rr][cc] == null;

        int backward = 0;
        rr = r - dr;
        cc = c - dc;
        while (inBounds(rr, cc, size) && board[rr][cc] != null && board[rr][cc].color().equals(color)) {
            backward++;
            rr -= dr;
            cc -= dc;
        }
        boolean backwardOpen = inBounds(rr, cc, size) && board[rr][cc] == null;

        int total = forward + backward + 1;
        double base = Math.pow(4, Math.min(total, 4));
        int openEnds = (forwardOpen ? 1 : 0) + (backwardOpen ? 1 : 0);
        double factor = switch (openEnds) {
            case 2 -> 1.0;
            case 1 -> 0.45;
            default -> 0.12;
        };
        return base * factor;
    }

    private static boolean inBounds(int r, int c, int size) {
        return r >= 0 && r < size && c >= 0 && c < size;
    }

    private static String key(int r, int c) {
        return r + "," + c;
    }

    private static String other(String color) {
        return "B".equals(color) ? "W" : "B";
    }

    /** AIの先読み用に、対局の核となる状態だけを持つ可変コピー（ログ・巡数・マーク設置タイミングは含まない）。 */
    private static final class SimState {
        Stone[][] board;
        Set<String> dmgMarks;
        Map<String, Boolean> removalEchoes;
        Map<String, Integer> hp;
        Pending pending;
        boolean gameOver;
        String winner;

        static SimState fromSnapshot(GameStateSnapshot state) {
            SimState s = new SimState();
            int size = state.size();
            s.board = new Stone[size][];
            for (int r = 0; r < size; r++) {
                s.board[r] = state.board()[r].clone();
            }
            s.dmgMarks = new HashSet<>(state.dmgMarks());
            s.removalEchoes = new HashMap<>(state.removalEchoes());
            s.hp = new HashMap<>(state.hp());
            s.pending = state.pending();
            s.gameOver = state.gameOver();
            s.winner = state.winner();
            return s;
        }

        SimState copy() {
            SimState s = new SimState();
            int size = board.length;
            s.board = new Stone[size][];
            for (int r = 0; r < size; r++) {
                s.board[r] = board[r].clone();
            }
            s.dmgMarks = new HashSet<>(dmgMarks);
            s.removalEchoes = new HashMap<>(removalEchoes);
            s.hp = new HashMap<>(hp);
            s.pending = pending;
            s.gameOver = gameOver;
            s.winner = winner;
            return s;
        }
    }
}
