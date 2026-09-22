package com.yonmoku.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RatingServiceのEloレーティング計算・ファイル永続化・同名ガードを検証する。 */
class RatingServiceTest {

    @Test
    void winnerGainsAndLoserLosesRatingFromAnEvenMatch(@TempDir Path dir) {
        RatingService service = new RatingService(dir.resolve("ratings.json").toString(), new ObjectMapper());

        service.recordResult("Alice", "Bob", "B"); // Alice(黒)の勝ち

        List<RatingEntry> board = service.leaderboard();
        assertEquals(2, board.size());
        RatingEntry alice = board.stream().filter(e -> e.nickname().equals("Alice")).findFirst().orElseThrow();
        RatingEntry bob = board.stream().filter(e -> e.nickname().equals("Bob")).findFirst().orElseThrow();

        assertEquals(1516.0, alice.rating(), 0.01, "1500 + 32*(1-0.5) = 1516");
        assertEquals(1484.0, bob.rating(), 0.01, "1500 + 32*(0-0.5) = 1484");
        assertEquals(1, alice.wins());
        assertEquals(1, bob.losses());
        assertTrue(alice.rating() > bob.rating());
    }

    @Test
    void drawMovesBothRatingsHalfway(@TempDir Path dir) {
        RatingService service = new RatingService(dir.resolve("ratings.json").toString(), new ObjectMapper());

        service.recordResult("Alice", "Bob", "draw");

        List<RatingEntry> board = service.leaderboard();
        RatingEntry alice = board.stream().filter(e -> e.nickname().equals("Alice")).findFirst().orElseThrow();
        RatingEntry bob = board.stream().filter(e -> e.nickname().equals("Bob")).findFirst().orElseThrow();

        assertEquals(1500.0, alice.rating(), 0.01, "互角同士の引き分けはレーティング変動なし");
        assertEquals(1500.0, bob.rating(), 0.01);
        assertEquals(1, alice.draws());
        assertEquals(1, bob.draws());
    }

    @Test
    void sameNicknameOnBothSidesIsIgnored(@TempDir Path dir) {
        RatingService service = new RatingService(dir.resolve("ratings.json").toString(), new ObjectMapper());

        service.recordResult("Solo", "Solo", "B");

        assertEquals(0, service.leaderboard().size(), "黒白同じニックネームの対局は反映されない");
    }

    @Test
    void ratingsSurviveAcrossServiceInstancesViaTheFile(@TempDir Path dir) {
        Path file = dir.resolve("ratings.json");
        ObjectMapper mapper = new ObjectMapper();

        RatingService first = new RatingService(file.toString(), mapper);
        first.recordResult("Alice", "Bob", "B");

        RatingService second = new RatingService(file.toString(), mapper);
        List<RatingEntry> board = second.leaderboard();
        assertEquals(2, board.size(), "新しいインスタンスでもファイルから読み込まれるはず");
        assertEquals("Alice", board.get(0).nickname(), "レーティング降順で先頭はAliceのはず");
    }
}
