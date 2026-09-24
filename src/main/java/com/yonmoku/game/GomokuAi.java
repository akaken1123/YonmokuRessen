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
 * 内蔵AI。自分の着手 → 相手の最善応手（DEFAULT: 2手先読み）、あるいはさらに自分の追撃・相手の再応手まで
 * （TEST: 3手先読み、TEST2: 5手先読み）を読む簡易ミニマックス探索で着手を選ぶ。TEST3はアルファベータ
 * 枝刈り＋反復深化で、持ち時間の許す限りできるだけ深く読む。
 * 判断基準は主に2つ：
 *  1. 相手が取れる最善の応手を仮定し、その結果できるだけ被ダメージが少ない（できれば逆転できる）手を選ぶ。
 *  2. 相殺・除外の応酬が終わったタイミングの盤面（残りの石の配置）が自分に有利かを評価する。
 * 深い探索木を全展開すると重いため、各手番ではヒューリスティックで有望な候補手だけに絞り込んで探索する。
 *
 * DEFAULT（2手先読み）は長く動かして調整してきた安定版。TEST（3手先読み）・TEST2（5手先読み）・
 * TEST3（アルファベータ＋反復深化）はさらに先まで読む実験版で、必ずしもDEFAULTより強いとは限らない。
 * LEARN（学習AI）は探索の深さはDEFAULTと同じだが、評価関数の重み（AiWeights）をLearningServiceが
 * 自己対戦を通じて調整する。新しい調整はまずTEST系に入れ、十分比較してからDEFAULTに昇格させる。
 * DEFAULT/TEST/TEST2の探索は、手番ごとの候補手数を指定した再帰ミニマックス（search）で統一的に扱う
 * （LEARNはその重み可変版であるsearchWeighted/chooseMoveWeightedを使う）。
 */
