package com.piyush.movierelay.library;

import java.time.Instant;
import java.util.List;

/**
 * A library entry grouped by the underlying physical file (displayName + sizeBytes), since
 * our duplicate-download cache can leave several DownloadedFile rows pointing at the same file
 * on disk. The UI should show one entry per actual file, not one per download attempt;
 * libraryIds carries every DownloadedFile.id that maps to it so a single delete can remove
 * them all. primaryLibraryId is the one to use for playback/status/actions.
 *
 * displayName is the raw filename as stored in the database (kept as-is, since backend dedup
 * lookups match against it) - cleanTitle is a presentation-only label derived from it for the
 * UI, never persisted or used for matching.
 */
public record LibraryEntry(String displayName, String cleanTitle, String mimeType, long sizeBytes,
                            boolean completed, boolean paused, Instant downloadedAt, long primaryLibraryId,
                            List<Long> libraryIds, String posterUrl, double lastPositionSeconds) {
}
