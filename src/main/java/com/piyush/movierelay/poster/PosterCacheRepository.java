package com.piyush.movierelay.poster;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PosterCacheRepository extends JpaRepository<PosterCache, Long> {

    Optional<PosterCache> findBySearchKey(String searchKey);
}
