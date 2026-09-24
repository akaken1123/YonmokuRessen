package com.yonmoku.game;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * YonmokuRessen-Neural-Networkリポジトリ（yonmoku_nn/export.py）でONNX形式に書き出した
 * 学習済みモデルを読み込み、方策ヘッドの出力（盤面81マス分のロジット）から、空いているマスの中で
 * 最も評価の高い手を選ぶ。モデルは起動時に一度だけ読み込む（NeuralAiConfigから呼ばれる）。
 * モデルファイルが無い/読み込みに失敗した場合は静かに「利用不可」の状態になり、AiLevel.NEURALを
 * 選んだ場合のみエラーになる（他のレベルの動作には影響しない）。
 */
final class NeuralAi {

    private static volatile OrtEnvironment environment;
    private static volatile OrtSession session;

    private NeuralAi() {
    }

    static synchronized void configure(String modelPath) {
        close();
        if (modelPath == null || modelPath.isBlank() || !Files.exists(Path.of(modelPath))) {
            return;
        }
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            OrtSession newSession = env.createSession(modelPath, options);
            environment = env;
            session = newSession;
        } catch (OrtException e) {
            environment = null;
            session = null;
        }
    }

    static synchronized void close() {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException ignored) {
                // クローズ失敗は無視して良い（プロセス終了時のリソース解放に過ぎない）。
            }
        }
        session = null;
        environment = null;
    }

    static boolean isAvailable() {
        return session != null;
    }

    /** 空いているマスの中で、方策ロジットが最大のマスを返す。モデル未読み込みならIllegalStateException。 */
    static int[] chooseMove(GameStateSnapshot state, String color) {
        OrtSession activeSession = session;
        OrtEnvironment activeEnv = environment;
        if (activeSession == null || activeEnv == null) {
            throw new IllegalStateException(
                    "neural model is not loaded (set neural.model.file to a valid ONNX model path)");
        }

        int size = state.size();
        float[] input = NeuralEncoder.encode(state, color);

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(activeEnv, FloatBuffer.wrap(input),
                new long[]{1, NeuralEncoder.NUM_PLANES, size, size})) {
            try (OrtSession.Result result = activeSession.run(Collections.singletonMap("board", inputTensor))) {
                float[][] policyLogits = (float[][]) result.get("policy_logits").get().getValue();
                float[] logits = policyLogits[0];

                List<int[]> empties = new ArrayList<>();
                Stone[][] board = state.board();
                for (int r = 0; r < size; r++) {
                    for (int c = 0; c < size; c++) {
                        if (board[r][c] == null) empties.add(new int[]{r, c});
                    }
                }
                if (empties.isEmpty()) return null;

                int[] best = null;
                float bestScore = Float.NEGATIVE_INFINITY;
                for (int[] cell : empties) {
                    float score = logits[cell[0] * size + cell[1]];
                    if (score > bestScore) {
                        bestScore = score;
                        best = cell;
                    }
                }
                return best;
            }
        } catch (OrtException e) {
            throw new RuntimeException("neural inference failed", e);
        }
    }
}
