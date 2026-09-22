package com.piyush.movierelay.telegram;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of structured fields (season/episode/resolution/language/size) out
 * of a movie bot's free-text button label. Labels are inconsistently formatted across bots
 * and uploads, so this is deliberately forgiving - any field it can't find is left null, and
 * the raw label text is always kept as the source of truth on the caller's side.
 */
final class LabelParser {

    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "S(\\d{1,2})\\s*E(\\d{1,3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEASON_ONLY = Pattern.compile(
            "Season\\s*(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOLUTION = Pattern.compile(
            "(480p|720p|1080p|2160p|4k)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SIZE = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s?(?:GB|MB))", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANGUAGE = Pattern.compile(
            "(Hindi Dual Audio|Dual Audio|Hindi|English|Tamil|Telugu|Korean|Bengali|Marathi|Punjabi)",
            Pattern.CASE_INSENSITIVE);

    private LabelParser() {
    }

    static BotReplyButton.ParsedLabel parse(String label) {
        if (label == null || label.isBlank()) {
            return new BotReplyButton.ParsedLabel(null, null, null, null, null);
        }

        Integer season = null;
        Integer episode = null;

        Matcher seasonEpisode = SEASON_EPISODE.matcher(label);
        if (seasonEpisode.find()) {
            season = Integer.parseInt(seasonEpisode.group(1));
            episode = Integer.parseInt(seasonEpisode.group(2));
        } else {
            Matcher seasonOnly = SEASON_ONLY.matcher(label);
            if (seasonOnly.find()) {
                season = Integer.parseInt(seasonOnly.group(1));
            }
        }

        String resolution = firstMatch(RESOLUTION, label);
        String sizeText = firstMatch(SIZE, label);
        String language = normalizeLanguage(firstMatch(LANGUAGE, label));

        return new BotReplyButton.ParsedLabel(season, episode, resolution, language, sizeText);
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    // Labels are matched case-insensitively, but the matched substring keeps whatever casing
    // the uploader used ("hindi", "HINDI", "Hindi", ...), which would otherwise fragment the
    // language filter into duplicate tabs for the same language. Normalize to a single
    // canonical casing per word so every variant collapses into one filter tab.
    private static String normalizeLanguage(String raw) {
        if (raw == null) return null;
        String[] words = raw.toLowerCase().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
