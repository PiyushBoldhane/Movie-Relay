package com.piyush.movierelay.telegram;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;

@RestController
public class SearchController {

    private final MovieBotService movieBotService;
    private final DownloadService downloadService;

    public SearchController(MovieBotService movieBotService, DownloadService downloadService) {
        this.movieBotService = movieBotService;
        this.downloadService = downloadService;
    }

    @GetMapping("/api/search")
    public BotReply search(@RequestParam("q") String query) {
        return movieBotService.search(query);
    }

    @PostMapping("/api/click")
    public BotReply click(@RequestParam("messageId") long messageId,
                           @RequestParam("callbackData") String callbackDataBase64) {
        byte[] callbackData = Base64.getDecoder().decode(callbackDataBase64);
        return movieBotService.clickButton(messageId, callbackData);
    }

    @GetMapping("/api/download-status")
    public DownloadStatus downloadStatus(@RequestParam("libraryId") long libraryId) {
        return downloadService.getStatus(libraryId);
    }

    @DeleteMapping("/api/download/{libraryId}")
    public ResponseEntity<Void> cancelDownload(@PathVariable long libraryId) {
        downloadService.cancelDownload(libraryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/download/{libraryId}/pause")
    public ResponseEntity<Void> pauseDownload(@PathVariable long libraryId) {
        downloadService.pauseDownload(libraryId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/download/{libraryId}/resume")
    public DownloadStatus resumeDownload(@PathVariable long libraryId) {
        return downloadService.resumeDownload(libraryId);
    }
}
