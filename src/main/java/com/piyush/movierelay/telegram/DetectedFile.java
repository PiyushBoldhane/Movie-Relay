package com.piyush.movierelay.telegram;

/**
 * Raw file info extracted from a Telegram message, before it's been registered with
 * DownloadService (which assigns it our own stable libraryId). Internal to this package only -
 * tdlibFileId here is session-scoped and must not leak outside.
 */
record DetectedFile(int tdlibFileId, String remoteUniqueId, String remoteFileId,
                     String fileName, String mimeType, long sizeBytes) {
}
