package com.yonmoku.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LearningServiceの自己対戦バッチ・重みの永続化・GomokuAiへの反映を検証する。 */
class LearningServiceTest {

    @Test
    void trainingBatchProducesASummaryWithDecisiveOutcomes(@TempDir Path dir) {
        LearningService service = new LearningService(dir.resolve("ai_weights.json").toString(), new ObjectMapper());

        TrainingSummary summary = service.runTrainingBatch(6);

        assertEquals(6, summary.games());
        assertEquals(6, summary.challengerWins() + summary.championWins() + summary.draws(),
                "全対局の内訳の合計が対局数と一致するはず");
        assertNotNull(summary.currentWeights());
    }

    @Test
    void promotingAChallengerUpdatesGomokuAisLearnedWeights(@TempDir Path dir) {
        LearningService service = new LearningService(dir.resolve("ai_weights.json").toString(), new ObjectMapper());

        TrainingSummary summary = service.runTrainingBatch(10);

        assertEquals(service.getChampionWeights(), GomokuAi.getLearnedWeights(),
                "LearningServiceのチャンピオン重みは常にGomokuAiの学習済み重みと一致するはず");
        if (summary.promoted()) {
            assertEquals(1, service.getGeneration());
        } else {
            assertEquals(0, service.getGeneration());
        }
    }

    @Test
    void weightsPersistAcrossServiceInstancesWhenPromoted(@TempDir Path dir) {
        Path file = dir.resolve("ai_weights.json");
        ObjectMapper mapper = new ObjectMapper();

        LearningService first = new LearningService(file.toString(), mapper);
        TrainingSummary summary = null;
        // 変異は乱数依存で毎回勝敗が決まるとは限らないため、昇格が起きるまで数回試す。
        for (int i = 0; i < 20 && (summary == null || !summary.promoted()); i++) {
            summary = first.runTrainingBatch(6);
        }
        assertTrue(summary.promoted(), "20回試して一度も挑戦者が昇格しなかった（自己対戦の勝敗ロジックを確認）");

        LearningService second = new LearningService(file.toString(), mapper);
        assertEquals(first.getChampionWeights(), second.getChampionWeights(),
                "新しいインスタンスでもファイルから保存済みの重みが読み込まれるはず");
    }
}
