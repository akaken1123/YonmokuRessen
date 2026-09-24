package com.yonmoku.game;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 起動時にONNXモデル（設定されていれば）をNeuralAiへ読み込ませる。 */
@Component
public class NeuralAiConfig {

    private final String modelFile;

    public NeuralAiConfig(@Value("${neural.model.file:}") String modelFile) {
        this.modelFile = modelFile;
    }

    @PostConstruct
    void load() {
        NeuralAi.configure(modelFile);
    }

    @PreDestroy
    void shutdown() {
        NeuralAi.close();
    }
}
