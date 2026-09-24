package com.yonmoku.game;

/** 学習AIの現在の状態（GET /api/learning/status のレスポンス）。lastSummaryはまだ一度も学習を
 *  実行していない場合はnull。 */
public record LearningStatus(int generation, AiWeights weights, TrainingSummary lastSummary) {
}
