package com.piyush.movierelay.poster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/**
 * Caches a TMDB poster lookup result by the cleaned title we searched for, so repeated
 * views of the same movie/show don't re-query TMDB every time. posterUrl is null when TMDB
 * had no match - we still cache that "no result" outcome (with a shorter effective lifetime
 * expectation) to avoid hammering TMDB for titles it simply doesn't have.
 */
@Entity
public class PosterCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String searchKey;

    private String posterUrl;

    @Column(nullable = false)
    private Instant cachedAt;

    protected PosterCache() {
        // required by JPA
    }

    public PosterCache(String searchKey, String posterUrl, Instant cachedAt) {
        this.searchKey = searchKey;
        this.posterUrl = posterUrl;
        this.cachedAt = cachedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSearchKey() {
        return searchKey;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }
}