final class GomokuAi {

    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};

    // 各レベルの深さごとの候補手数（1手目=自分の着手を含む）。深さが増える分、各層の候補手は絞る。
    // DEFAULT: 2手先読み（自分の着手 → 相手の最善応手）。LearningServiceの自己対戦にも同じ形を使う。
    static final int[] DEFAULT_CANDIDATES = {14, 10};
    // TEST: 3手先読み（自分の着手 → 相手の最善応手 → 自分の追撃）。
    private static final int[] TEST_CANDIDATES = {12, 8, 6};
    // TEST2: 5手先読み（自分 → 相手 → 自分 → 相手 → 自分）。層が多いぶん各層はさらに絞る。
    private static final int[] TEST2_CANDIDATES = {8, 6, 5, 4, 3};

    // TEST3: アルファベータ＋反復深化。1手あたりこの時間予算内で、深さ2から限界まで段階的に読み進める。
    private static final long TEST3_TIME_BUDGET_NANOS = 1_200_000_000L;
    private static final int TEST3_CANDIDATE_LIMIT = 10;
    private static final int TEST3_MAX_PLY = 20;

    // LEARN: 探索の形はDEFAULTと同じ（2手先読み）で、評価関数の重み（AiWeights）だけを自己対戦で
    // 学習していく。LearningServiceが起動時とバッチ学習後にsetLearnedWeightsで更新する。
    private static volatile AiWeights learnedWeights = AiWeights.defaults();

    private GomokuAi() {
    }

    static void setLearnedWeights(AiWeights weights) {
        learnedWeights = weights;
    }

    static AiWeights getLearnedWeights() {
        return learnedWeights;
    }

    private static int[] candidateCountsFor(AiLevel level) {
        return switch (level) {
            case TEST -> TEST_CANDIDATES;
            case TEST2 -> TEST2_CANDIDATES;
            default -> DEFAULT_CANDIDATES;
        };
    }

    static int[] chooseMove(GameStateSnapshot state, String aiColor, AiLevel level) {
        if (level == AiLevel.TEST3) {
            return chooseMoveTest3(state, aiColor);
        }
        if (level == AiLevel.NEURAL) {
            return NeuralAi.chooseMove(state, aiColor);
        }
        if (level == AiLevel.LEARN) {
            return chooseMoveWeighted(state, aiColor, learnedWeights, DEFAULT_CANDIDATES);
        }

        SimState root = SimState.fromSnapshot(state);
        String opponent = other(aiColor);
        int[] candidateCounts = candidateCountsFor(level);
        int maxDepth = candidateCounts.length;

        List<int[]> myCandidates = rankedCandidates(root, aiColor, opponent, candidateCounts[0]);
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
                value = search(afterMine, aiColor, opponent, false, 1, maxDepth, candidateCounts);
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

    /**
     * TEST3の着手選択。反復深化：深さ2から順に完全なアルファベータ探索を行い、時間予算を使い切ったら
     * 最後に完了した深さでの最善手を採用する（探索途中で打ち切られた深さの結果は使わない）。
     * 直前の深さでの評価値で候補手を並べ替えてから探索することで、アルファベータの枝刈り効率を上げる。
     */
    private static int[] chooseMoveTest3(GameStateSnapshot state, String aiColor) {
        SimState root = SimState.fromSnapshot(state);
        String opponent = other(aiColor);
        long deadline = System.nanoTime() + TEST3_TIME_BUDGET_NANOS;

        List<int[]> candidates = rankedCandidates(root, aiColor, opponent, TEST3_CANDIDATE_LIMIT);
        if (candidates.isEmpty()) return null;

        int[] bestMove = candidates.get(0);
        Map<String, Double> lastScores = new HashMap<>();

        for (int depth = 2; depth <= TEST3_MAX_PLY; depth++) {
            if (System.nanoTime() >= deadline) break;

            Map<String, Double> orderingScores = lastScores;
            List<int[]> ordered = new ArrayList<>(candidates);
            ordered.sort(Comparator.comparingDouble(
                    (int[] cell) -> orderingScores.getOrDefault(key(cell[0], cell[1]), 0.0)).reversed());

            double bestValueThisDepth = Double.NEGATIVE_INFINITY;
            int[] bestMoveThisDepth = null;
            Map<String, Double> scoresThisDepth = new HashMap<>();
            boolean aborted = false;

            for (int[] cell : ordered) {
                SimState afterMine = root.copy();
                applyMove(afterMine, cell[0], cell[1], aiColor);

                Double value;
                if (afterMine.gameOver) {
                    value = terminalValue(afterMine, aiColor, opponent);
                } else {
                    value = alphaBeta(afterMine, aiColor, opponent, false, 1, depth,
                            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, deadline);
                }
                if (value == null) {
                    aborted = true;
                    break;
                }

                scoresThisDepth.put(key(cell[0], cell[1]), value);
                if (value > bestValueThisDepth) {
                    bestValueThisDepth = value;
                    bestMoveThisDepth = cell;
                }
            }

            if (aborted || bestMoveThisDepth == null) break;
            bestMove = bestMoveThisDepth;
            lastScores = scoresThisDepth;

            // 勝敗が確定する評価まで読めていれば、それ以上深く読む必要はない。
            if (bestValueThisDepth >= 1_000_000 || bestValueThisDepth <= -1_000_000) break;
        }
        return bestMove;
    }

    /**
     * アルファベータ枝刈り付きの再帰ミニマックス。時間予算を使い切ったら null を返して呼び出し元へ
     * 打ち切りを伝播する（その深さの結果全体を破棄させるため）。
     */
    private static Double alphaBeta(SimState state, String aiColor, String opponent, boolean maximizing,
                                     int depth, int maxDepth, double alpha, double beta, long deadline) {
        if (state.gameOver) {
            return terminalValue(state, aiColor, opponent);
        }
        if (depth >= maxDepth) {
            return evaluateTest3(state, aiColor, opponent);
        }
        if (System.nanoTime() >= deadline) {
            return null;
        }

        String mover = maximizing ? aiColor : opponent;
        String against = maximizing ? opponent : aiColor;
        List<int[]> candidates = rankedCandidates(state, mover, against, TEST3_CANDIDATE_LIMIT);
        if (candidates.isEmpty()) {
            return evaluateTest3(state, aiColor, opponent);
        }

        double best = maximizing ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int[] cell : candidates) {
            SimState next = state.copy();
            applyMove(next, cell[0], cell[1], mover);
            Double value = alphaBeta(next, aiColor, opponent, !maximizing, depth + 1, maxDepth, alpha, beta, deadline);
            if (value == null) return null;

            if (maximizing) {
                best = Math.max(best, value);
                alpha = Math.max(alpha, best);
            } else {
                best = Math.min(best, value);
                beta = Math.min(beta, best);
            }
            if (alpha >= beta) break;
        }
        return best;
    }

    /**
     * 深さ depth（0始まり、0は既に打たれた自分の1手目）まで進んだ局面から、残りの手番を再帰的に
     * ミニマックス探索する。奇数深さは相手の手番（最小化）、偶数深さは自分の手番（最大化）。
     * depth が maxDepth に達したら、それ以上は読まずヒューリスティック評価で打ち切る。
     */
    private static double search(SimState state, String aiColor, String opponent, boolean maximizing,
                                  int depth, int maxDepth, int[] candidateCounts) {
        if (state.gameOver) {
            return terminalValue(state, aiColor, opponent);
        }
        if (depth >= maxDepth) {
            return evaluate(state, aiColor, opponent);
        }

        String mover = maximizing ? aiColor : opponent;
        String against = maximizing ? opponent : aiColor;
        List<int[]> candidates = rankedCandidates(state, mover, against, candidateCounts[depth]);
        if (candidates.isEmpty()) {
            return evaluate(state, aiColor, opponent);
        }

        double best = maximizing ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int[] cell : candidates) {
            SimState next = state.copy();
            applyMove(next, cell[0], cell[1], mover);
            double value = search(next, aiColor, opponent, !maximizing, depth + 1, maxDepth, candidateCounts);
            best = maximizing ? Math.max(best, value) : Math.min(best, value);
        }
        return best;
    }

    /**
     * 指定された重み（AiWeights）で着手を選ぶ。探索の形はDEFAULT/TEST/TEST2と同じ再帰ミニマックスだが、
     * 評価関数だけがevaluateWeighted（重み可変）になる。LEARNレベルの実対局と、LearningServiceの
     * 自己対戦（現チャンピオンと変異させた挑戦者を戦わせる）の両方から使われる。
     */
    static int[] chooseMoveWeighted(GameStateSnapshot state, String aiColor, AiWeights weights, int[] candidateCounts) {
        SimState root = SimState.fromSnapshot(state);
        String opponent = other(aiColor);
        int maxDepth = candidateCounts.length;

        List<int[]> myCandidates = rankedCandidates(root, aiColor, opponent, candidateCounts[0]);
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
                value = searchWeighted(afterMine, aiColor, opponent, false, 1, maxDepth, candidateCounts, weights);
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

    private static double searchWeighted(SimState state, String aiColor, String opponent, boolean maximizing,
                                          int depth, int maxDepth, int[] candidateCounts, AiWeights weights) {
        if (state.gameOver) {
            return terminalValue(state, aiColor, opponent);
        }
        if (depth >= maxDepth) {
            return evaluateWeighted(state, aiColor, opponent, weights);
        }

        String mover = maximizing ? aiColor : opponent;
        String against = maximizing ? opponent : aiColor;
        List<int[]> candidates = rankedCandidates(state, mover, against, candidateCounts[depth]);
        if (candidates.isEmpty()) {
            return evaluateWeighted(state, aiColor, opponent, weights);
        }

        double best = maximizing ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (int[] cell : candidates) {
            SimState next = state.copy();
            applyMove(next, cell[0], cell[1], mover);
            double value = searchWeighted(next, aiColor, opponent, !maximizing, depth + 1, maxDepth, candidateCounts, weights);
            best = maximizing ? Math.max(best, value) : Math.min(best, value);
        }
        return best;
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

    /**
     * LEARN用の評価関数。evaluateと同じ構造（HP差・保留ダメージ・ライン形成ポテンシャル）だが、
     * 各項の重みが固定値ではなくAiWeights（自己対戦で学習される）になっている。
     */
    private static double evaluateWeighted(SimState s, String aiColor, String opponent, AiWeights w) {
        double value = (s.hp.get(aiColor) - s.hp.get(opponent)) * w.hpWeight();

        if (s.pending != null) {
            double expected = s.pending.amount() * w.pendingWeight();
            value += s.pending.target().equals(aiColor) ? -expected : expected;
        }

        double boardScore = 0;
        for (int r = 0; r < s.board.length; r++) {
            for (int c = 0; c < s.board.length; c++) {
                if (s.board[r][c] == null) {
                    boardScore += heuristicScoreWeighted(s.board, s.dmgMarks, s.removalEchoes, r, c, aiColor, w);
                    boardScore -= heuristicScoreWeighted(s.board, s.dmgMarks, s.removalEchoes, r, c, opponent, w);
                }
            }
        }
        value += boardScore * w.boardScoreWeight();
        return value;
    }

    private static double heuristicScoreWeighted(Stone[][] board, Set<String> dmgMarks,
                                                  Map<String, Boolean> removalEchoes, int r, int c, String color,
                                                  AiWeights w) {
        double score = 0;
        for (int[] d : DIRS) {
            score += lineWeightWeighted(board, r, c, d[0], d[1], color, w);
        }
        int size = board.length;
        int center = size / 2;
        double distance = Math.hypot(r - center, c - center);
        score += (size - distance) * w.centerWeight();
        String k = key(r, c);
        if (dmgMarks.contains(k)) score += w.dmgMarkBonus();
        if (removalEchoes.containsKey(k)) score += w.echoMarkBonus();
        return score;
    }

    private static double lineWeightWeighted(Stone[][] board, int r, int c, int dr, int dc, String color,
                                               AiWeights w) {
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
            case 2 -> w.openTwoFactor();
            case 1 -> w.openOneFactor();
            default -> w.closedFactor();
        };
        return base * factor;
    }

    /**
     * TEST3用の評価関数。evaluateのHP差・保留ダメージ・ライン形成ポテンシャルに加えて、次の3点を見る。
     *  1. 残りHPが少ない側ほど1点あたりの価値を上げる（追い詰めているほど有利、追い詰められているほど深刻）。
     *  2. 「あと1手で除外を起こせるマス」が2つ以上同時にある場合はフォーク（相手は片方しか防げない）として
     *     大きく加点/減点する。
     *  3. 盤上の自分のダメージ増加マーク付きの石（除外されると相手への与ダメージが増える）が、まだ生きている
     *     ライン上でどれだけ強い状態にあるかを加味する。
     */
    private static double evaluateTest3(SimState s, String aiColor, String opponent) {
        double value = damageCost(6 - s.hp.get(opponent)) - damageCost(6 - s.hp.get(aiColor));

        if (s.pending != null) {
            double expected = s.pending.amount() * 8.0;
            value += s.pending.target().equals(aiColor) ? -expected : expected;
        }

        double boardScore = 0;
        int aiWinningSquares = 0;
        int oppWinningSquares = 0;
        for (int r = 0; r < s.board.length; r++) {
            for (int c = 0; c < s.board.length; c++) {
                if (s.board[r][c] != null) continue;
                boardScore += heuristicScore(s.board, s.dmgMarks, s.removalEchoes, r, c, aiColor);
                boardScore -= heuristicScore(s.board, s.dmgMarks, s.removalEchoes, r, c, opponent);
                if (aiWinningSquares < 2 && wouldRemove(s.board, r, c, aiColor)) aiWinningSquares++;
                if (oppWinningSquares < 2 && wouldRemove(s.board, r, c, opponent)) oppWinningSquares++;
            }
        }
        value += boardScore * 0.02;
        if (aiWinningSquares >= 2) value += 400;
        if (oppWinningSquares >= 2) value -= 400;

        value += dmgFlagLineBonus(s, aiColor) - dmgFlagLineBonus(s, opponent);
        return value;
    }

    /** 残りHPからの被ダメージ量（lost）に対するコスト。追い詰められているときほど1点の価値が急に増す。 */
    private static double damageCost(double lost) {
        return lost * 50.0 + lost * lost * 10.0;
    }

    /**
     * 盤上に既にある color のダメージ増加マーク付きの石が、まだ生きているラインの中でどれだけ強い状態に
     * あるかの合計。この石を含むラインがいずれ除外されると相手への与ダメージが底上げされるため、
     * ライン形成が進んでいるほど加点する。
     */
    private static double dmgFlagLineBonus(SimState s, String color) {
        double bonus = 0;
        for (int r = 0; r < s.board.length; r++) {
            for (int c = 0; c < s.board.length; c++) {
                Stone stone = s.board[r][c];
                if (stone != null && stone.dmgFlag() && color.equals(stone.color())) {
                    for (int[] d : DIRS) {
                        bonus += lineWeight(s.board, r, c, d[0], d[1], color) * 0.01;
                    }
                }
            }
        }
        return bonus;
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
