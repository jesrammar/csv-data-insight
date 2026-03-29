package com.asecon.enterpriseiq.repo;

import com.asecon.enterpriseiq.model.MacroSeries;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MacroSeriesRepository extends JpaRepository<MacroSeries, Long> {
    Optional<MacroSeries> findByProviderAndCode(String provider, String code);
}

