package com.yonmoku.game;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * バックアタックの補正値（+1／+2）は、それが付いた着手で除外が同時に発生した場合にしか
 * 効果を持たず、除外を伴わず石が盤上に残った場合は次の着手までに消えて、後の別の除外には
 * 影響しないことを検証する。GameRoom.resolveMoveを直接呼び出し、盤面・保留ダメージ・
 * 除外あとマークの状態を狙って作ることで、自然対局では作りにくいピンポイントの局面を再現する。
 */
class BackAttackBonusTest {

    @Test
    void backAttackBonusDoesNotCarryOverToALaterUnrelatedRemoval() {
        Stone[][] board = new Stone[9][9];
        Set<String> dmgMarks = new HashSet<>();
        Map<String, Boolean> removalEchoes = new HashMap<>();
        removalEchoes.put("0,0", false); // 除外あとマーク（軽減+1相当）
        Map<String, Integer> hp = new HashMap<>();
        hp.put("B", 6);
        hp.put("W", 6);

        // Bに3点の保留ダメージがある状態で、除外あとマークの上に着手（除外は発生しない＝軽減のみ）。
        MoveResolution first = GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp,
                new Pending("W", "B", 3), 0, 0, "B");
        assertNull(first.removalResult(), "この着手単独では除外は発生しないはず");
        assertEquals(1, first.backAttackBonus(), "軽減用の補正値(+1)がこの着手には付くはず");
        assertEquals("B", first.damagedColor());
        assertEquals(2, first.damageAmount(), "保留3点のうち1点軽減されて2点になるはず");
        assertEquals(4, hp.get("B"), "6-2=4");

        // その後、(0,0)を含む形で4つ並びを完成させる（pendingなしの通常の着手を3回）。
        MoveResolution second = GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 1, "B");
        assertNull(second.removalResult());
        MoveResolution third = GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 2, "B");
        assertNull(third.removalResult());
        MoveResolution fourth = GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 3, "B");

        assertEquals(4, fourth.removalResult().total(),
                "(0,0)の古いバックアタック補正が次の着手までに消え、無関係な後の除外には影響しないはず");
    }

    @Test
    void backAttackBonusAppliesWhenRemovalHappensInTheSameMove() {
        Stone[][] board = new Stone[9][9];
        Set<String> dmgMarks = new HashSet<>();
        Map<String, Boolean> removalEchoes = new HashMap<>();
        Map<String, Integer> hp = new HashMap<>();
        hp.put("B", 6);
        hp.put("W", 6);

        // (0,3)にダメージマーク付きの除外あとマーク（+2相当）を用意し、そこへの着手で
        // 4つ並びの除外と保留ダメージの相殺が同時に起きる「4+1攻撃」を再現する。
        GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 0, "B");
        GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 1, "B");
        GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp, null, 0, 2, "B");
        removalEchoes.put("0,3", true); // wasDmg=true → バックアタック+2

        MoveResolution fourth = GameRoom.resolveMove(board, dmgMarks, removalEchoes, hp,
                new Pending("W", "B", 4), 0, 3, "B");

        assertEquals(6, fourth.removalResult().total(), "4個除外+マーク付きバックアタック+2で合計6");
        assertEquals("W", fourth.damagedColor(), "保留4点を6点の相殺量が上回り、差分がWに反撃されるはず");
        assertEquals(2, fourth.damageAmount(), "6-4=2");
        assertEquals(4, hp.get("W"), "6-2=4");
        assertEquals(6, hp.get("B"), "Bは反撃側なのでダメージを受けない");
    }
}
