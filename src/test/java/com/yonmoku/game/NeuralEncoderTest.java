package com.yonmoku.game;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NeuralEncoderの出力が、YonmokuRessen-Neural-Networkリポジトリの
 * yonmoku_nn/encoding.pyのencode_state()と同じ値になることを検証する
 * （同じ入力に対して同じPythonテストで使ったものと同一のアサーションにしてある）。
 * ここが食い違うと、Java側で読み込むONNXモデルの出力は意味を持たなくなる。
 */
class NeuralEncoderTest {

    @Test
    void encodingMatchesThePythonReferenceImplementation() {
        int size = 9;
        Stone[][] board = new Stone[size][size];
        board[3][4] = new Stone("B", true, 0);
        board[3][5] = new Stone("W", false, 2);

        Set<String> dmgMarks = new LinkedHashSet<>(List.of("0,0", "1,1"));
        Map<String, Boolean> removalEchoes = new HashMap<>();
        removalEchoes.put("2,2", false);
        removalEchoes.put("2,3", true);
        Map<String, Integer> hp = new HashMap<>();
        hp.put("B", 4);
        hp.put("W", 6);
        Pending pending = new Pending("W", "B", 3);

        GameStateSnapshot state = new GameStateSnapshot(
                "TEST", size, board, dmgMarks, removalEchoes, "B", hp, pending,
                false, null, 0, List.of(), null, null, null, null, null);

        float[] planes = NeuralEncoder.encode(state, "B");
        assertEquals(NeuralEncoder.NUM_PLANES * size * size, planes.length);

        // 自分(B)の石、ダメージフラグ付き
        assertEquals(1f, at(planes, size, 0, 3, 4));
        assertEquals(1f, at(planes, size, 1, 3, 4));
        // 相手(W)の石、バックアタック補正あり
        assertEquals(1f, at(planes, size, 2, 3, 5));
        assertEquals(1f, at(planes, size, 5, 3, 5));
        // ダメージ増加マーク
        assertEquals(1f, at(planes, size, 6, 0, 0));
        assertEquals(1f, at(planes, size, 6, 1, 1));
        assertEquals(0f, at(planes, size, 6, 3, 4)); // 石が乗っているマスはマークチャンネルに立たない
        // 除外あとマーク
        assertEquals(1f, at(planes, size, 7, 2, 2));
        assertEquals(0f, at(planes, size, 8, 2, 2)); // wasDmg=false
        assertEquals(1f, at(planes, size, 7, 2, 3));
        assertEquals(1f, at(planes, size, 8, 2, 3)); // wasDmg=true
        // HP（自分4/6, 相手6/6）
        assertEquals(4f / 6f, at(planes, size, 9, 0, 0), 1e-6f);
        assertEquals(1.0f, at(planes, size, 10, 0, 0), 1e-6f);
        // 保留ダメージ（自分向け3点、相手向けは0）
        assertEquals(0.3f, at(planes, size, 11, 0, 0), 1e-6f);
        assertEquals(0f, at(planes, size, 12, 0, 0));

        // 相手(W)視点でエンコードすると、自分/相手が入れ替わるはず
        float[] planesW = NeuralEncoder.encode(state, "W");
        assertEquals(1f, at(planesW, size, 0, 3, 5)); // Wから見て自分の石
        assertEquals(1f, at(planesW, size, 2, 3, 4)); // Wから見て相手の石
        assertEquals(1.0f, at(planesW, size, 9, 0, 0), 1e-6f); // Wの残りHP=6/6
        assertEquals(0.3f, at(planesW, size, 12, 0, 0), 1e-6f); // Wから見て「相手への保留」
    }

    private static float at(float[] planes, int size, int plane, int r, int c) {
        return planes[plane * size * size + r * size + c];
    }
}
