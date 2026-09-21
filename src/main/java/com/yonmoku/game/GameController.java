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

    public GameController(GameService gameService, SimpMessagingTemplate messagingTemplate, AiVsAiDriver aiVsAiDriver) {
        this.gameService = gameService;
        this.messagingTemplate = messagingTemplate;
        this.aiVsAiDriver = aiVsAiDriver;
    }

    /**
     * 対局を作成する。blackAi / whiteAi にレベル名（DEFAULT/TEST/TEST2）を指定した色はAIが担当し、
     * 省略した色は人間が操作する。両方指定すればAI対AI（観戦専用）になる。
     */
    @PostMapping
    public GameStateSnapshot createGame(@RequestParam(name = "blackAi", required = false) String blackAi,
                                         @RequestParam(name = "whiteAi", required = false) String whiteAi) {
        GameRoom room = gameService.createGame();
        applyAiConfig(room, blackAi, whiteAi);
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
        broadcast(id, room.snapshot());
        return resolveAiTurns(room);
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
            room.setAi("B", AiLevel.fromParam(blackAi));
        }
        if (whiteAi != null && !whiteAi.isBlank()) {
            room.setAi("W", AiLevel.fromParam(whiteAi));
        }
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
            state = room.snapshot();
            broadcast(room.getId(), state);
        }
        return state;
    }

    private void broadcast(String id, GameStateSnapshot snapshot) {
        messagingTemplate.convertAndSend("/topic/games/" + id, snapshot);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IndexOutOfBoundsException.class})
    public ResponseEntity<Map<String, String>> handleBadRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }
}
