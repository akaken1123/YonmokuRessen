package com.yonmoku.game;

/** 着手リクエストのボディ（0始まりの行・列）。 */
public record MoveRequest(int row, int col) {
}
