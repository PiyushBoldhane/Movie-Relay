package com.piyush.movierelay.poster;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of a searchable movie/show title (and optional year) from a messy
 * bot-uploaded filename like "Interstellar_2014_BluRay_IMAX_Hindi_English_480p_ESub.mkv".
 * Used to query TMDB for a matching poster - inevitably imperfect given how inconsistently
 * these filenames are formatted, so callers should treat a null/no-match result as normal,
 * not an error.
 */
public final class TitleCleaner {

    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    // A combined/range file covering several episodes, e.g. "S01E01-E12", "S01 E01-12",
    // "Episodes 1-12", "E01-E12". Checked before the single-episode pattern since a single
    // S/E match would otherwise consume the season number and miss the range.
    private static final Pattern SEASON_EPISODE_RANGE = Pattern.compile(
            "S(\\d{1,2})\\s*E?(\\d{1,3})\\s*-\\s*E?(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern EPISODE_RANGE_ONLY = Pattern.compile(
            "Episodes?\\s*(\\d{1,3})\\s*-\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "S(\\d{1,2})\\s*E(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_ONLY = Pattern.compile(
            "Season\\s*(\\d{1,2})|S(\\d{1,2})(?![a-zA-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMBINED_MARKER = Pattern.compile(
            "combined|complete", Pattern.CASE_INSENSITIVE);

    // Anything from the first occurrence of one of these markers onward is noise, not title.
    // Ordered so multi-token markers (episode ranges, S+E) are tried before the bare "S\d{1,2}"
    // fallback, which would otherwise match just the season part and leave "Episodes 1-12" in
    // the title.
    private static final Pattern NOISE_MARKER = Pattern.compile(
            "\\b(480p|720p|1080p|2160p|4k|webrip|web[- ]?dl|bluray|blu[- ]ray|hdrip|hdtv|dvdrip|" +
            "hevc|x264|x265|10bit|esub|esubs|dual audio|hindi|english|tamil|telugu|korean|bengali|" +
            "marathi|punjabi|s\\d{1,2}\\s*e\\d{1,3}(?:\\s*-\\s*e?\\d{1,3})?|" +
            "episodes?\\s*\\d{1,3}\\s*-\\s*\\d{1,3}|season\\s*\\d{1,2}|s\\d{1,2}(?![a-zA-Z0-9])|" +
            "e\\d{1,3}|aac|dd\\d|ddp\\d|\\d+ch|complete|combined)\\b",
            Pattern.CASE_INSENSITIVE);

    private TitleCleaner() {
    }

    public record CleanedTitle(String title, Integer year) {
    }

    /**
     * Season/episode info parsed out of a filename, if any. episodeEnd is only set for a
     * combined/range file (e.g. "S01 E01-E12"); a single-episode file leaves it null.
     */
    public record EpisodeInfo(Integer season, Integer episodeStart, Integer episodeEnd) {
        boolean isRange() {
            return episodeEnd != null;
        }
    }

    public static EpisodeInfo parseEpisodeInfo(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            return new EpisodeInfo(null, null, null);
        }
        String name = rawFileName.replace('_', ' ').replace('.', ' ');

        Matcher range = SEASON_EPISODE_RANGE.matcher(name);
        if (range.find()) {
            return new EpisodeInfo(Integer.parseInt(range.group(1)), Integer.parseInt(range.group(2)),
                    Integer.parseInt(range.group(3)));
        }

        Matcher single = SEASON_EPISODE.matcher(name);
        if (single.find()) {
            Integer season = Integer.parseInt(single.group(1));
            Integer episode = Integer.parseInt(single.group(2));

            // A single S/E match alongside an explicit "combined"/"complete" marker elsewhere
            // in the name (but no literal range syntax) still means "the whole season", just
            // without a parsable end episode - treat it as a range with an unknown end.
            if (COMBINED_MARKER.matcher(name).find()) {
                return new EpisodeInfo(season, episode, episode);
            }
            return new EpisodeInfo(season, episode, null);
        }

        Matcher episodeRangeOnly = EPISODE_RANGE_ONLY.matcher(name);
        if (episodeRangeOnly.find()) {
            Integer season = firstGroupAsInt(SEASON_ONLY, name);
            return new EpisodeInfo(season, Integer.parseInt(episodeRangeOnly.group(1)),
                    Integer.parseInt(episodeRangeOnly.group(2)));
        }

        Integer season = firstGroupAsInt(SEASON_ONLY, name);
        if (season != null) {
            // No episode number at all, but an explicit "combined"/"complete" marker means
            // this file is the whole season - flag it as a range with unknown start/end.
            if (COMBINED_MARKER.matcher(name).find()) {
                return new EpisodeInfo(season, 0, 0);
            }
            return new EpisodeInfo(season, null, null);
        }

        return new EpisodeInfo(null, null, null);
    }

    private static Integer firstGroupAsInt(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return null;
        String group = m.group(1) != null ? m.group(1) : m.group(2);
        return group != null ? Integer.parseInt(group) : null;
    }

    /**
     * Builds the human-friendly label shown in the UI in place of the raw filename, e.g.
     * "Interstellar (2014)", "Breaking Bad - S01 E03", or
     * "Breaking Bad - S01 Episodes 01-12 (Combined)". Falls back to the cleaned title alone
     * when nothing else parses.
     */
    public static String buildDisplayLabel(String rawFileName) {
        CleanedTitle cleaned = clean(rawFileName);
        String title = cleaned.title().isBlank() ? rawFileName : cleaned.title();
        EpisodeInfo info = parseEpisodeInfo(rawFileName);

        StringBuilder sb = new StringBuilder(title);
        if (cleaned.year() != null) {
            sb.append(" (").append(cleaned.year()).append(")");
        }

        if (info.season() != null) {
            sb.append(" - S").append(String.format("%02d", info.season()));
            if (info.isRange()) {
                boolean knownRange = info.episodeStart() != null && info.episodeStart() > 0
                        && !info.episodeStart().equals(info.episodeEnd());
                if (knownRange) {
                    sb.append(" Episodes ").append(String.format("%02d", info.episodeStart()))
                            .append("-").append(String.format("%02d", info.episodeEnd()));
                }
                sb.append(" (Combined)");
            } else if (info.episodeStart() != null) {
                sb.append(" E").append(String.format("%02d", info.episodeStart()));
            }
        }

        return sb.toString();
    }

    public static CleanedTitle clean(String rawFileName) {
        if (rawFileName == null || rawFileName.isBlank()) {
            return new CleanedTitle("", null);
        }

        String name = rawFileName;
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.replace('_', ' ').replace('.', ' ');

        Integer year = null;
        Matcher yearMatcher = YEAR.matcher(name);
        if (yearMatcher.find()) {
            year = Integer.parseInt(yearMatcher.group(1));
        }

        Matcher noiseMatcher = NOISE_MARKER.matcher(name);
        int cutoff = noiseMatcher.find() ? noiseMatcher.start() : name.length();
        if (year != null) {
            int yearIndex = name.indexOf(String.valueOf(year));
            if (yearIndex >= 0) {
                cutoff = Math.min(cutoff, yearIndex);
            }
        }

        String title = name.substring(0, cutoff)
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return new CleanedTitle(title, year);
    }
}
