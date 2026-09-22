package com.piyush.movierelay.subtitle;

/**
 * One embedded subtitle stream found inside a downloaded video file. ffmpegStreamIndex is the
 * index ffprobe/ffmpeg use to select this exact stream (not necessarily 0-based among subtitle
 * tracks alone - it's the stream's index within the whole file).
 */
public record SubtitleTrack(int ffmpegStreamIndex, String language, String title) {
}
