package com.yonmoku.game;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning")
public class LearningController {

    private static final int MIN_GAMES = 2;
    private static final int MAX_GAMES = 200;

    private final LearningService learningService;

    public LearningController(LearningService learningService) {
        this.learningService = learningService;
    }

    /** 学習AI（LEARN）の現在の世代・重み・直近の学習結果。 */
    @GetMapping("/status")
    public LearningStatus status() {
        return new LearningStatus(learningService.getGeneration(), learningService.getChampionWeights(),
                learningService.getLastSummary());
    }

    /**
     * 自己対戦による学習を1バッチ実行する。挑戦者（現チャンピオンをランダムに変異させた重み）と
     * チャンピオンをgames局戦わせ、挑戦者の勝率が上回れば重みを更新する。
     */
    @PostMapping("/train")
    public TrainingSummary train(@RequestParam(defaultValue = "20") int games) {
        int capped = Math.max(MIN_GAMES, Math.min(MAX_GAMES, games));
        return learningService.runTrainingBatch(capped);
    }
}
