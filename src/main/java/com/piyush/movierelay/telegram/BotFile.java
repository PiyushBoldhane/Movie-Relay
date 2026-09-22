package com.piyush.movierelay.telegram;

/**
 * A file the bot has offered/sent. libraryId is our own stable, permanent identifier (the
 * DownloadedFile database row id) - use this everywhere outside this package (streaming URLs,
 * library actions), never tdlibFileId, which is only valid for the current TDLib session.
 */
public record BotFile(long libraryId, String fileName, String mimeType, long sizeBytes) {
}
