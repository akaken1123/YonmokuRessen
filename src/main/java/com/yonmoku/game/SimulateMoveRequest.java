package com.yonmoku.game;

/** ステートレスな1手シミュレーションのリクエストボディ（0始まりの行・列）。 */
public record SimulateMoveRequest(SimulationState state, int row, int col) {
}
