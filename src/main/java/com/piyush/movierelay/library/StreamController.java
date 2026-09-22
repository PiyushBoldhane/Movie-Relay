package com.piyush.movierelay.library;

import com.piyush.movierelay.telegram.DownloadService;
import com.piyush.movierelay.web.ApiException;
import it.tdlight.jni.TdApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
public class StreamController {

    private static final long DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB, if the client doesn't ask for a specific range

    private final DownloadedFileRepository downloadedFileRepository;
    private final DownloadService downloadService;
    private final Path audioVariantsDir;

    public StreamController(DownloadedFileRepository downloadedFileRepository, DownloadService downloadService,
                             @Value("${tdlib.data-path}") String dataPath) {
        this.downloadedFileRepository = downloadedFileRepository;
        this.downloadService = downloadService;
        this.audioVariantsDir = Path.of(dataPath, "downloads", "audio-variants");
    }

    @GetMapping("/api/stream/{libraryId}")
    public ResponseEntity<byte[]> stream(@PathVariable long libraryId,
                                          @RequestHeader(value = "Range", required = false) String rangeHeader) {
        DownloadedFile record = downloadedFileRepository.findById(libraryId)
                .orElseThrow(() -> new ApiException("file_not_found", HttpStatus.NOT_FOUND,
                        "No downloaded file with id " + libraryId));

        // A user-selected non-default audio track is served from its remuxed variant (video +
        // just that audio track) instead of the original file - only possible once the
        // download is complete, since the variant is derived from the finished file.
        Path audioVariant = record.isCompleted() && record.getActiveAudioTrackIndex() != null
                ? audioVariantsDir.resolve(libraryId + "_track" + record.getActiveAudioTrackIndex() + ".mkv")
                : null;
        boolean usingVariant = audioVariant != null && Files.exists(audioVariant);

        long totalSize;
        try {
            totalSize = usingVariant ? Files.size(audioVariant) : record.getSizeBytes();
        } catch (IOException e) {
            totalSize = record.getSizeBytes();
            usingVariant = false;
        }

        long[] range = parseRange(rangeHeader, totalSize);
        long start = range[0];
        long endInclusive = range[1];

        String localPath;
        if (usingVariant) {
            localPath = audioVariant.toString();
        } else {
            localPath = record.getLocalPath();
            if (!record.isCompleted()) {
                TdApi.File file = downloadService.awaitByteRangeAvailable(libraryId, start, endInclusive + 1, 30);
                if (file == null || !isByteAvailable(file, start, endInclusive) || file.local.path.isBlank()) {
                    // Still not there after waiting - tell the client to back off and retry
                    // shortly, rather than serving incomplete/wrong data.
                    throw new ApiException("range_not_ready", HttpStatus.SERVICE_UNAVAILABLE,
                            "That part of the file hasn't downloaded yet. Please retry shortly.");
                }
                // The DB record's localPath is only refreshed on completion, so while the
                // download is still in progress we must read TDLib's current working path
                // instead - it can differ (and even be empty) earlier in the download.
                localPath = file.local.path;
            }
        }

        Path path = Path.of(localPath);
        long chunkLength = endInclusive - start + 1;

        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            byte[] buffer = new byte[(int) chunkLength];
            raf.seek(start);
            raf.readFully(buffer);

            MediaType mediaType = record.getMimeType() != null
                    ? MediaType.parseMediaType(record.getMimeType())
                    : MediaType.APPLICATION_OCTET_STREAM;

            HttpStatus status = rangeHeader != null ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;

            return ResponseEntity.status(status)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + record.getDisplayName() + "\"")
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + endInclusive + "/" + totalSize)
                    .contentType(mediaType)
                    .contentLength(chunkLength)
                    .body(buffer);
        } catch (IOException e) {
            throw new ApiException("stream_read_failed", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read the file for streaming", e);
        }
    }

    private boolean isByteAvailable(TdApi.File file, long startByte, long endByteInclusive) {
        TdApi.LocalFile local = file.local;
        if (local.isDownloadingCompleted) return true;
        long availableStart = local.downloadOffset;
        long availableEnd = local.downloadOffset + local.downloadedPrefixSize;
        return startByte >= availableStart && endByteInclusive < availableEnd;
    }

    /**
     * Parses a "Range: bytes=start-end" header. If absent, defaults to a bounded chunk from
     * the start rather than "the whole file", so an initial request from a fresh player
     * doesn't have to wait for the entire (possibly multi-GB) file to be available.
     */
    private long[] parseRange(String rangeHeader, long totalSize) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            long end = Math.min(DEFAULT_CHUNK_SIZE - 1, totalSize - 1);
            return new long[]{0, end};
        }

        String spec = rangeHeader.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        long start = parts[0].isBlank() ? 0 : Long.parseLong(parts[0]);
        long end;
        if (parts.length > 1 && !parts[1].isBlank()) {
            end = Math.min(Long.parseLong(parts[1]), totalSize - 1);
        } else {
            end = Math.min(start + DEFAULT_CHUNK_SIZE - 1, totalSize - 1);
        }
        return new long[]{start, end};
    }
}
