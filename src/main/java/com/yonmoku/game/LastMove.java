package com.yonmoku.game;

import java.util.List;

/**
 * 直近の着手に関する情報。クライアント側で「どこに置かれ、どこが除外されたか」を
 * 盤面の前後比較だけで推測すると、置いた石自身がその場で除外されるケース
 * （除外のほとんどがこれに該当する）を正しく検出できないため、サーバー側から明示する。
 */
public record LastMove(int row, int col, String color, List<String> removedCells) {
}
