package com.marketintel.marketintelligenceagent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface MarketEventRepository extends JpaRepository<MarketEvent, Long> {

    List<MarketEvent> findByStatus(EventStatus status);

    // Atomic state transition for a single row: only succeeds if the current status is `from`.
    // Returns the number of rows updated (0 or 1).
    @Modifying
    @Transactional
    @Query("UPDATE MarketEvent e SET e.status = :to, e.statusUpdatedAt = CURRENT_TIMESTAMP " +
           "WHERE e.id = :id AND e.status = :from")
    int transition(@Param("id") Long id,
                   @Param("from") EventStatus from,
                   @Param("to") EventStatus to);

    // Reconciler: move rows stuck in `from` for longer than `minutes` back to `to`.
    // The threshold is computed with the database clock (now()), the same clock that wrote
    // status_updated_at, so JVM vs. DB time zones can never disagree.
    @Modifying
    @Transactional
    @Query(value = "UPDATE market_events SET status = :to, status_updated_at = now() " +
                   "WHERE status = :from AND status_updated_at < now() - make_interval(mins => :minutes)",
           nativeQuery = true)
    int resetStale(@Param("from") String from,
                   @Param("to") String to,
                   @Param("minutes") int minutes);
}
