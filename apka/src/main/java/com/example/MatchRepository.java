package com.example;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface MatchRepository extends JpaRepository<MatchEntity, Long> {

    List<MatchEntity> findByCountryAndStartSeasonYear(String country, int startSeasonYear);

    List<MatchEntity> findByCountry(String country);

    @Query("SELECT DISTINCT m.country FROM MatchEntity m ORDER BY m.country ASC")
    List<String> findDistinctCountries();

    @Query("SELECT m.externalId FROM MatchEntity m WHERE m.externalId IN :ids")
    Set<String> findExistingExternalIds(@Param("ids") Collection<String> ids);

    @Query("""
            SELECT m.country, m.league, m.stage, m.startSeasonYear, m.seasonLabel, COUNT(m)
            FROM MatchEntity m WHERE m.league IS NOT NULL
            GROUP BY m.country, m.league, m.stage, m.startSeasonYear, m.seasonLabel
            """)
    List<Object[]> findCompetitionKeys();

    @Query("""
            SELECT m FROM MatchEntity m
            WHERE m.league IS NOT NULL AND m.country = :country
              AND (:league IS NULL OR m.league = :league)
              AND m.startSeasonYear >= :fromSeason
            """)
    List<MatchEntity> findCompetitionMatches(@Param("country") String country,
                                             @Param("league") String league,
                                             @Param("fromSeason") int fromSeason);
}
