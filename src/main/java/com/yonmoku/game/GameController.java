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
    public GameStateSnapshot createGame() {
        GameRoom room = gameService.createGame();
        return room.snapshot();
    }

    @GetMapping("/{id}")
    public GameStateSnapshot getGame(@PathVariable String id) {
        return gameService.getGame(id).snapshot();
    }

    @PostMapping("/{id}/move")
    public GameStateSnapshot move(@PathVariable String id, @RequestBody MoveRequest request) {
        GameRoom room = gameService.getGame(id);
        room.placeStone(request.row(), request.col());
        GameStateSnapshot snapshot = room.snapshot();
        broadcast(id, snapshot);
        return snapshot;
    }

    @PostMapping("/{id}/reset")
    public GameStateSnapshot reset(@PathVariable String id) {
        GameRoom room = gameService.getGame(id);
        room.reset();
        GameStateSnapshot snapshot = room.snapshot();
        broadcast(id, snapshot);
        return snapshot;
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
