package com.yonmoku.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NEURAL AIの対局時に使う、方策・価値ネットワーク付きのPUCT探索（AlphaZero方式のMCTS）。
 * YonmokuRessen-Neural-Networkリポジトリの yonmoku_nn/mcts.py（学習側の自己対戦で使っているのと
 * 同じアルゴリズム）をJavaに移植したもの。学習は「ネットワーク単体」ではなく「ネットワーク+探索」の
 * 強さを前提にしているため、対局時にも同じネットワークで探索しないと、学習された本来の強さが出ない。
 *
 * 既存の内蔵AI（GomokuAi）のミニマックス探索と同様、着手のたびのダメージ増加マーク設置イベント
 * （plyCount依存のスケジューリング）は先読みの中では考慮しない（既存AIの先読みと同じ簡略化。
 * mcts.pyの学習用シミュレーションはHTTP経由のGameRoom.applyTurnを使うぶん精密だが、対局時は
 * 応答速度が要るため、GomokuAiと同じ軽量なGameRoom.resolveMoveベースの先読みにしている）。
 */
final class NeuralMcts {

    private NeuralMcts() {
    }

    static int[] chooseMove(GameStateSnapshot snapshot, int numSimulations, double cPuct) {
        Node root = new Node(SimBoard.fromSnapshot(snapshot), 0.0);
        double rootValue = expand(root);
        root.visitCount = 1;
        root.valueSum = rootValue;

        for (int i = 0; i < numSimulations; i++) {
            simulateOnce(root, cPuct);
        }

        Integer bestMove = null;
        int bestVisits = -1;
        for (Map.Entry<Integer, Node> e : root.children.entrySet()) {
            if (e.getValue().visitCount > bestVisits) {
                bestVisits = e.getValue().visitCount;
                bestMove = e.getKey();
            }
        }
        if (bestMove == null) return null;
        int size = root.state.board.length;
        return new int[]{bestMove / size, bestMove % size};
    }

    private static void simulateOnce(Node root, double cPuct) {
        List<Node> path = new ArrayList<>();
        Node node = root;
        path.add(node);

        Integer pendingMove = null;
        while (node.expanded && !node.state.gameOver) {
            int move = selectMove(node, cPuct);
            Node child = node.children.get(move);
            if (child == null) {
                pendingMove = move;
                break;
            }
            node = child;
            path.add(node);
        }

        double value;
        if (node.state.gameOver) {
            value = terminalValue(node.state);
        } else if (!node.expanded) {
            value = expand(node);
        } else {
            SimBoard childState = node.state.copyAndApply(pendingMove);
            Node child = new Node(childState, node.priors.get(pendingMove));
            node.children.put(pendingMove, child);
            path.add(child);
            node = child;
            value = node.state.gameOver ? terminalValue(node.state) : expand(node);
        }

        for (int i = path.size() - 1; i >= 0; i--) {
            Node n = path.get(i);
            n.visitCount++;
            n.valueSum += value;
            value = -value;
        }
    }

    private static int selectMove(Node node, double cPuct) {
        int totalChildVisits = 0;
        for (Node child : node.children.values()) {
            totalChildVisits += child.visitCount;
        }
        double sqrtTotal = Math.sqrt(totalChildVisits + 1);

        double bestScore = Double.NEGATIVE_INFINITY;
        int bestMove = -1;
        for (Map.Entry<Integer, Double> e : node.priors.entrySet()) {
            int move = e.getKey();
            double prior = e.getValue();
            Node child = node.children.get(move);
            double q = child == null ? 0.0 : child.value();
            int n = child == null ? 0 : child.visitCount;
            double u = cPuct * prior * sqrtTotal / (1 + n);
            double score = q + u;
            if (score > bestScore) {
                bestScore = score;
                bestMove = move;
            }
        }
        return bestMove;
    }

    /** stateのmover視点（gameOver時は「終局させた側」＝直前の着手者）の評価値。 */
    private static double terminalValue(SimBoard s) {
        if (s.winner == null || "draw".equals(s.winner)) return 0.0;
        return s.winner.equals(s.mover) ? 1.0 : -1.0;
    }

