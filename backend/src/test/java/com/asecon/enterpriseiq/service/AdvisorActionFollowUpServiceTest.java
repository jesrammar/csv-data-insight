package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.model.AdvisorActionFollowUpStatus;
import com.asecon.enterpriseiq.model.AdvisorRecommendation;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.AdvisorActionFollowUpRepository;
import com.asecon.enterpriseiq.repo.AdvisorRecommendationRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AdvisorActionFollowUpServiceTest {
    private static final AtomicLong COMPANY_IDS = new AtomicLong(41_000);
    private static final AtomicLong USER_IDS = new AtomicLong(51_000);

    @Autowired
    private AdvisorActionFollowUpService followUpService;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private AdvisorRecommendationRepository recommendationRepository;

    @Autowired
    private AdvisorActionFollowUpRepository followUpRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void syncForRecommendation_createsAndCarriesOpenActionsToNextPeriod() {
        Company company = createCompany("followup carry");
        AdvisorRecommendation june = recommendationRepository.save(recommendation(
            company,
            "2026-06",
            """
            [
              {"horizon":"30d","priority":"HIGH","title":"Reducir gasto fijo","detail":"Renegociar alquiler","kpi":"cash burn","evidence":[]},
              {"horizon":"60d","priority":"MEDIUM","title":"Subir precio medio","detail":"Revisar tarifas","kpi":"margen","evidence":[]}
            ]
            """
        ));

        followUpService.syncForRecommendation(june);
        var juneItems = followUpService.listForPeriod(company.getId(), "2026-06", null);
        assertThat(juneItems).hasSize(2);
        Long openId = juneItems.get(0).id();

        User actor = createUser("consultor.followup@example.com");
        followUpService.updateStatus(company.getId(), openId, "IN_PROGRESS", actor);

        AdvisorRecommendation july = recommendationRepository.save(recommendation(
            company,
            "2026-07",
            """
            [
              {"horizon":"30d","priority":"HIGH","title":"Reducir gasto fijo","detail":"Renegociar alquiler","kpi":"cash burn","evidence":[]}
            ]
            """
        ));

        followUpService.syncForRecommendation(july);
        var julyItems = followUpService.listForPeriod(company.getId(), "2026-07", null);

        assertThat(julyItems).hasSize(2);
        assertThat(julyItems).anySatisfy(item -> {
            if ("Reducir gasto fijo".equals(item.title())) {
                assertThat(item.status()).isEqualTo("IN_PROGRESS");
                assertThat(item.carriedOver()).isTrue();
                assertThat(item.originFollowUpId()).isEqualTo(openId);
            }
        });
        assertThat(julyItems).anySatisfy(item -> {
            if ("Subir precio medio".equals(item.title())) {
                assertThat(item.status()).isEqualTo("PENDING");
                assertThat(item.carriedOver()).isTrue();
                assertThat(item.originPeriod()).isEqualTo("2026-06");
            }
        });
    }

    @Test
    void updateStatus_marksResolvedTimestamp() {
        Company company = createCompany("followup resolve");
        AdvisorRecommendation recommendation = recommendationRepository.save(recommendation(
            company,
            "2026-08",
            """
            [
              {"horizon":"30d","priority":"HIGH","title":"Acelerar cobros","detail":"Llamadas de seguimiento","kpi":"DSO","evidence":[]}
            ]
            """
        ));
        followUpService.syncForRecommendation(recommendation);
        Long id = followUpRepository.findByCompany_IdAndPeriodAndSourceOrderByCreatedAtAsc(company.getId(), "2026-08", "RULES").get(0).getId();

        var updated = followUpService.updateStatus(company.getId(), id, "RESOLVED", createUser("consultor.resolve@example.com"));

        assertThat(updated.status()).isEqualTo("RESOLVED");
        assertThat(updated.resolvedAt()).isNotNull();
        assertThat(followUpRepository.findById(id).orElseThrow().getStatus()).isEqualTo(AdvisorActionFollowUpStatus.RESOLVED);
    }

    private Company createCompany(String suffix) {
        long id = COMPANY_IDS.incrementAndGet();
        jdbcTemplate.update(
            "insert into companies (id, name, plan) values (?, ?, ?)",
            id,
            "Test " + suffix,
            Plan.GOLD.name()
        );
        return companyRepository.findById(id).orElseThrow();
    }

    private User createUser(String email) {
        long id = USER_IDS.incrementAndGet();
        jdbcTemplate.update(
            "insert into users (id, email, password_hash, role, enabled) values (?, ?, ?, ?, ?)",
            id,
            email,
            "hash",
            Role.CONSULTOR.name(),
            true
        );
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setRole(Role.CONSULTOR);
        user.setEnabled(true);
        return user;
    }

    private static AdvisorRecommendation recommendation(Company company, String period, String actionsJson) {
        AdvisorRecommendation recommendation = new AdvisorRecommendation();
        recommendation.setCompany(company);
        recommendation.setPeriod(period);
        recommendation.setCreatedAt(Instant.now());
        recommendation.setSource("RULES");
        recommendation.setSummary("summary");
        recommendation.setActionsJson(actionsJson);
        return recommendation;
    }
}
