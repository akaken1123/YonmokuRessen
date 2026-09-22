package com.yonmoku.game;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 人間プレイヤー同士の対局（両者にニックネームが設定されている対局）の結果から、
 * ニックネームごとのEloレーティングを更新・永続化する。AI対戦・AI対AI観戦は対象外。
 * サーバー再起動をまたいで保持できるよう、更新のたびにJSONファイルへ保存する
 * （対局データ自体はこれまで通りメモリ上のみで、この情報だけ別に永続化する）。
 */
@Service
public class RatingService {

    private static final double DEFAULT_RATING = 1500.0;
    private static final double K_FACTOR = 32.0;

    private final Path filePath;
    private final ObjectMapper mapper;
    private final Map<String, RatingEntry> ratings = new ConcurrentHashMap<>();

    public RatingService(@Value("${ratings.file:ratings.json}") String filePath, ObjectMapper mapper) {
        this.filePath = Path.of(filePath);
        this.mapper = mapper;
        load();
    }

    private synchronized void load() {
        if (!Files.exists(filePath)) return;
        try {
            RatingEntry[] entries = mapper.readValue(filePath.toFile(), RatingEntry[].class);
            for (RatingEntry entry : entries) {
                ratings.put(entry.nickname(), entry);
            }
        } catch (IOException e) {
            // 読み込みに失敗しても起動は継続する（壊れた/存在しないファイルの場合は空の状態から始める）。
        }
    }

    private synchronized void save() {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), leaderboard());
        } catch (IOException e) {
            // 保存に失敗してもアプリの動作は継続する（次回の更新時に再度保存を試みる）。
        }
    }

    /** 対局結果からEloレーティングを更新する。同じニックネームが黒白両方に設定されていた場合は何もしない。 */
    public synchronized void recordResult(String blackNickname, String whiteNickname, String winner) {
        if (blackNickname.equals(whiteNickname)) {
            return;
        }
        RatingEntry black = ratings.computeIfAbsent(blackNickname, n -> new RatingEntry(n, DEFAULT_RATING, 0, 0, 0));
        RatingEntry white = ratings.computeIfAbsent(whiteNickname, n -> new RatingEntry(n, DEFAULT_RATING, 0, 0, 0));

        double blackScore = "B".equals(winner) ? 1.0 : "W".equals(winner) ? 0.0 : 0.5;
        double whiteScore = 1.0 - blackScore;

        double expectedBlack = 1.0 / (1.0 + Math.pow(10, (white.rating() - black.rating()) / 400.0));
        double expectedWhite = 1.0 - expectedBlack;

        double newBlackRating = black.rating() + K_FACTOR * (blackScore - expectedBlack);
        double newWhiteRating = white.rating() + K_FACTOR * (whiteScore - expectedWhite);

        ratings.put(blackNickname, new RatingEntry(blackNickname, newBlackRating,
                black.wins() + ("B".equals(winner) ? 1 : 0),
                black.losses() + ("W".equals(winner) ? 1 : 0),
                black.draws() + ("draw".equals(winner) ? 1 : 0)));
        ratings.put(whiteNickname, new RatingEntry(whiteNickname, newWhiteRating,
                white.wins() + ("W".equals(winner) ? 1 : 0),
                white.losses() + ("B".equals(winner) ? 1 : 0),
                white.draws() + ("draw".equals(winner) ? 1 : 0)));

        save();
    }

    /** レーティング降順の一覧。 */
    public synchronized List<RatingEntry> leaderboard() {
        return ratings.values().stream()
                .sorted(Comparator.comparingDouble(RatingEntry::rating).reversed())
                .collect(Collectors.toList());
    }
}