    /** 未展開のノードにネットワークで方策・価値を計算する。戻り値はnode.stateのmover視点の価値評価。 */
    private static double expand(Node node) {
        SimBoard s = node.state;
        int size = s.board.length;
        List<Integer> moves = legalMoves(s.board);

        float[] input = NeuralEncoder.encode(s.board, s.dmgMarks, s.removalEchoes, s.hp, s.pending, s.mover);
        NeuralAi.Inference out = NeuralAi.infer(input, size);
        float[] policy = softmax(out.policyLogits());

        Map<Integer, Double> priors = new HashMap<>();
        double total = 0;
        for (int move : moves) {
            double p = policy[move];
            priors.put(move, p);
            total += p;
        }
        if (total > 1e-8) {
            double finalTotal = total;
            priors.replaceAll((move, p) -> p / finalTotal);
        } else {
            double uniform = moves.isEmpty() ? 0.0 : 1.0 / moves.size();
            for (int move : moves) {
                priors.put(move, uniform);
            }
        }

        node.priors = priors;
        node.expanded = true;
        return out.value();
    }

    private static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) {
            if (v > max) max = v;
        }
        float[] exps = new float[logits.length];
        float sum = 0f;
        for (int i = 0; i < logits.length; i++) {
            exps[i] = (float) Math.exp(logits[i] - max);
            sum += exps[i];
        }
        for (int i = 0; i < exps.length; i++) {
            exps[i] /= sum;
        }
        return exps;
    }

    private static List<Integer> legalMoves(Stone[][] board) {
        int size = board.length;
        List<Integer> moves = new ArrayList<>();
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (board[r][c] == null) moves.add(r * size + c);
            }
        }
        return moves;
    }

    private static final class Node {
        final SimBoard state;
        final double prior;
        final Map<Integer, Node> children = new HashMap<>();
        Map<Integer, Double> priors = Map.of();
        int visitCount;
        double valueSum;
        boolean expanded;

        Node(SimBoard state, double prior) {
            this.state = state;
            this.prior = prior;
        }

        double value() {
            return visitCount == 0 ? 0.0 : valueSum / visitCount;
        }
    }

    /**
     * AIの先読み用に、対局の核となる状態だけを持つ不変の局面（GomokuAi.SimStateと同じ簡略化で、
     * ダメージ増加マーク設置イベントは考慮しない）。
     */
    private static final class SimBoard {
        final Stone[][] board;
        final Set<String> dmgMarks;
        final Map<String, Boolean> removalEchoes;
        final Map<String, Integer> hp;
        final Pending pending;
        final String mover;
        final boolean gameOver;
        final String winner;

        private SimBoard(Stone[][] board, Set<String> dmgMarks, Map<String, Boolean> removalEchoes,
                          Map<String, Integer> hp, Pending pending, String mover, boolean gameOver, String winner) {
            this.board = board;
            this.dmgMarks = dmgMarks;
            this.removalEchoes = removalEchoes;
            this.hp = hp;
            this.pending = pending;
            this.mover = mover;
            this.gameOver = gameOver;
            this.winner = winner;
        }

        static SimBoard fromSnapshot(GameStateSnapshot state) {
            int size = state.size();
            Stone[][] board = new Stone[size][];
            for (int r = 0; r < size; r++) {
                board[r] = state.board()[r].clone();
            }
            return new SimBoard(board, new HashSet<>(state.dmgMarks()), new HashMap<>(state.removalEchoes()),
                    new HashMap<>(state.hp()), state.pending(), state.currentPlayer(), state.gameOver(),
                    state.winner());
        }

        /** moveIdx（r*size+c）にmoverの手を適用した新しい状態を返す（thisは変更しない）。 */
        SimBoard copyAndApply(int moveIdx) {
            int size = board.length;
            int r = moveIdx / size;
            int c = moveIdx % size;

            Stone[][] newBoard = new Stone[size][];
            for (int i = 0; i < size; i++) {
                newBoard[i] = board[i].clone();
            }
            Set<String> newDmgMarks = new HashSet<>(dmgMarks);
            Map<String, Boolean> newRemovalEchoes = new HashMap<>(removalEchoes);
            Map<String, Integer> newHp = new HashMap<>(hp);

            MoveResolution res = GameRoom.resolveMove(newBoard, newDmgMarks, newRemovalEchoes, newHp, pending,
                    r, c, mover);

            boolean newGameOver = false;
            String newWinner = null;
            if (newHp.get("B") <= 0 && newHp.get("W") <= 0) {
                newGameOver = true;
                newWinner = "draw";
            } else if (newHp.get("B") <= 0) {
                newGameOver = true;
                newWinner = "W";
            } else if (newHp.get("W") <= 0) {
                newGameOver = true;
                newWinner = "B";
            }
            String nextMover = newGameOver ? mover : ("B".equals(mover) ? "W" : "B");

            return new SimBoard(newBoard, newDmgMarks, newRemovalEchoes, newHp, res.pendingAfter(), nextMover,
                    newGameOver, newWinner);
        }
    }
}
