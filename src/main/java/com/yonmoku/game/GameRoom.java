package com.yonmoku.game;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * HP制四目並べ、1対局分の状態とルール処理。
 * 元のブラウザ版JavaScript実装（initState/computeRemoval/applyRemoval/placeStone/
 * placeAntiStaleMarks）をそのままJavaに移植したもの。
 */
public final class GameRoom {

    public static final int SIZE = 9;
    private static final int[][] DIRS = {{0, 1}, {1, 0}, {1, 1}, {1, -1}};
    private static final int START_HP = 6;

    private final String id;
    private Stone[][] board;
    private Set<String> dmgMarks;
    private Map<String, Boolean> removalEchoes;
    private String currentPlayer;
    private Map<String, Integer> hp;
    private Pending pending;
    private int markEventCount;
    private int markPerSide;
    private int nextMarkEventTurn;
    private int plyCount;
    private boolean gameOver;
    private String winner;
    private Deque<String> log;
    private Instant lastActivity;
    private boolean aiEnabled;
    private String aiColor;

    public GameRoom(String id) {
        this.id = id;
        reset();
    }

    /** この対局をAI対戦にする（またはAI対戦をやめる）。resetをまたいで有効。 */
    public synchronized void configureAi(boolean enabled, String color) {
        aiEnabled = enabled;
        aiColor = enabled ? color : null;
    }

    public synchronized boolean isAiTurn() {
        return aiEnabled && !gameOver && aiColor != null && aiColor.equals(currentPlayer);
    }

    /** 現在AIの手番であれば、AIに着手させる。手番でなければ何もしない。 */
    public synchronized void playAiMove() {
        if (!isAiTurn()) return;
        int[] move = GomokuAi.chooseMove(snapshot(), aiColor);
        if (move != null) {
            placeStone(move[0], move[1]);
        }
    }

    public synchronized void reset() {
        board = new Stone[SIZE][SIZE];
        dmgMarks = new LinkedHashSet<>();
        removalEchoes = new LinkedHashMap<>();
        currentPlayer = "B";
        hp = new HashMap<>();
        hp.put("B", START_HP);
        hp.put("W", START_HP);
        pending = null;
        markEventCount = 0;
        markPerSide = 1;
        nextMarkEventTurn = 10; // 5巡目＝両者が5回ずつ着手し終えた時点（10手目）
        plyCount = 0;
        gameOver = false;
        winner = null;
        log = new ArrayDeque<>();
        lastActivity = Instant.now();
    }

    private static String opponent(String c) {
        return "B".equals(c) ? "W" : "B";
    }

    private static String colorName(String c) {
        return "B".equals(c) ? "黒" : "白";
    }

    private static String colLabel(int c) {
        return String.valueOf((char) ('A' + c));
    }

    private static String posLabel(int r, int c) {
        return colLabel(c) + (r + 1);
    }

    private static String key(int r, int c) {
        return r + "," + c;
    }

    private static boolean inBounds(int r, int c) {
        return r >= 0 && r < SIZE && c >= 0 && c < SIZE;
    }

