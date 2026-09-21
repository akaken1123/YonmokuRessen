package com.yonmoku.game;

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

    public GameController(GameService gameService, SimpMessagingTemplate messagingTemplate) {
        this.gameService = gameService;
        this.messagingTemplate = messagingTemplate;
    }

    @PostMapping
    public GameStateSnapshot createGame(@RequestParam(name = "vsAi", defaultValue = "false") boolean vsAi,
                                         @RequestParam(name = "aiColor", defaultValue = "W") String aiColor,
                                         @RequestParam(name = "aiLevel", defaultValue = "DEFAULT") String aiLevel) {
        GameRoom room = gameService.createGame();
        if (vsAi) {
            room.configureAi(true, normalizeColor(aiColor), AiLevel.fromParam(aiLevel));
        }
        return resolveAiTurns(room);
    }

    @GetMapping("/{id}")
    public GameStateSnapshot getGame(@PathVariable String id) {
        return gameService.getGame(id).snapshot();
    }

    @PostMapping("/{id}/move")
    public GameStateSnapshot move(@PathVariable String id, @RequestBody MoveRequest request) {
        GameRoom room = gameService.getGame(id);
        room.placeStone(request.row(), request.col());
        broadcast(id, room.snapshot());
        return resolveAiTurns(room);
    }

    @PostMapping("/{id}/reset")
    public GameStateSnapshot reset(@PathVariable String id) {
        GameRoom room = gameService.getGame(id);
        room.reset();
        broadcast(id, room.snapshot());
        return resolveAiTurns(room);
    }

    private String normalizeColor(String color) {
        String c = color == null ? "" : color.trim().toUpperCase();
        return ("B".equals(c) || "W".equals(c)) ? c : "W";
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
