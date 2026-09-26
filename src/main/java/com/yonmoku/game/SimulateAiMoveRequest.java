package com.yonmoku.game;

/**
 * ステートレスな「この局面で内蔵AIならどこに打つか」リクエストボディ。
 * levelはDEFAULT/TEST/TEST2/TEST3/LEARN（AiLevel.fromParamと同じ表記）。
 */
public record SimulateAiMoveRequest(SimulationState state, String level) {
}
