package com.yonmoku.game;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * 両方の色をAIが担当する対局（観戦専用）を、人間の操作なしで自動的に進行させる。
 * 通常の対局はAPIリクエスト（着手・リセット）のたびにAIの応手を挟むだけで済むが、
 * AI対AIでは誰も操作しないため、専用のバックグラウンドループで着手させ続ける必要がある。
 */
@Component
class AiVsAiDriver {

    private static final long THINK_DELAY_MS = 450;

    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "ai-vs-ai-driver");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> runningRoomIds = ConcurrentHashMap.newKeySet();

    AiVsAiDriver(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /** 対局がAI対AIで、まだ進行ループが動いていなければ開始する。すでに動いていれば何もしない。 */
    void ensureRunning(GameRoom room) {
        if (!room.isFullyAiControlled()) return;
        if (!runningRoomIds.add(room.getId())) return;
        executor.submit(() -> runLoop(room));
    }

    private void runLoop(GameRoom room) {
        try {
            while (room.isAiTurn()) {
                try {
                    Thread.sleep(THINK_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                room.playAiMove();
                messagingTemplate.convertAndSend("/topic/games/" + room.getId(), room.snapshot());
            }
        } finally {
            runningRoomIds.remove(room.getId());
        }
    }
}
