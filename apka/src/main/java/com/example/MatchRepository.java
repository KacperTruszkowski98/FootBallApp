package com.example;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MatchRepository extends JpaRepository<MatchEntity, Long> {

    List<MatchEntity> findByCountryAndStartSeasonYear(String country, int startSeasonYear);

    List<MatchEntity> findByCountry(String country);

    @Query("SELECT DISTINCT m.country FROM MatchEntity m ORDER BY m.country ASC")
    List<String> findDistinctCountries();
}