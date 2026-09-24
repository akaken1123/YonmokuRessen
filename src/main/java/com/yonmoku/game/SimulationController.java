package com.yonmoku.game;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 対局の登録（GameService）を介さない、ステートレスな1手シミュレーションAPI。
 * MCTS（YonmokuRessen-Neural-Networkリポジトリ側）が、実際の対局を作らずに「もしここに打ったら」を
 * 何度も試すために使う。ゲームルールはGameRoom.applyTurn（実際の対局と共通の唯一の実装）をそのまま
 * 呼ぶだけで、ここでは一切再実装しない。
 */
@RestController
@RequestMapping("/api/simulate")
public class SimulationController {

    @PostMapping("/move")
    public SimulationState applyMove(@RequestBody SimulateMoveRequest request) {
        SimulationState in = request.state();
        int size = in.board().length;
        int r = request.row();
        int c = request.col();
        if (r < 0 || r >= size || c < 0 || c >= size) {
            throw new IndexOutOfBoundsException("out of bounds: " + r + "," + c);
        }
        if (in.gameOver()) {
            throw new IllegalStateException("game already over");
        }
        if (in.board()[r][c] != null) {
            throw new IllegalStateException("cell occupied: " + r + "," + c);
        }

        Stone[][] board = new Stone[size][];
        for (int i = 0; i < size; i++) {
            board[i] = in.board()[i].clone();
        }
        Set<String> dmgMarks = new LinkedHashSet<>(in.dmgMarks());
        Map<String, Boolean> removalEchoes = new LinkedHashMap<>(in.removalEchoes());
        Map<String, Integer> hp = new HashMap<>(in.hp());

        TurnResult turn = GameRoom.applyTurn(board, dmgMarks, removalEchoes, hp, in.pending(),
                in.plyCount(), in.markEventCount(), in.markPerSide(), in.nextMarkEventTurn(),
                in.currentPlayer(), r, c, new Random());

        String nextCurrentPlayer = turn.gameOver() ? in.currentPlayer() : turn.nextPlayer();

        return new SimulationState(board, dmgMarks, removalEchoes, hp, turn.pendingAfter(),
                nextCurrentPlayer, turn.gameOver(), turn.winner(), turn.plyCount(), turn.markEventCount(),
                turn.markPerSide(), turn.nextMarkEventTurn());
    }

    @ExceptionHandler({IllegalStateException.class, IndexOutOfBoundsException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
