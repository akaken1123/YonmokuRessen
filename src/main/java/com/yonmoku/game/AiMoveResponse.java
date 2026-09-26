package com.yonmoku.game;

/** 内蔵AIが選んだ手（0始まりの行・列）。合法手が無い場合は両方null。 */
public record AiMoveResponse(Integer row, Integer col) {
}
