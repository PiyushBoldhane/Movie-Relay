package com.piyush.movierelay.subtitle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Inspects and extracts data from embedded tracks in downloaded video files via ffmpeg/ffprobe:
 * subtitle tracks (converted to WebVTT, since browsers can't read them straight out of an MKV)
 * and audio tracks (used to remux a copy of the file with a specific audio track selected,
 * since browsers can't switch between embedded audio tracks during playback either). Both are
 * entirely optional - if ffmpeg isn't installed or a file has no such tracks, callers just get
 * an empty list rather than an error, so the rest of the app works fine without it.
 */
@Service
@EnableConfigurationProperties(FfmpegProperties.class)
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private final FfmpegProperties properties;

    public SubtitleService(FfmpegProperties properties) {
        this.properties = properties;
    }

    public List<SubtitleTrack> listSubtitleTracks(Path file) {
        return listStreams(file, "s").stream()
                .map(m -> new SubtitleTrack(m.index(), m.language(), m.title()))
                .toList();
    }

    public List<AudioTrack> listAudioTracks(Path file) {
        return listStreams(file, "a").stream()
                .map(m -> new AudioTrack(m.index(), m.language(), m.title()))
                .toList();
    }

    private record StreamMeta(int index, String language, String title) {
    }

    private List<StreamMeta> listStreams(Path file, String streamSelector) {
        List<StreamMeta> streams = new ArrayList<>();
        try {
            // csv output (one stream per line: index,language,title) avoids needing a JSON
            // parsing dependency just for this one small piece of data.
            Process process = new ProcessBuilder(
                    properties.getFfprobePath(), "-v", "error",
                    "-select_streams", streamSelector,
                    "-show_entries", "stream=index:stream_tags=language,title",
                    "-of", "csv=p=0", file.toString())
                    .redirectErrorStream(false)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    StreamMeta meta = parseCsvLine(line);
                    if (meta != null) streams.add(meta);
                }
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("ffprobe timed out listing streams ({}) for {}", streamSelector, file);
                return List.of();
            }
            return streams;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Could not list streams ({}) for {} (is ffmpeg/ffprobe installed?): {}", streamSelector, file, e.toString());
            return List.of();
        }
    }

    private StreamMeta parseCsvLine(String line) {
        if (line == null || line.isBlank()) return null;
        // ffprobe's csv output quotes fields containing commas but not plain ones - a simple
        // split is sufficient here since language/title values in practice don't contain commas.
        String[] parts = line.split(",", -1);
        if (parts.length == 0) return null;
        try {
            int index = Integer.parseInt(parts[0].trim());
            String language = parts.length > 1 ? blankToNull(parts[1]) : null;
            String title = parts.length > 2 ? blankToNull(String.join(",", Arrays.asList(parts).subList(2, parts.length))) : null;
            return new StreamMeta(index, language, title);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String s) {
        s = s.trim();
        return s.isBlank() ? null : s;
    }

    /**
     * Extracts one subtitle stream (by its ffmpeg stream index, from listSubtitleTracks) as
     * WebVTT, writing directly to the given output stream. A single episode's subtitles convert
     * in well under a second, so this is done synchronously per request rather than cached.
     */
    public void extractAsVtt(Path file, int streamIndex, OutputStream out) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(
                properties.getFfmpegPath(), "-v", "error",
                "-i", file.toString(),
                "-map", "0:" + streamIndex,
                "-c:s", "webvtt",
                "-f", "webvtt", "-")
                .redirectErrorStream(false)
                .start();

        process.getInputStream().transferTo(out);
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("ffmpeg timed out extracting subtitle track " + streamIndex + " from " + file);
        }
        if (process.exitValue() != 0) {
            throw new IOException("ffmpeg exited " + process.exitValue() + " extracting subtitle track " + streamIndex);
        }
    }

    /**
     * Remuxes (repackages, no re-encoding) sourceFile into outputFile containing only the video
     * stream plus the chosen audio stream - browsers can't switch between an MKV's embedded
     * audio tracks during playback, so this is how "pick a language" is implemented. -c copy
     * means this is pure repackaging (typically a couple of seconds even for a full episode),
     * not a slow transcode. No-ops if outputFile already exists, since the same (file, track)
     * pair always produces the same result.
     */
    public void remuxWithAudioTrack(Path sourceFile, int audioStreamIndex, Path outputFile) throws IOException, InterruptedException {
        if (Files.exists(outputFile)) {
            return;
        }
        Path tempOutput = outputFile.resolveSibling(outputFile.getFileName() + ".tmp");
        Files.createDirectories(outputFile.getParent());

        Process process = new ProcessBuilder(
                properties.getFfmpegPath(), "-v", "error", "-y",
                "-i", sourceFile.toString(),
                "-map", "0:v:0",
                "-map", "0:" + audioStreamIndex,
                "-c", "copy",
                // The temp path ends in ".tmp", not ".mkv", so ffmpeg can't infer the container
                // format from the extension - state it explicitly rather than relying on that.
                "-f", "matroska",
                tempOutput.toString())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Files.deleteIfExists(tempOutput);
            throw new IOException("ffmpeg timed out remuxing audio track " + audioStreamIndex + " from " + sourceFile);
        }
        if (process.exitValue() != 0) {
            Files.deleteIfExists(tempOutput);
            throw new IOException("ffmpeg exited " + process.exitValue() + " remuxing audio track " + audioStreamIndex + ": " + output);
        }

        // Write to a .tmp path and rename on success, so a concurrent reader (or a half-failed
        // previous attempt) never sees a partially-written file at the final path.
        Files.move(tempOutput, outputFile);
    }
}
