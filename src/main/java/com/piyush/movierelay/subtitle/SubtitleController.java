package com.piyush.movierelay.subtitle;

import com.piyush.movierelay.library.DownloadedFile;
import com.piyush.movierelay.library.DownloadedFileRepository;
import com.piyush.movierelay.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
public class SubtitleController {

    private final DownloadedFileRepository downloadedFileRepository;
    private final SubtitleService subtitleService;

    public SubtitleController(DownloadedFileRepository downloadedFileRepository, SubtitleService subtitleService) {
        this.downloadedFileRepository = downloadedFileRepository;
        this.subtitleService = subtitleService;
    }

    /**
     * Lists embedded subtitle tracks available for a downloaded file. Only meaningful once the
     * download has actually completed - reading subtitle data from a partially-downloaded file
     * would be unreliable, so this returns an empty list rather than a half-working result.
     */
    @GetMapping("/api/stream/{libraryId}/subtitles")
    public List<SubtitleTrack> listSubtitles(@PathVariable long libraryId) {
        DownloadedFile record = requireCompletedRecord(libraryId);
        return subtitleService.listSubtitleTracks(Path.of(record.getLocalPath()));
    }

    @GetMapping(value = "/api/stream/{libraryId}/subtitles/{trackIndex}.vtt", produces = "text/vtt")
    public ResponseEntity<byte[]> getSubtitleTrack(@PathVariable long libraryId, @PathVariable int trackIndex) {
        DownloadedFile record = requireCompletedRecord(libraryId);

        try {
            var buffer = new java.io.ByteArrayOutputStream();
            subtitleService.extractAsVtt(Path.of(record.getLocalPath()), trackIndex, buffer);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/vtt"))
                    .body(buffer.toByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("subtitle_extract_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while extracting the subtitle track", e);
        } catch (IOException e) {
            throw new ApiException("subtitle_extract_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not extract that subtitle track. Is ffmpeg installed?", e);
        }
    }

    private DownloadedFile requireCompletedRecord(long libraryId) {
        DownloadedFile record = downloadedFileRepository.findById(libraryId)
                .orElseThrow(() -> new ApiException("file_not_found", HttpStatus.NOT_FOUND,
                        "No downloaded file with id " + libraryId));
        if (!record.isCompleted() || record.getLocalPath() == null || record.getLocalPath().isBlank()) {
            throw new ApiException("file_not_ready", HttpStatus.CONFLICT,
                    "Subtitles aren't available until this download finishes.");
        }
        return record;
    }
}