    private void placeAntiStaleMarks() {
        List<String> placedInfo = new ArrayList<>();
        for (String color : new String[]{"B", "W"}) {
            List<int[]> stones = new ArrayList<>();
            for (int r = 0; r < SIZE; r++) {
                for (int c = 0; c < SIZE; c++) {
                    Stone s = board[r][c];
                    if (s != null && s.color().equals(color)) {
                        stones.add(new int[]{r, c});
                    }
                }
            }
            if (stones.isEmpty()) continue;

            int placedCount = 0;
            int attempts = 0;
            int maxAttempts = markPerSide * 40;
            while (placedCount < markPerSide && attempts < maxAttempts) {
                attempts++;
                int[] s = stones.get(ThreadLocalRandom.current().nextInt(stones.size()));
                int sr = s[0], sc = s[1];
                List<String> localCandidates = new ArrayList<>();
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        int rr = sr + dr, cc = sc + dc;
                        if (!inBounds(rr, cc)) continue;
                        if (board[rr][cc] != null) continue;
                        String k = key(rr, cc);
                        if (dmgMarks.contains(k) || removalEchoes.containsKey(k)) continue;
                        localCandidates.add(k);
                    }
                }
                if (localCandidates.isEmpty()) continue;
                String chosen = localCandidates.get(ThreadLocalRandom.current().nextInt(localCandidates.size()));
                dmgMarks.add(chosen);
                placedCount++;
            }
            if (placedCount > 0) {
                placedInfo.add(colorName(color) + "側に" + placedCount + "個");
            }
        }
        if (!placedInfo.isEmpty()) {
            log.addFirst("【ダメージ増加マークの設置】" + String.join("、", placedInfo)
                    + "（今後1辺あたり" + markPerSide + "個設置）");
        }
    }

    /** 盤面の状態から除外を計算する（読み取り専用）。AIの先読みシミュレーションからも呼ばれる。 */
    static RemovalResult computeRemoval(Stone[][] board, int r, int c, String color) {
        Set<String> unionCells = new LinkedHashSet<>();
        for (int[] d : DIRS) {
            int dr = d[0], dc = d[1];
            List<int[]> cells = new ArrayList<>();
            cells.add(new int[]{r, c});
            int rr = r + dr, cc = c + dc;
            while (inBounds(rr, cc) && board[rr][cc] != null && board[rr][cc].color().equals(color)) {
                cells.add(new int[]{rr, cc});
                rr += dr;
                cc += dc;
            }
            rr = r - dr;
            cc = c - dc;
            while (inBounds(rr, cc) && board[rr][cc] != null && board[rr][cc].color().equals(color)) {
                cells.add(new int[]{rr, cc});
                rr -= dr;
                cc -= dc;
            }
            if (cells.size() >= 4) {
                for (int[] cell : cells) {
                    unionCells.add(key(cell[0], cell[1]));
                }
            }
        }
        if (unionCells.isEmpty()) return null;

        int baseCount = 0, dmgBonus = 0, backBonus = 0;
        for (String k : unionCells) {
            String[] parts = k.split(",");
            int rr = Integer.parseInt(parts[0]);
            int cc = Integer.parseInt(parts[1]);
            Stone stone = board[rr][cc];
            baseCount++;
            if (stone.dmgFlag()) dmgBonus++;
            if (stone.backAttackBonus() > 0) backBonus += stone.backAttackBonus();
        }
        return new RemovalResult(baseCount, dmgBonus, backBonus, baseCount + dmgBonus + backBonus,
                new ArrayList<>(unionCells));
    }

    private void applyRemoval(RemovalResult result) {
        for (String k : result.cells()) {
            String[] parts = k.split(",");
            int rr = Integer.parseInt(parts[0]);
            int cc = Integer.parseInt(parts[1]);
            boolean wasDmg = board[rr][cc].dmgFlag();
            board[rr][cc] = null;
            removalEchoes.put(k, wasDmg);
        }
    }

    /**
     * 指定マスに現在の手番の色で着手する。
     *
     * @throws IllegalStateException   ゲームが既に終了している、またはマスが埋まっている場合
     * @throws IndexOutOfBoundsException 盤外の座標が指定された場合
     */
    public synchronized void placeStone(int r, int c) {
        if (gameOver) {
            throw new IllegalStateException("game already over");
        }
        if (!inBounds(r, c)) {
            throw new IndexOutOfBoundsException("out of bounds: " + r + "," + c);
        }
        if (board[r][c] != null) {
            throw new IllegalStateException("cell occupied: " + r + "," + c);
        }

        lastActivity = Instant.now();
        String color = currentPlayer;
        String k = key(r, c);
        boolean dmgFlag = false;
        int backAttackBonus = 0;
        if (dmgMarks.contains(k)) {
            dmgFlag = true;
            dmgMarks.remove(k);
        }
        if (removalEchoes.containsKey(k)) {
            backAttackBonus = Boolean.TRUE.equals(removalEchoes.get(k)) ? 2 : 1;
            removalEchoes.remove(k);
        }
        board[r][c] = new Stone(color, dmgFlag, backAttackBonus);

        RemovalResult removalResult = computeRemoval(board, r, c, color);
        if (removalResult != null) {
            applyRemoval(removalResult);
        }

        StringBuilder msg = new StringBuilder(colorName(color) + " が " + posLabel(r, c) + " に着手。");

        if (pending != null && pending.target().equals(color)) {
            if (removalResult != null) {
                int a = pending.amount();
                int b = removalResult.total();
                int diff = a - b;
                int dmg = 0;
                String dmgTarget = null;
                if (diff > 0) {
                    dmg = diff;
                    dmgTarget = color;
                } else if (diff < 0) {
                    dmg = -diff;
                    dmgTarget = pending.source();
                }
                String bonusNote = (removalResult.dmgBonus() + removalResult.backBonus()) > 0
                        ? "(基本" + removalResult.baseCount() + "+マーク" + (removalResult.dmgBonus() + removalResult.backBonus()) + ")"
                        : "";
                msg.append(" 相殺判定：反撃").append(b).append("点").append(bonusNote)
                        .append(" vs 保留").append(a).append("点 → ");
                if (dmg > 0) {
                    hp.put(dmgTarget, hp.get(dmgTarget) - dmg);
                    msg.append(colorName(dmgTarget)).append("に").append(dmg).append("ダメージ。");
                } else {
                    msg.append("完全相殺・ダメージ0。");
                }
                pending = null;
                removalEchoes.clear();
            } else if (backAttackBonus > 0) {
                int dmg = Math.max(0, pending.amount() - 1);
                msg.append(" バックアタック成立（軽減1点）。保留").append(pending.amount())
                        .append("点 → ").append(colorName(color)).append("に").append(dmg).append("ダメージ。");
                if (dmg > 0) hp.put(color, hp.get(color) - dmg);
                pending = null;
                removalEchoes.clear();
            } else {
                int dmg = pending.amount();
                msg.append(" 反撃なし。保留していた").append(dmg).append("ダメージが")
                        .append(colorName(color)).append("に確定。");
                if (dmg > 0) hp.put(color, hp.get(color) - dmg);
                pending = null;
                removalEchoes.clear();
            }
        } else if (removalResult != null) {
            String opp = opponent(color);
            pending = new Pending(color, opp, removalResult.total());
            String bonusNote = (removalResult.dmgBonus() + removalResult.backBonus()) > 0
                    ? "(基本" + removalResult.baseCount() + "+マーク" + (removalResult.dmgBonus() + removalResult.backBonus()) + ")"
                    : "";
            msg.append(" ").append(removalResult.baseCount()).append("目除外").append(bonusNote)
                    .append("＝").append(removalResult.total()).append("点のダメージを保留（")
                    .append(colorName(opp)).append("が次の手番で反撃できれば相殺判定）。");
        }

        log.addFirst(msg.toString());

        String w = null;
        if (hp.get("B") <= 0 && hp.get("W") <= 0) w = "draw";
        else if (hp.get("B") <= 0) w = "W";
        else if (hp.get("W") <= 0) w = "B";
        if (w != null) {
            gameOver = true;
            winner = w;
            log.addFirst("draw".equals(w) ? "── 両者HP0：引き分け！" : "── " + colorName(w) + "の勝利！");
        }

        if (!gameOver) {
            plyCount++;
            if (plyCount == nextMarkEventTurn) {
                placeAntiStaleMarks();
                markEventCount++;
                if (markEventCount % 2 == 0) {
                    markPerSide = Math.min(5, markPerSide + 1);
                }
                nextMarkEventTurn += 6; // 3巡＝6手ごと
            }
            currentPlayer = opponent(color);
        }
    }

    public String getId() {
        return id;
    }

    public synchronized Instant getLastActivity() {
        return lastActivity;
    }

    public synchronized GameStateSnapshot snapshot() {
        Stone[][] boardCopy = new Stone[SIZE][SIZE];
        for (int r = 0; r < SIZE; r++) {
            System.arraycopy(board[r], 0, boardCopy[r], 0, SIZE);
        }
        List<String> logCopy = new ArrayList<>();
        int limit = 0;
        for (String entry : log) {
            if (limit++ >= 60) break;
            logCopy.add(entry);
        }
        return new GameStateSnapshot(
                id,
                SIZE,
                boardCopy,
                new LinkedHashSet<>(dmgMarks),
                new LinkedHashMap<>(removalEchoes),
                currentPlayer,
                new HashMap<>(hp),
                pending,
                gameOver,
                winner,
                plyCount,
                logCopy,
                aiEnabled,
                aiColor
        );
    }
}
