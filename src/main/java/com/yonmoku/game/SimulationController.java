package com.yonmoku.game;

import java.util.HashMap;
import java.util.List;
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

    /**
     * ある局面で、内蔵AI（DEFAULT/TEST/TEST2/TEST3/LEARN）ならどこに打つかだけを返す
     * （実際に着手は適用しない）。強化学習の自己対戦（YonmokuRessen-Neural-Networkリポジトリの
     * rl_selfplay.py）で、ネットワーク同士の対戦だけでなく内蔵AIとの対戦も混ぜられるようにするため。
     * NEURALは（このAPIの利用側が別途ネットワークで着手を選ぶので）意味を持たないが、GomokuAi側の
     * ディスパッチをそのまま使うため呼び出し自体は可能。
     */
    @PostMapping("/ai-move")
    public AiMoveResponse chooseAiMove(@RequestBody SimulateAiMoveRequest request) {
        SimulationState in = request.state();
        if (in.gameOver()) {
            throw new IllegalStateException("game already over");
        }
        AiLevel level = AiLevel.fromParam(request.level());
        GameStateSnapshot snapshot = new GameStateSnapshot(
                "", in.board().length, in.board(), in.dmgMarks(), in.removalEchoes(), in.currentPlayer(),
                in.hp(), in.pending(), in.gameOver(), in.winner(), in.plyCount(), List.of(),
                null, null, null, null, null);

        int[] move = GomokuAi.chooseMove(snapshot, in.currentPlayer(), level);
        if (move == null) {
            return new AiMoveResponse(null, null);
        }
        return new AiMoveResponse(move[0], move[1]);
    }

    @ExceptionHandler({IllegalStateException.class, IndexOutOfBoundsException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
