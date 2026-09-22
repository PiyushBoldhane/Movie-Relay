package com.piyush.movierelay.library;

/**
 * Disk usage summary shown in the UI: how much space this app's downloads are taking up, and
 * how much free space remains on the drive they're stored on.
 */
public record StorageInfo(long libraryUsedBytes, long diskFreeBytes, long diskTotalBytes) {
}
