package com.yonmoku.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 内蔵AI。深い読み（ミニマックス探索等）は行わず、1手先の除外シミュレーションと
 * 盤面のヒューリスティック評価だけで着手を選ぶ、対人戦の代役として遊べる程度の強さのAI。
 */
final class GomokuAi {

    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    private GomokuAi() {
    }

    static int[] chooseMove(GameStateSnapshot state, String aiColor) {
        Stone[][] board = state.board();
        int size = state.size();
        List<int[]> empties = new ArrayList<>();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (board[r][c] == null) empties.add(new int[]{r, c});
            }
        }
        if (empties.isEmpty()) return null;

        Set<String> dmgMarks = state.dmgMarks();
        Map<String, Boolean> removalEchoes = state.removalEchoes();
        Pending pending = state.pending();

        if (pending != null) {
            return chooseResponseToPending(board, dmgMarks, removalEchoes, pending, aiColor, empties);
        }
        return chooseOffenseOrDefense(board, dmgMarks, removalEchoes, aiColor, empties);
    }

    /** 保留ダメージがAI宛てになっている状態での応手。反撃・バックアタックで被害を最小化（できれば逆転）する。 */
    private static int[] chooseResponseToPending(Stone[][] board, Set<String> dmgMarks,
                                                  Map<String, Boolean> removalEchoes, Pending pending,
                                                  String aiColor, List<int[]> empties) {
        int pendingAmount = pending.amount();
        double bestValue = Double.NEGATIVE_INFINITY;
        List<int[]> best = new ArrayList<>();

        for (int[] cell : empties) {
            int r = cell[0], c = cell[1];
            String k = key(r, c);
            RemovalResult rr = simulateRemoval(board, dmgMarks, removalEchoes, r, c, aiColor);
            double value;
            if (rr != null) {
                value = rr.total() - pendingAmount;
            } else if (removalEchoes.containsKey(k)) {
                value = -Math.max(0, pendingAmount - 1);
            } else {
                value = -pendingAmount;
            }
            value += heuristicScore(board, dmgMarks, removalEchoes, r, c, aiColor) * 0.01;
            value += ThreadLocalRandom.current().nextDouble() * 0.001;
            if (value > bestValue) {
                bestValue = value;
                best.clear();
                best.add(cell);
            } else if (value == bestValue) {
                best.add(cell);
            }
        }
        return pickRandom(best);
    }

    /** 保留がない通常局面：自分が除外できるなら攻撃、相手の除外を防げるなら防御、それ以外は形勢評価。 */
    private static int[] chooseOffenseOrDefense(Stone[][] board, Set<String> dmgMarks,
                                                 Map<String, Boolean> removalEchoes, String aiColor,
                                                 List<int[]> empties) {
        String opponent = "B".equals(aiColor) ? "W" : "B";

        int[] bestAttack = null;
        int bestAttackTotal = -1;
        for (int[] cell : empties) {
            RemovalResult rr = simulateRemoval(board, dmgMarks, removalEchoes, cell[0], cell[1], aiColor);
            if (rr != null && rr.total() > bestAttackTotal) {
                bestAttackTotal = rr.total();
                bestAttack = cell;
            }
        }
        if (bestAttack != null) return bestAttack;

        int[] bestBlock = null;
        int bestBlockTotal = -1;
        for (int[] cell : empties) {
            RemovalResult rr = simulateRemoval(board, dmgMarks, removalEchoes, cell[0], cell[1], opponent);
            if (rr != null && rr.total() > bestBlockTotal) {
                bestBlockTotal = rr.total();
                bestBlock = cell;
            }
        }
        if (bestBlock != null) return bestBlock;

        double bestScore = Double.NEGATIVE_INFINITY;
        List<int[]> best = new ArrayList<>();
        for (int[] cell : empties) {
            double score = heuristicScore(board, dmgMarks, removalEchoes, cell[0], cell[1], aiColor)
                    - 0.6 * heuristicScore(board, dmgMarks, removalEchoes, cell[0], cell[1], opponent)
                    + ThreadLocalRandom.current().nextDouble() * 0.5;
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(cell);
            } else if (score == bestScore) {
                best.add(cell);
            }
        }
        return pickRandom(best);
    }

    private static RemovalResult simulateRemoval(Stone[][] board, Set<String> dmgMarks,
                                                  Map<String, Boolean> removalEchoes, int r, int c, String color) {
        int size = board.length;
        Stone[][] copy = new Stone[size][];
        for (int i = 0; i < size; i++) copy[i] = board[i].clone();
        String k = key(r, c);
        boolean dmgFlag = dmgMarks.contains(k);
        int backAttackBonus = removalEchoes.containsKey(k) ? (Boolean.TRUE.equals(removalEchoes.get(k)) ? 2 : 1) : 0;
        copy[r][c] = new Stone(color, dmgFlag, backAttackBonus);
        return GameRoom.computeRemoval(copy, r, c, color);
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

    private static int[] pickRandom(List<int[]> cells) {
        return cells.get(ThreadLocalRandom.current().nextInt(cells.size()));
    }
}
