package com.yonmoku.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 学習AI（AiLevel.LEARN）の評価関数の重み（AiWeights）を、GPU等を使わずJavaだけで完結する
 * 軽量な自己対戦で少しずつ調整していくサービス。
 *
 * 仕組み：現在のチャンピオンの重みを軽くランダムに変異させた「挑戦者」を作り、先後を入れ替えながら
 * チャンピオンと何局も自己対戦させる。挑戦者の勝率が5割を超えたら、挑戦者を新しいチャンピオンとして
 * 採用し、ファイル（ai_weights.json）へ保存する。サーバー再起動をまたいで学習結果が保持される。
 * 探索そのものはDEFAULTと同じ2手先読み（軽量）にしてあり、多くの対局を素早く回すことを優先している
 * （深い探索によるAIの強さの向上はTEST系が別途担当する）。
 */
@Service
public class LearningService {

    private static final int MAX_PLIES_PER_GAME = 300;

    private final Path filePath;
    private final ObjectMapper mapper;
    private AiWeights champion;
    private TrainingSummary lastSummary;
    private int generation;

    public LearningService(@Value("${ai.weights.file:ai_weights.json}") String filePath, ObjectMapper mapper) {
        this.filePath = Path.of(filePath);
        this.mapper = mapper;
        this.champion = load();
        GomokuAi.setLearnedWeights(champion);
    }

    private AiWeights load() {
        if (Files.exists(filePath)) {
            try {
                return mapper.readValue(filePath.toFile(), AiWeights.class);
            } catch (IOException e) {
                // 読み込みに失敗した場合はデフォルトの重みから学習をやり直す。
            }
        }
        return AiWeights.defaults();
    }

    private void save() {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), champion);
        } catch (IOException e) {
            // 保存に失敗してもアプリの動作は継続する（次回の学習成功時に再度保存を試みる）。
        }
    }

    /**
     * 自己対戦をgames局行い、挑戦者の勝率が現チャンピオンを上回れば重みを更新する。
     * 先後を1局ごとに入れ替え、先手・後手どちらが有利かの影響を打ち消す。
     */
    public synchronized TrainingSummary runTrainingBatch(int games) {
        AiWeights challenger = champion.mutated(ThreadLocalRandom.current());
        int challengerWins = 0;
        int championWins = 0;
        int draws = 0;

        for (int g = 0; g < games; g++) {
            boolean challengerIsBlack = g % 2 == 0;
            AiWeights blackWeights = challengerIsBlack ? challenger : champion;
            AiWeights whiteWeights = challengerIsBlack ? champion : challenger;
            String winner = playSelfPlayGame(blackWeights, whiteWeights);

            boolean challengerWon = ("B".equals(winner) && challengerIsBlack) || ("W".equals(winner) && !challengerIsBlack);
            boolean championWon = ("B".equals(winner) && !challengerIsBlack) || ("W".equals(winner) && challengerIsBlack);
            if (challengerWon) challengerWins++;
            else if (championWon) championWins++;
            else draws++;
        }

        double challengerScore = (challengerWins + draws * 0.5) / games;
        boolean promoted = challengerScore > 0.5;
        if (promoted) {
            champion = challenger;
            GomokuAi.setLearnedWeights(champion);
            save();
            generation++;
        }

        lastSummary = new TrainingSummary(generation, games, challengerWins, championWins, draws,
                challengerScore, promoted, champion);
        return lastSummary;
    }

    /** 使い捨てのGameRoomで、指定した重み同士の自己対戦を1局最後まで行う。 */
    private String playSelfPlayGame(AiWeights blackWeights, AiWeights whiteWeights) {
        GameRoom room = new GameRoom("TRAIN");
        for (int ply = 0; ply < MAX_PLIES_PER_GAME; ply++) {
            GameStateSnapshot state = room.snapshot();
            if (state.gameOver()) {
                return state.winner();
            }
            AiWeights weights = "B".equals(state.currentPlayer()) ? blackWeights : whiteWeights;
            int[] move = GomokuAi.chooseMoveWeighted(state, state.currentPlayer(), weights, GomokuAi.DEFAULT_CANDIDATES);
            if (move == null) break;
            room.placeStone(move[0], move[1]);
        }
        GameStateSnapshot finalState = room.snapshot();
        return finalState.gameOver() ? finalState.winner() : "draw";
    }

    public synchronized AiWeights getChampionWeights() {
        return champion;
    }

    public synchronized TrainingSummary getLastSummary() {
        return lastSummary;
    }

    public synchronized int getGeneration() {
        return generation;
    }
}
