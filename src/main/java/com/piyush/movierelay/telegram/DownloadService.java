package com.piyush.movierelay.telegram;

import com.piyush.movierelay.library.DownloadedFile;
import com.piyush.movierelay.library.DownloadedFileRepository;
import com.piyush.movierelay.web.ApiException;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Manages downloading and streaming files via TDLib.
 * <p>
 * IMPORTANT: TdApi.File.id ("tdlibFileId") is only valid for the lifetime of the TDLib client
 * session that produced it - TDLib can silently reassign that integer to a completely
 * different file once a new session starts (confirmed against TDLib's own documentation and
 * known issues; see DownloadedFile's class comment). Every public method here therefore takes
 * our own stable libraryId (DownloadedFile.id) and resolves a trustworthy tdlibFileId
 * internally via resolveTdlibFile before touching TDLib, rather than trusting a value that may
 * have been cached from a previous session.
 */
@Service
public class DownloadService {

    private static final Logger log = LoggerFactory.getLogger(DownloadService.class);

    private final TelegramClientService telegramClientService;
    private final DownloadedFileRepository downloadedFileRepository;

    // Both maps are keyed by tdlibFileId and are only ever trustworthy for the CURRENT TDLib
    // session - they're rebuilt from scratch (empty) on every restart, and entries are only
    // added via resolveTdlibFile or a live UpdateFile, never by trusting a stored DB value.
    private final Map<Integer, TdApi.File> latestFileState = new ConcurrentHashMap<>();
    private final Map<Integer, List<RangeWaiter>> rangeWaiters = new ConcurrentHashMap<>();
    // Tracks which libraryId each tdlibFileId currently belongs to, for the CURRENT session
    // only - populated exclusively by our own code (beginTdlibDownload/resolveTdlibFile) when
    // WE start tracking a specific record's download, never inferred from TDLib data. This is
    // what onUpdateFile uses to find the record a completion applies to: looking it up by
    // scanning the database for a matching tdlibFileId (as a naive implementation might) is
    // unsafe, since TDLib can and does reassign a numeric file id to a completely unrelated
    // file once the original is free (e.g. reused for a profile photo fetch) - a DB scan would
    // silently overwrite a different, unrelated record's path in that case.
    private final Map<Integer, Long> tdlibFileIdToLibraryId = new ConcurrentHashMap<>();
    private volatile boolean handlerRegistered = false;

    private record RangeWaiter(long startByte, long endByteExclusive, CompletableFuture<TdApi.File> future) {
    }

    public DownloadService(TelegramClientService telegramClientService, DownloadedFileRepository downloadedFileRepository) {
        this.telegramClientService = telegramClientService;
        this.downloadedFileRepository = downloadedFileRepository;
    }

    private SimpleTelegramClient client() {
        return telegramClientService.getClient();
    }

    /**
     * Verifies every record marked "completed" still has its file on disk. TDLib's own file
     * management (or a lost/interrupted process) can leave the database claiming a download
     * is ready when the physical file is actually gone - without this check the UI would
     * silently say "Ready" and playback would just fail with no explanation. Any record whose
     * file is missing gets flipped back to not-completed with its path cleared, so it's
     * visible as broken (it will need a fresh download to become playable again).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyLibraryFilesExist() {
        List<DownloadedFile> completedRecords = downloadedFileRepository.findAll().stream()
                .filter(DownloadedFile::isCompleted)
                .toList();

        for (DownloadedFile record : completedRecords) {
            String path = record.getLocalPath();
            if (path == null || path.isBlank() || !Files.exists(Path.of(path))) {
                log.warn("Library record for '{}' (id {}) claims to be completed but its file is missing at '{}' - marking as unavailable",
                        record.getDisplayName(), record.getId(), path);
                record.setCompleted(false);
                record.setLocalPath("");
                downloadedFileRepository.save(record);
            }
        }
    }

    /**
     * Re-issues DownloadFile to TDLib for every record that was actively downloading (not
     * paused, not completed) when the app last stopped. TDLib's active transfer state lives in
     * the TDLib client process itself, not in our database - a restart (including a DevTools
     * hot-reload) leaves these downloads looking "in progress" in the DB while TDLib has
     * actually gone completely idle on them, silently stalling at 0 bytes/sec until something
     * calls DownloadFile again. Without this, that only ever happened when the user manually
     * hit Resume.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInProgressDownloads() {
        List<DownloadedFile> inProgress = downloadedFileRepository.findAll().stream()
                .filter(r -> !r.isCompleted() && !r.isPaused())
                .toList();

        for (DownloadedFile record : inProgress) {
            try {
                TdApi.File file = resolveTdlibFile(record);
                beginTdlibDownload(record, file.local.downloadOffset, 0);
                log.info("Resumed in-progress download for '{}' (id {}) on startup", record.getDisplayName(), record.getId());
            } catch (Exception e) {
                log.warn("Could not resume in-progress download for '{}' (id {}) on startup: {}",
                        record.getDisplayName(), record.getId(), e.toString());
            }
        }
    }

    private void ensureHandlerRegistered() {
        if (!handlerRegistered) {
            synchronized (this) {
                if (!handlerRegistered) {
                    client().addUpdateHandler(TdApi.UpdateFile.class, this::onUpdateFile);
                    handlerRegistered = true;
                }
            }
        }
    }

    private void onUpdateFile(TdApi.UpdateFile update) {
        latestFileState.put(update.file.id, update.file);

        if (update.file.local.isDownloadingCompleted) {
            Long libraryId = tdlibFileIdToLibraryId.get(update.file.id);
            if (libraryId != null) {
                downloadedFileRepository.findById(libraryId).ifPresent(record -> {
                    if (!record.isCompleted()) {
                        record.setCompleted(true);
                        record.setLocalPath(update.file.local.path);
                        downloadedFileRepository.save(record);
                        log.info("Download completed for '{}' (id {}) at {}", record.getDisplayName(), record.getId(), update.file.local.path);
                    }
                });
            }
        }

        List<RangeWaiter> waiters = rangeWaiters.get(update.file.id);
        if (waiters != null && !waiters.isEmpty()) {
            waiters.removeIf(waiter -> {
                if (update.file.local.isDownloadingCompleted || isRangeAvailable(update.file, waiter.startByte(), waiter.endByteExclusive())) {
                    waiter.future().complete(update.file);
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * True if bytes [startByte, endByteExclusive) are currently readable from disk. TDLib
     * downloads a single contiguous region starting at file.local.downloadOffset and
     * extending for file.local.downloadedPrefixSize bytes - a byte is only safe to read if it
     * falls entirely inside that region. Requesting a range elsewhere in the file (e.g. near
     * the end, for MKV metadata) moves that region rather than adding a second one, so only
     * one contiguous window needs to be tracked at a time.
     */
    private boolean isRangeAvailable(TdApi.File file, long startByte, long endByteExclusive) {
        TdApi.LocalFile local = file.local;
        long availableStart = local.downloadOffset;
        long availableEnd = local.downloadOffset + local.downloadedPrefixSize;
        return startByte >= availableStart && endByteExclusive <= availableEnd;
    }

    /**
     * Returns a TdApi.File for this record that is guaranteed valid in the CURRENT TDLib
     * session. If we already have a live, current-session entry for its tdlibFileId, that's
     * reused as-is. Otherwise (first use in this process, or after a restart) the stored
     * tdlibFileId cannot be trusted, so this resolves a fresh one via GetRemoteFile using the
     * record's remoteFileId, and updates the record if the id changed.
     */
    private TdApi.File resolveTdlibFile(DownloadedFile record) {
        // Only trust the cache if WE are the one it's mapped to - it may hold a live entry for
        // this numeric id that actually belongs to a different record now (ids get reused).
        TdApi.File cached = latestFileState.get(record.getTdlibFileId());
        if (cached != null && record.getId().equals(tdlibFileIdToLibraryId.get(record.getTdlibFileId()))) {
            return cached;
        }

        if (record.getRemoteFileId() == null || record.getRemoteFileId().isBlank()) {
            // Records created before this fix has no remote id to resolve from - nothing we
            // can safely do but surface that clearly.
            throw new ApiException("file_unresolvable", HttpStatus.GONE,
                    "'" + record.getDisplayName() + "' can no longer be resolved (no remote reference on file). Please delete and re-download it.");
        }

        ensureHandlerRegistered();
        try {
            TdApi.File fresh = client().send(new TdApi.GetRemoteFile(record.getRemoteFileId(), null)).get(15, TimeUnit.SECONDS);
            latestFileState.put(fresh.id, fresh);
            tdlibFileIdToLibraryId.put(fresh.id, record.getId());
            if (fresh.id != record.getTdlibFileId()) {
                log.info("Resolved fresh tdlibFileId {} for '{}' (was {})", fresh.id, record.getDisplayName(), record.getTdlibFileId());
                record.setTdlibFileId(fresh.id);
                downloadedFileRepository.save(record);
            }
            return fresh;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("download_wait_interrupted", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while resolving '" + record.getDisplayName() + "'", e);
        } catch (Exception e) {
            throw new ApiException("file_unresolvable", HttpStatus.GONE,
                    "Could not resolve '" + record.getDisplayName() + "' with Telegram. It may no longer be accessible.", e);
        }
    }

    private DownloadedFile requireRecord(long libraryId) {
        return downloadedFileRepository.findById(libraryId)
                .orElseThrow(() -> new ApiException("file_not_found", HttpStatus.NOT_FOUND,
                        "No downloaded file with id " + libraryId));
    }

    /**
     * Registers a freshly detected file and starts (or resumes) downloading it, returning our
     * own stable libraryId. If we already have a completed download with the same
     * remoteUniqueId (the bot can hand out a different tdlibFileId for what is really the same
     * underlying file on a later click), this reuses that existing local copy instead of
     * downloading it again.
     */
    public long startDownload(DetectedFile detected) {
        ensureHandlerRegistered();

        Optional<DownloadedFile> existing = detected.remoteUniqueId() != null && !detected.remoteUniqueId().isBlank()
                ? downloadedFileRepository.findByRemoteUniqueId(detected.remoteUniqueId())
                : Optional.empty();

        if (existing.isPresent()) {
            DownloadedFile record = existing.get();
            if (record.isCompleted()) {
                return record.getId();
            }
            // Keep our record's tdlibFileId/remoteFileId current - this click gave us a fresh one.
            record.setTdlibFileId(detected.tdlibFileId());
            record.setRemoteFileId(detected.remoteFileId());
            record.setPaused(false);
            downloadedFileRepository.save(record);
            latestFileState.remove(detected.tdlibFileId());
            beginTdlibDownload(record, 0, 0);
            return record.getId();
        }

        DownloadedFile record = new DownloadedFile(detected.tdlibFileId(), detected.remoteUniqueId(), detected.remoteFileId(),
                detected.fileName(), detected.mimeType(), detected.sizeBytes(), "", false, Instant.now());
        record = downloadedFileRepository.save(record);
        beginTdlibDownload(record, 0, 0);
        return record.getId();
    }

    private void beginTdlibDownload(DownloadedFile record, long offset, long limit) {
        // Priority 32 (TDLib's max) matches what awaitByteRangeAvailable already uses for
        // active playback - priority 1 (TDLib's min, used here previously) visibly throttled
        // background downloads well below what Telegram's own client achieves on the same
        // connection, since TDLib schedules lower-priority transfers behind everything else.
        TdApi.DownloadFile request = new TdApi.DownloadFile(record.getTdlibFileId(), 32, offset, limit, false);
        try {
            TdApi.File file = client().send(request).get(15, TimeUnit.SECONDS);
            latestFileState.put(file.id, file);
            tdlibFileIdToLibraryId.put(file.id, record.getId());
            log.info("Started download for '{}' ({} bytes)", record.getDisplayName(), file.size);

            record.setLocalPath(file.local.path);
            record.setCompleted(file.local.isDownloadingCompleted);
            downloadedFileRepository.save(record);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("download_start_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while starting the download", e);
        } catch (Exception e) {
            throw new ApiException("download_start_failed", HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start downloading '" + record.getDisplayName() + "'", e);
        }
    }

    /**
     * Resumes a paused (or otherwise stalled) download.
     */
    public DownloadStatus resumeDownload(long libraryId) {
        DownloadedFile record = requireRecord(libraryId);
        if (record.isCompleted()) {
            return toStatus(libraryId, record, resolveTdlibFile(record));
        }
        record.setPaused(false);
        downloadedFileRepository.save(record);
        beginTdlibDownload(record, 0, 0);
        return getStatus(libraryId);
    }

    /**
     * Blocks (up to timeoutSeconds) until bytes [startByte, endByteExclusive) of this file are
     * available to read from disk. If they aren't available yet, reprioritizes the download to
     * fetch exactly that range next (true random access - not always from byte 0), which is
     * what lets playback start on a file that's still downloading even when the player needs
     * to read near the end first (e.g. MKV's metadata/seek index). Returns the current
     * TdApi.File state once satisfied (or on timeout, whatever the latest state is - callers
     * should re-check before trusting it).
     */
    public TdApi.File awaitByteRangeAvailable(long libraryId, long startByte, long endByteExclusive, int timeoutSeconds) {
        DownloadedFile record = requireRecord(libraryId);
        TdApi.File current = resolveTdlibFile(record);

        if (current.local.isDownloadingCompleted || isRangeAvailable(current, startByte, endByteExclusive)) {
            return current;
        }

        int fileId = current.id;
        CompletableFuture<TdApi.File> future = new CompletableFuture<>();
        rangeWaiters.computeIfAbsent(fileId, id -> new CopyOnWriteArrayList<>())
                .add(new RangeWaiter(startByte, endByteExclusive, future));

        // Re-check after registering, in case the range became available between our first
        // check and registering the waiter (a benign race, not a correctness issue - just
        // avoids waiting on an update that already happened).
        current = latestFileState.get(fileId);
        if (current.local.isDownloadingCompleted || isRangeAvailable(current, startByte, endByteExclusive)) {
            rangeWaiters.get(fileId).remove(new RangeWaiter(startByte, endByteExclusive, future));
            return current;
        }

        long length = endByteExclusive - startByte;
        TdApi.DownloadFile request = new TdApi.DownloadFile(fileId, 32, startByte, length, false);
        try {
            client().send(request).get(15, TimeUnit.SECONDS);
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("download_wait_interrupted", HttpStatus.SERVICE_UNAVAILABLE,
                    "Interrupted while waiting for more of the file to download", e);
        } catch (Exception e) {
            log.warn("Timed out or failed waiting for bytes up to {} of file {}: {}", endByteExclusive, fileId, e.toString());
            return latestFileState.get(fileId);
        }
    }

    public DownloadStatus getStatus(long libraryId) {
        DownloadedFile record = requireRecord(libraryId);

        if (record.isCompleted()) {
            return DownloadStatus.of(libraryId, record.getSizeBytes(), record.getSizeBytes(), true, false, record.getLocalPath());
        }
        if (record.isPaused()) {
            return DownloadStatus.of(libraryId, record.getSizeBytes(), 0, false, true, null);
        }

        // Only trust latestFileState if it's actually still ours - tdlibFileId can be silently
        // reassigned to a different in-flight download (see resolveTdlibFile), and reading it
        // unguarded here would report a completely different file's progress as this one's.
        TdApi.File cached = latestFileState.get(record.getTdlibFileId());
        if (cached != null && record.getId().equals(tdlibFileIdToLibraryId.get(record.getTdlibFileId()))) {
            return DownloadStatus.of(libraryId, cached.size, cached.local.downloadedSize,
                    cached.local.isDownloadingCompleted, false,
                    cached.local.isDownloadingCompleted ? cached.local.path : null);
        }

        // Not tracked in this process (e.g. after a restart, before anything has touched this
        // file yet, or its tdlibFileId was reassigned elsewhere) - report the last known
        // progress from the DB rather than resolving with TDLib just to answer a status poll.
        return DownloadStatus.of(libraryId, record.getSizeBytes(), 0, false, false, null);
    }

    /**
     * Pauses an in-progress download: cancels the active TDLib transfer but keeps the
     * library record (marked paused) and whatever bytes were already downloaded, so it can be
     * resumed later without losing progress.
     */
    public void pauseDownload(long libraryId) {
        DownloadedFile record = requireRecord(libraryId);

        if (record.isCompleted()) {
            throw new ApiException("already_completed", HttpStatus.CONFLICT,
                    "This download already finished, there's nothing to pause.");
        }

        TdApi.File current = latestFileState.get(record.getTdlibFileId());
        if (current != null) {
            try {
                client().send(new TdApi.CancelDownloadFile(current.id, false)).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to pause TDLib download for '{}': {}", record.getDisplayName(), e.toString());
            }
        }

        record.setPaused(true);
        downloadedFileRepository.save(record);
        log.info("Paused download for '{}' (id {})", record.getDisplayName(), libraryId);
    }

    /**
     * Cancels an in-progress download and removes its library record (there's no complete
     * file worth keeping). Use deleteFromLibrary to remove a completed file instead.
     */
    public void cancelDownload(long libraryId) {
        DownloadedFile record = requireRecord(libraryId);

        if (record.isCompleted()) {
            throw new ApiException("already_completed", HttpStatus.CONFLICT,
                    "This download already finished. Use the library delete endpoint to remove it instead.");
        }

        TdApi.File current = latestFileState.get(record.getTdlibFileId());
        if (current != null) {
            try {
                client().send(new TdApi.CancelDownloadFile(current.id, false)).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to cancel TDLib download for '{}': {}", record.getDisplayName(), e.toString());
            }
            latestFileState.remove(current.id);
            tdlibFileIdToLibraryId.remove(current.id, libraryId);
            List<RangeWaiter> waiters = rangeWaiters.remove(current.id);
            if (waiters != null) {
                waiters.forEach(waiter -> waiter.future().cancel(false));
            }
        }

        downloadedFileRepository.delete(record);
        log.info("Cancelled download and removed library record for '{}' (id {})", record.getDisplayName(), libraryId);
    }

    /**
     * Removes a completed file from the library: deletes its DB record and, only if no other
     * library record still points at the same physical file (our duplicate-download cache can
     * make several records share one file on disk), deletes the file itself.
     */
    public void deleteFromLibrary(long libraryId) {
        DownloadedFile record = requireRecord(libraryId);
        deleteAllForFile(record.getDisplayName(), record.getSizeBytes());
    }

    /**
     * Removes every library record for the same underlying file (our duplicate-download cache
     * can leave several records pointing at one physical file) and deletes the file itself.
     * Used by the grouped library view, where duplicates are shown as a single entry.
     */
    public void deleteAllForFile(String displayName, long sizeBytes) {
        List<DownloadedFile> records = downloadedFileRepository.findByDisplayNameAndSizeBytes(displayName, sizeBytes);
        if (records.isEmpty()) {
            throw new ApiException("file_not_found", HttpStatus.NOT_FOUND,
                    "No library entry found for '" + displayName + "'");
        }

        String localPath = records.stream()
                .map(DownloadedFile::getLocalPath)
                .filter(p -> p != null && !p.isBlank())
                .findFirst()
                .orElse(null);

        for (DownloadedFile record : records) {
            latestFileState.remove(record.getTdlibFileId());
            tdlibFileIdToLibraryId.remove(record.getTdlibFileId(), record.getId());
        }
        downloadedFileRepository.deleteAll(records);

        if (localPath != null) {
            try {
                Files.deleteIfExists(Path.of(localPath));
                log.info("Deleted file from disk: {}", localPath);
            } catch (IOException e) {
                log.warn("Failed to delete file from disk at {}: {}", localPath, e.toString());
            }
        }
    }

    private DownloadStatus toStatus(long libraryId, DownloadedFile record, TdApi.File file) {
        return DownloadStatus.of(
                libraryId,
                file.size,
                file.local.downloadedSize,
                file.local.isDownloadingCompleted,
                record.isPaused(),
                file.local.isDownloadingCompleted ? file.local.path : null
        );
    }
}
