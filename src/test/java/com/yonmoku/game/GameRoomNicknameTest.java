package com.yonmoku.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ニックネーム登録の検証（AI担当色への登録禁止・空/長すぎる名前の拒否）と、
 * consumeRatingUpdateが対局終了の瞬間にのみ・両者のニックネームが設定されている場合にのみ
 * 情報を返すこと（2回目以降はnull）を検証する。
 */
class GameRoomNicknameTest {

    @Test
    void cannotSetNicknameForAiControlledColor() {
        GameRoom room = new GameRoom("NICK1");
        room.setAi("W", AiLevel.DEFAULT);
        assertThrows(IllegalStateException.class, () -> room.setNickname("W", "Bob"));
        room.setNickname("B", "Alice"); // 人間側は問題なく設定できる
    }

    @Test
    void blankOrTooLongNicknameIsRejected() {
        GameRoom room = new GameRoom("NICK2");
        assertThrows(IllegalArgumentException.class, () -> room.setNickname("B", "   "));
        assertThrows(IllegalArgumentException.class, () -> room.setNickname("B", "a".repeat(25)));
        room.setNickname("B", "a".repeat(24)); // 境界値はOK
    }

    @Test
    void consumeRatingUpdateOnlyFiresOnceWhenBothNicknamesAreSet() {
        GameRoom room = new GameRoom("NICK3");
        room.setNickname("B", "Alice");
        room.setNickname("W", "Bob");

        assertNull(room.consumeRatingUpdate(), "対局中はまだ何も返さない");

        String winner = playRandomGameToConclusion(room);

        RatingUpdate update = room.consumeRatingUpdate();
        assertEquals("Alice", update.blackNickname());
        assertEquals("Bob", update.whiteNickname());
        assertEquals(winner, update.winner());

        assertNull(room.consumeRatingUpdate(), "2回目の呼び出しはnullのはず");
    }

    @Test
    void consumeRatingUpdateStaysNullWhenOnlyOneNicknameIsSet() {
        GameRoom room = new GameRoom("NICK4");
        room.setNickname("B", "Alice");
        // 白にはニックネームを設定しない

        playRandomGameToConclusion(room);

        assertNull(room.consumeRatingUpdate(), "片方しかニックネームがない対局はレーティング対象外");
    }

    /** 現局面から、両者ともランダムな空きマスへ交互に着手させ続け、対局が終了するまで進める。 */
    private String playRandomGameToConclusion(GameRoom room) {
        Random random = new Random(7);
        for (int ply = 0; ply < 400; ply++) {
            GameStateSnapshot state = room.snapshot();
            if (state.gameOver()) {
                return state.winner();
            }
            List<int[]> empties = new ArrayList<>();
            for (int r = 0; r < state.size(); r++) {
                for (int c = 0; c < state.size(); c++) {
                    if (state.board()[r][c] == null) empties.add(new int[]{r, c});
                }
            }
            if (empties.isEmpty()) break;
            int[] cell = empties.get(random.nextInt(empties.size()));
            room.placeStone(cell[0], cell[1]);
        }
        GameStateSnapshot finalState = room.snapshot();
        if (!finalState.gameOver()) {
            throw new AssertionError("400手以内に対局が終了しなかった");
        }
        return finalState.winner();
    }
}
