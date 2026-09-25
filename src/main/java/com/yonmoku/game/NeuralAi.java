package com.yonmoku.game;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * YonmokuRessen-Neural-Networkリポジトリ（yonmoku_nn/export.py）でONNX形式に書き出した
 * 学習済みモデルを読み込み、方策・価値ヘッドの推論を行う。モデルは起動時に読み込まれ、その後も
 * NeuralAiConfigが定期的にモデルファイルの更新を検知して自動で再読み込みする（学習ループで新しい
 * 重みをエクスポートしてもサーバー再起動なしで反映できるようにするため）。
 * モデルファイルが無い/読み込みに失敗した場合は静かに「利用不可」の状態になり、AiLevel.NEURALを
 * 選んだ場合のみエラーになる（他のレベルの動作には影響しない）。
 * configure/closeによるセッションの入れ替えとinferによる推論が同時に起きても安全なように、
 * 読み書きロックで保護する（推論中に古いセッションがcloseされてしまうのを防ぐ）。
 *
 * 着手選択自体（chooseMove）はネットワーク単体の貪欲法ではなく、NeuralMcts（PUCT探索、学習側の
 * 自己対戦 yonmoku_nn/mcts.py と同じアルゴリズム）に委譲する。学習はネットワーク単体ではなく
 * 「ネットワーク+探索」の強さを前提にしているため、対局時にも探索しないと本来の強さが出ない。
 */
final class NeuralAi {

    private static final ReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static OrtEnvironment environment;
    private static OrtSession session;
    private static volatile int mctsSimulations = 200;
    private static volatile double mctsCPuct = 1.5;

    private NeuralAi() {
    }

    /** 対局時のMCTS探索の設定（NeuralAiConfigから起動時に一度だけ呼ばれる）。 */
    static void configureSearch(int simulations, double cPuct) {
        mctsSimulations = simulations;
        mctsCPuct = cPuct;
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

    /** ネットワークの出力そのもの（方策ロジット・価値）。NeuralMcts（葉ノードの評価）が使う。 */
    record Inference(float[] policyLogits, float value) {
    }

    /** 盤面テンソル（NeuralEncoder.encode済み）に対する生の推論。モデル未読み込みならIllegalStateException。 */
    static Inference infer(float[] input, int size) {
        LOCK.readLock().lock();
        try {
            OrtSession activeSession = session;
            OrtEnvironment activeEnv = environment;
            if (activeSession == null || activeEnv == null) {
                throw new IllegalStateException(
                        "neural model is not loaded (set neural.model.file to a valid ONNX model path)");
            }

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(activeEnv, FloatBuffer.wrap(input),
                    new long[]{1, NeuralEncoder.NUM_PLANES, size, size})) {
                try (OrtSession.Result result = activeSession.run(Map.of("board", inputTensor))) {
                    float[][] policyLogits = (float[][]) result.get("policy_logits").get().getValue();
                    float[] valueOut = (float[]) result.get("value").get().getValue();
                    return new Inference(policyLogits[0], valueOut[0]);
                }
            } catch (OrtException e) {
                throw new RuntimeException("neural inference failed", e);
            }
        } finally {
            LOCK.readLock().unlock();
        }
    }

    /** 対局時の着手選択。NeuralMcts（PUCT探索）に委譲する。モデル未読み込みならIllegalStateException。 */
    static int[] chooseMove(GameStateSnapshot state, String color) {
        return NeuralMcts.chooseMove(state, mctsSimulations, mctsCPuct);
    }
}
