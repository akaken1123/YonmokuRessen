package com.yonmoku.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 起動時にONNXモデル（設定されていれば）をNeuralAiへ読み込ませ、対局時のMCTS探索の設定
 * （シミュレーション回数・c_puct）を渡す。
 * また、モデルファイルの更新日時を定期的に監視し、変更を検知したら自動で再読み込みする
 * （学習ループ側でモデルを再エクスポートしても、サーバーを再起動せずに対人戦へ反映できるようにするため）。
 */
@Component
public class NeuralAiConfig {

    private static final Logger log = LoggerFactory.getLogger(NeuralAiConfig.class);

    private final String modelFile;
    private final int mctsSimulations;
    private final double mctsCPuct;
    private volatile FileTime lastLoadedMtime;

    public NeuralAiConfig(@Value("${neural.model.file:}") String modelFile,
                           @Value("${neural.mcts.simulations:200}") int mctsSimulations,
                           @Value("${neural.mcts.c-puct:1.5}") double mctsCPuct) {
        this.modelFile = modelFile;
        this.mctsSimulations = mctsSimulations;
        this.mctsCPuct = mctsCPuct;
    }

    @PostConstruct
    void load() {
        NeuralAi.configure(modelFile);
        NeuralAi.configureSearch(mctsSimulations, mctsCPuct);
        lastLoadedMtime = currentMtime();
    }

    /** モデルファイルの更新日時が前回読み込み時から変わっていれば、再読み込みする。 */
    @Scheduled(fixedDelayString = "${neural.model.reload-check-interval-ms:30000}")
    void reloadIfChanged() {
        FileTime mtime = currentMtime();
        if (mtime == null || mtime.equals(lastLoadedMtime)) {
            return;
        }
        log.info("neural model file changed ({}); reloading", modelFile);
        NeuralAi.configure(modelFile);
        lastLoadedMtime = mtime;
    }

    private FileTime currentMtime() {
        if (modelFile == null || modelFile.isBlank()) {
            return null;
        }
        try {
            return Files.getLastModifiedTime(Path.of(modelFile));
        } catch (IOException e) {
            return null;
        }
    }

    @PreDestroy
    void shutdown() {
        NeuralAi.close();
    }
}
