package com.yonmoku.game;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * YonmokuRessen-Neural-Networkリポジトリ（yonmoku_nn/export.py）でONNX形式に書き出した
 * 学習済みモデルを読み込み、方策ヘッドの出力（盤面81マス分のロジット）から、空いているマスの中で
 * 最も評価の高い手を選ぶ。モデルは起動時に読み込まれ、その後もNeuralAiConfigが定期的に
 * モデルファイルの更新を検知して自動で再読み込みする（学習ループで新しい重みをエクスポートしても
 * サーバー再起動なしで反映できるようにするため）。
 * モデルファイルが無い/読み込みに失敗した場合は静かに「利用不可」の状態になり、AiLevel.NEURALを
 * 選んだ場合のみエラーになる（他のレベルの動作には影響しない）。
 * configure/closeによるセッションの入れ替えとchooseMoveによる推論が同時に起きても安全なように、
 * 読み書きロックで保護する（推論中に古いセッションがcloseされてしまうのを防ぐ）。
 */
final class NeuralAi {

    private static final ReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static OrtEnvironment environment;
    private static OrtSession session;

    private NeuralAi() {
    }

    static void configure(String modelPath) {
        LOCK.writeLock().lock();
        try {
            closeLocked();
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
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    static void close() {
        LOCK.writeLock().lock();
        try {
            closeLocked();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static void closeLocked() {
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
        LOCK.readLock().lock();
        try {
            return session != null;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /** 空いているマスの中で、方策ロジットが最大のマスを返す。モデル未読み込みならIllegalStateException。 */
    static int[] chooseMove(GameStateSnapshot state, String color) {
        LOCK.readLock().lock();
        try {
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
        } finally {
            LOCK.readLock().unlock();
        }
    }
}
