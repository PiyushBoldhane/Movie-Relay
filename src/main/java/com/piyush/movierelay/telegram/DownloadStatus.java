package com.piyush.movierelay.telegram;

public record DownloadStatus(long libraryId, long sizeBytes, long downloadedBytes, int percentComplete,
                              boolean completed, boolean paused, String localPath) {

    public static DownloadStatus of(long libraryId, long sizeBytes, long downloadedBytes, boolean completed,
                                     boolean paused, String localPath) {
        int percent = sizeBytes <= 0
                ? (completed ? 100 : 0)
                : (int) Math.min(100, (downloadedBytes * 100) / sizeBytes);
        return new DownloadStatus(libraryId, sizeBytes, downloadedBytes, percent, completed, paused, localPath);
    }
}
