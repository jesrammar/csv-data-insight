package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.MacroObservation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MacroObservationRepository extends JpaRepository<MacroObservation, Long> {
    Optional<MacroObservation> findBySeries_IdAndPeriod(Long seriesId, String period);
    Optional<MacroObservation> findTop1BySeries_IdOrderByPeriodDesc(Long seriesId);
    Optional<MacroObservation> findTop1BySeries_IdAndPeriodLessThanEqualOrderByPeriodDesc(Long seriesId, String period);
    List<MacroObservation> findTop36BySeries_IdOrderByPeriodDesc(Long seriesId);
}

