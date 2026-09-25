package com.yonmoku.game;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;
    private final AiVsAiDriver aiVsAiDriver;
    private final RatingService ratingService;

    public GameController(GameService gameService, SimpMessagingTemplate messagingTemplate, AiVsAiDriver aiVsAiDriver,
                           RatingService ratingService) {
        this.gameService = gameService;
        this.messagingTemplate = messagingTemplate;
        this.aiVsAiDriver = aiVsAiDriver;
        this.ratingService = ratingService;
    }

    /**
     * 対局を作成する。blackAi / whiteAi にレベル名（DEFAULT/TEST/TEST2/TEST3）を指定した色はAIが担当し、
     * 省略した色は人間が操作する。両方指定すればAI対AI（観戦専用）になる。
     */
    @PostMapping
    public GameStateSnapshot createGame(@RequestParam(name = "blackAi", required = false) String blackAi,
                                         @RequestParam(name = "whiteAi", required = false) String whiteAi) {
        GameRoom room = gameService.createGame();
        applyAiConfig(room, blackAi, whiteAi);
        return startOrResolveAiTurns(room);
    }

    /**
     * 既存の対局にAIを割り当てる（作成時にblackAi/whiteAiを指定しなかった場合の後付け用）。
     * 例えば、最初の数手だけ人間役でランダムに打たせてから、残りをAI対AI（観戦専用）に切り替える、
     * といった評価用途（yonmoku_nn.evaluateなど）で使う。blackAi/whiteAiを省略した色はそのまま
     * （既に人間操作中ならそのまま人間操作、既にAIが割り当て済みならそのAIのまま）。
     */
    @PostMapping("/{id}/ai")
    public GameStateSnapshot setAi(@PathVariable String id,
                                    @RequestParam(name = "blackAi", required = false) String blackAi,
                                    @RequestParam(name = "whiteAi", required = false) String whiteAi) {
        GameRoom room = gameService.getGame(id);
        applyAiConfig(room, blackAi, whiteAi);
        broadcast(id, room.snapshot());
        return startOrResolveAiTurns(room);
    }

    @GetMapping("/{id}")
    public GameStateSnapshot getGame(@PathVariable String id) {
        return gameService.getGame(id).snapshot();
    }

    /** 対局開始からの各着手直後の状態を古い順に返す（棋譜検討用）。 */
    @GetMapping("/{id}/history")
    public List<GameStateSnapshot> getHistory(@PathVariable String id) {
        return gameService.getGame(id).getHistory();
    }

    @PostMapping("/{id}/move")
    public GameStateSnapshot move(@PathVariable String id, @RequestBody MoveRequest request) {
        GameRoom room = gameService.getGame(id);
        if (room.isFullyAiControlled()) {
            throw new IllegalStateException("this game is AI vs AI (spectate only)");
        }
        room.placeStone(request.row(), request.col());
        applyRatingUpdateIfConcluded(room);
        broadcast(id, room.snapshot());
        return resolveAiTurns(room);
    }

    /**
     * この対局のレーティング用にニックネームを設定する（AIが担当する色には設定できない）。
     * 対局は人間プレイヤー同士（両者にニックネームが設定されている場合）のみレーティング対象になる。
     */
    @PostMapping("/{id}/nickname")
    public GameStateSnapshot setNickname(@PathVariable String id, @RequestBody NicknameRequest request) {
        GameRoom room = gameService.getGame(id);
        room.setNickname(request.color(), request.nickname());
        GameStateSnapshot state = room.snapshot();
        broadcast(id, state);
        return state;
    }

    @PostMapping("/{id}/reset")
    public GameStateSnapshot reset(@PathVariable String id) {
        GameRoom room = gameService.getGame(id);
        room.reset();
        broadcast(id, room.snapshot());
        return startOrResolveAiTurns(room);
    }

    private void applyAiConfig(GameRoom room, String blackAi, String whiteAi) {
        if (blackAi != null && !blackAi.isBlank()) {
            room.setAi("B", requireAvailable(AiLevel.fromParam(blackAi)));
        }
        if (whiteAi != null && !whiteAi.isBlank()) {
            room.setAi("W", requireAvailable(AiLevel.fromParam(whiteAi)));
        }
    }

    /** NEURALはモデルが読み込まれていないと着手できずスタックしてしまうため、対局作成時点で弾く。 */
    private AiLevel requireAvailable(AiLevel level) {
        if (level == AiLevel.NEURAL && !NeuralAi.isAvailable()) {
            throw new IllegalStateException(
                    "neural AI is not available (no model loaded; set neural.model.file on the server)");
        }
        return level;
    }

    /**
     * AI対AI（観戦専用）なら、以後は人間の操作を待たずに進み続けるバックグラウンドの進行役を起動する。
     * そうでなければ、これまで通りこのリクエストの中でAIの手番を処理する。
     */
    private GameStateSnapshot startOrResolveAiTurns(GameRoom room) {
        if (room.isFullyAiControlled()) {
            aiVsAiDriver.ensureRunning(room);
            return room.snapshot();
        }
        return resolveAiTurns(room);
    }

    /** AIの手番が続く限り、少し間を置きながら着手させてブロードキャストする。 */
    private GameStateSnapshot resolveAiTurns(GameRoom room) {
        GameStateSnapshot state = room.snapshot();
        int guard = 0;
        while (room.isAiTurn() && guard++ < 4) {
            try {
                Thread.sleep(450);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            room.playAiMove();
            applyRatingUpdateIfConcluded(room);
            state = room.snapshot();
            broadcast(room.getId(), state);
        }
        return state;
    }

    /** 対局がちょうど終了した直後であれば（人間プレイヤー同士かつ両者ニックネーム設定済みの場合のみ）、
     *  レーティングへ結果を反映する。それ以外の場合は何もしない。 */
    private void applyRatingUpdateIfConcluded(GameRoom room) {
        RatingUpdate update = room.consumeRatingUpdate();
        if (update != null) {
            ratingService.recordResult(update.blackNickname(), update.whiteNickname(), update.winner());
        }
    }

    private void broadcast(String id, GameStateSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/games/" + id, snapshot);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IndexOutOfBoundsException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
