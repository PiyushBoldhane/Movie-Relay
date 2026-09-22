package com.piyush.movierelay.subtitle;

/**
 * One embedded audio stream found inside a downloaded video file. ffmpegStreamIndex is the
 * index ffprobe/ffmpeg use to select this exact stream (its index within the whole file, not
 * 0-based among audio tracks alone).
 */
public record AudioTrack(int ffmpegStreamIndex, String language, String title) {
}
