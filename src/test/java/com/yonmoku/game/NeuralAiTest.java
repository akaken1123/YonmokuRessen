package com.yonmoku.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * モデルファイルが設定されていない/存在しない場合にNeuralAiが「利用不可」の状態になり、
 * chooseMoveを呼ぶと（対局がスタックする前に）明確な例外を投げることを検証する。
 */
class NeuralAiTest {

    @AfterEach
    void resetState() {
        NeuralAi.close();
    }

    @Test
    void isUnavailableWhenNoModelIsConfigured() {
        NeuralAi.configure(null);
        assertFalse(NeuralAi.isAvailable());
        assertThrows(IllegalStateException.class, () -> NeuralAi.chooseMove(dummyState(), "B"));
    }

    @Test
    void isUnavailableWhenModelFileDoesNotExist() {
        NeuralAi.configure("/no/such/model.onnx");
        assertFalse(NeuralAi.isAvailable());
    }

    private GameStateSnapshot dummyState() {
        GameRoom room = new GameRoom("NEURAL_TEST");
        return room.snapshot();
    }
}
