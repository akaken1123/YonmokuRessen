package com.yonmoku.game;

import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 対局ルームの生成・保持・破棄を担うレジストリ。 */
@Service
public class GameService {

    private static final String ID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 紛らわしい文字を除外
    private static final int ID_LENGTH = 6;
    private static final Duration IDLE_TIMEOUT = Duration.ofHours(6);

    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public GameRoom createGame() {
        String id;
        do {
            id = generateId();
        } while (rooms.putIfAbsent(id, new GameRoom(id)) != null);
        return rooms.get(id);
    }

    public GameRoom getGame(String id) {
        GameRoom room = rooms.get(id.toUpperCase());
        if (room == null) {
            throw new NoSuchElementException("game not found: " + id);
        }
        return room;
    }

    private String generateId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ID_ALPHABET.charAt(random.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    void evictIdleRooms() {
        Instant cutoff = Instant.now().minus(IDLE_TIMEOUT);
        rooms.values().removeIf(room -> room.getLastActivity().isBefore(cutoff));
    }
}
