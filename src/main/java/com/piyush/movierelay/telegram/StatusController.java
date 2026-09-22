package com.piyush.movierelay.telegram;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {

    private final TelegramClientService telegramClientService;

    public StatusController(TelegramClientService telegramClientService) {
        this.telegramClientService = telegramClientService;
    }

    @GetMapping("/api/status")
    public ConnectionStatus getStatus() {
        return new ConnectionStatus(telegramClientService.isConnected(), telegramClientService.getConnectionStateLabel());
    }
}
