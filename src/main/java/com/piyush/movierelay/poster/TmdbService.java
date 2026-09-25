package com.piyush.movierelay.poster;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Looks up a poster image for a cleaned movie/show title via TMDB's /search/multi endpoint,
 * caching results (including "no match found") so the same title is never looked up twice.
 * Entirely optional: if no API key is configured, every lookup just returns empty rather than
 * failing, so the rest of the app works fine without it.
 */
@Service
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbService {

    private static final Logger log = LoggerFactory.getLogger(TmdbService.class);
    private static final String POSTER_BASE_URL = "https://image.tmdb.org/t/p/w342";

    private final TmdbProperties properties;
    private final PosterCacheRepository posterCacheRepository;
    private final RestClient restClient = RestClient.create("https://api.themoviedb.org/3");

    public TmdbService(TmdbProperties properties, PosterCacheRepository posterCacheRepository) {
        this.properties = properties;
        this.posterCacheRepository = posterCacheRepository;
    }

    /**
     * Returns a poster image URL for the given title/year, or empty if TMDB has no match (or
     * lookups are disabled). Cached by the exact title+year combination requested.
     */
    public Optional<String> findPosterUrl(String title, Integer year) {
        if (!properties.isConfigured() || title == null || title.isBlank()) {
            return Optional.empty();
        }

        String searchKey = title.toLowerCase() + (year != null ? " (" + year + ")" : "");

        Optional<PosterCache> cached = posterCacheRepository.findBySearchKey(searchKey);
        if (cached.isPresent()) {
            return Optional.ofNullable(cached.get().getPosterUrl());
        }

        QueryResult result = queryTmdb(title, year);
        if (!result.succeeded()) {
            // A transient failure (network error, TMDB down, etc.) is not the same as TMDB
            // genuinely having no match - caching it would permanently hide a poster that a
            // later, successful lookup could have found. Leave it uncached so the next request
            // for this title tries again.
            return Optional.empty();
        }

        posterCacheRepository.save(new PosterCache(searchKey, result.posterUrl(), Instant.now()));
        return Optional.ofNullable(result.posterUrl());
    }

    private record QueryResult(boolean succeeded, String posterUrl) {
        static QueryResult success(String posterUrl) {
            return new QueryResult(true, posterUrl);
        }
        static QueryResult failed() {
            return new QueryResult(false, null);
        }
    }

    private QueryResult queryTmdb(String title, Integer year) {
        try {
            MultiSearchResponse response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/search/multi")
                            .queryParam("api_key", properties.getApiKey())
                            .queryParam("query", title)
                            .queryParam("include_adult", "false")
                            .build())
                    .retrieve()
                    .body(MultiSearchResponse.class);

            List<MultiSearchResult> results = response == null || response.results == null
                    ? List.of()
                    : response.results.stream()
                            .filter(r -> "movie".equals(r.mediaType) || "tv".equals(r.mediaType))
                            .toList();

            if (results.isEmpty()) {
                return QueryResult.success(null);
            }

            // Prefer a result matching the year we parsed (if any). Without a year to
            // disambiguate, TMDB's own relevance ranking can put an obscure old title ahead of
            // the actual match (e.g. a 1937 film named identically to a popular 2025 show), so
            // fall back to the most popular result that actually has a poster, rather than
            // blindly trusting whatever's first - a match with no poster is useless to us
            // anyway, so skip it in favor of one that has one.
            MultiSearchResult best = null;
            if (year != null) {
                best = results.stream().filter(r -> matchesYear(r, year)).findFirst().orElse(null);
            }
            if (best == null) {
                best = results.stream()
                        .filter(r -> r.posterPath != null && !r.posterPath.isBlank())
                        .max((a, b) -> Double.compare(a.popularity, b.popularity))
                        .orElse(results.get(0));
            }

            if (best.posterPath == null || best.posterPath.isBlank()) {
                return QueryResult.success(null);
            }
            return QueryResult.success(POSTER_BASE_URL + best.posterPath);
        } catch (Exception e) {
            log.warn("TMDB lookup failed for '{}' ({}): {}", title, year, e.toString());
            return QueryResult.failed();
        }
    }

    private boolean matchesYear(MultiSearchResult result, int year) {
        String date = result.releaseDate != null ? result.releaseDate : result.firstAirDate;
        return date != null && date.startsWith(String.valueOf(year));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MultiSearchResponse {
        public List<MultiSearchResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MultiSearchResult {
        @JsonProperty("media_type")
        public String mediaType;
        @JsonProperty("poster_path")
        public String posterPath;
        @JsonProperty("release_date")
        public String releaseDate;
        @JsonProperty("first_air_date")
        public String firstAirDate;
        public double popularity;
    }
}
