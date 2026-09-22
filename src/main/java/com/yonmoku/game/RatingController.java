package com.yonmoku.game;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    /** レーティング降順のランキング一覧（人間プレイヤー同士の対局のみが対象）。 */
    @GetMapping
    public List<RatingEntry> leaderboard() {
        return ratingService.leaderboard();
    }
}
