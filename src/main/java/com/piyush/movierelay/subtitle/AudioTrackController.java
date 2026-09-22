package com.piyush.movierelay.subtitle;

import com.piyush.movierelay.library.DownloadedFile;
import com.piyush.movierelay.library.DownloadedFileRepository;
import com.piyush.movierelay.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
public class AudioTrackController {

    private final DownloadedFileRepository downloadedFileRepository;
    private final SubtitleService subtitleService;
    private final Path audioVariantsDir;

    public AudioTrackController(DownloadedFileRepository downloadedFileRepository, SubtitleService subtitleService,
                                 @Value("${tdlib.data-path}") String dataPath) {
        this.downloadedFileRepository = downloadedFileRepository;
        this.subtitleService = subtitleService;
        this.audioVariantsDir = Path.of(dataPath, "downloads", "audio-variants");
    }

    @GetMapping("/api/stream/{libraryId}/audio-tracks")
    public List<AudioTrack> listAudioTracks(@PathVariable long libraryId) {
        DownloadedFile record = requireCompletedRecord(libraryId);
        List<AudioTrack> tracks = subtitleService.listAudioTracks(Path.of(record.getLocalPath()));
        // Not worth offering a switcher for a file with only one audio track.
        return tracks.size() > 1 ? tracks : List.of();
    }

    /**
     * Selects which embedded audio track should play, remuxing a variant containing just the
     * video stream and that audio track if one doesn't already exist for this (file, track)
     * pair. The player is expected to reload /api/stream/{libraryId} after this call succeeds -
     * that endpoint serves the remuxed variant once one is active.
     */
    @PostMapping("/api/stream/{libraryId}/audio-tracks/{trackIndex}")
    public ResponseEntity<Void> selectAudioTrack(@PathVariable long libraryId, @PathVariable int trackIndex) {
        DownloadedFile record = requireCompletedRecord(libraryId);
        Path variantPath = variantPath(libraryId, trackIndex);

        try {
            subtitleService.remuxWithAudioTrack(Path.of(record.getLocalPath()), trackIndex, variantPath);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("audio_track_switch_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while preparing that audio track", e);
        } catch (IOException e) {
            throw new ApiException("audio_track_switch_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not switch to that audio track. Is ffmpeg installed?", e);
        }

        record.setActiveAudioTrackIndex(trackIndex);
        downloadedFileRepository.save(record);
        return ResponseEntity.noContent().build();
    }

    /**
     * Reverts to the file as originally downloaded (its default audio track), rather than a
     * remuxed single-track variant.
     */
    @DeleteMapping("/api/stream/{libraryId}/audio-tracks")
    public ResponseEntity<Void> clearAudioTrack(@PathVariable long libraryId) {
        DownloadedFile record = requireCompletedRecord(libraryId);
        record.setActiveAudioTrackIndex(null);
        downloadedFileRepository.save(record);
        return ResponseEntity.noContent().build();
    }

    Path variantPath(long libraryId, int trackIndex) {
        return audioVariantsDir.resolve(libraryId + "_track" + trackIndex + ".mkv");
    }

    private DownloadedFile requireCompletedRecord(long libraryId) {
        DownloadedFile record = downloadedFileRepository.findById(libraryId)
                .orElseThrow(() -> new ApiException("file_not_found", HttpStatus.NOT_FOUND,
                        "No downloaded file with id " + libraryId));
        if (!record.isCompleted() || record.getLocalPath() == null || record.getLocalPath().isBlank()) {
            throw new ApiException("file_not_ready", HttpStatus.CONFLICT,
                    "Audio tracks aren't available until this download finishes.");
        }
        return record;
    }
}
