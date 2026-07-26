package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.AdvisorActionDto;
import com.asecon.enterpriseiq.dto.AdvisorActionFollowUpDto;
import com.asecon.enterpriseiq.model.AdvisorActionFollowUp;
import com.asecon.enterpriseiq.model.AdvisorActionFollowUpStatus;
import com.asecon.enterpriseiq.model.AdvisorRecommendation;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.AdvisorActionFollowUpRepository;
import com.asecon.enterpriseiq.repo.AdvisorRecommendationRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdvisorActionFollowUpService {
    private static final TypeReference<List<AdvisorActionDto>> ACTION_LIST = new TypeReference<>() {};

    private final AdvisorActionFollowUpRepository followUpRepository;
    private final AdvisorRecommendationRepository recommendationRepository;
    private final ObjectMapper objectMapper;

    public AdvisorActionFollowUpService(AdvisorActionFollowUpRepository followUpRepository,
                                        AdvisorRecommendationRepository recommendationRepository,
                                        ObjectMapper objectMapper) {
        this.followUpRepository = followUpRepository;
        this.recommendationRepository = recommendationRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<AdvisorActionFollowUp> syncForRecommendation(AdvisorRecommendation recommendation) {
        if (recommendation == null || recommendation.getCompany() == null || recommendation.getCompany().getId() == null) {
            return List.of();
        }
        String period = normalizePeriod(recommendation.getPeriod());
        String source = normalizeSource(recommendation.getSource());
        Long companyId = recommendation.getCompany().getId();
        List<AdvisorActionDto> actions = parseActions(recommendation);
        List<AdvisorActionFollowUp> existing = followUpRepository.findByCompany_IdAndPeriodAndSourceOrderByCreatedAtAsc(companyId, period, source);
        Map<String, AdvisorActionFollowUp> existingByKey = new HashMap<>();
        for (AdvisorActionFollowUp item : existing) {
            existingByKey.put(item.getActionKey(), item);
        }

        Map<String, AdvisorActionFollowUp> previousOpenByKey = new HashMap<>();
        List<AdvisorActionFollowUp> previousOpen = followUpRepository.findByCompany_IdAndSourceAndPeriodLessThanAndStatusInOrderByPeriodDescUpdatedAtDesc(
            companyId,
            source,
            period,
            EnumSet.of(AdvisorActionFollowUpStatus.PENDING, AdvisorActionFollowUpStatus.IN_PROGRESS)
        );
        for (AdvisorActionFollowUp item : previousOpen) {
            previousOpenByKey.putIfAbsent(item.getActionKey(), item);
        }

        List<AdvisorActionFollowUp> out = new ArrayList<>();
        Instant now = Instant.now();
        Map<String, Boolean> currentKeys = new HashMap<>();

        for (int i = 0; i < actions.size(); i++) {
            AdvisorActionDto action = actions.get(i);
            String actionKey = buildActionKey(action);
            currentKeys.put(actionKey, true);
            AdvisorActionFollowUp entity = existingByKey.get(actionKey);
            if (entity == null) {
                entity = new AdvisorActionFollowUp();
                entity.setCompany(recommendation.getCompany());
                entity.setPeriod(period);
                entity.setSource(source);
                entity.setActionKey(actionKey);
                entity.setCreatedAt(now);

                AdvisorActionFollowUp previous = previousOpenByKey.get(actionKey);
                if (previous != null) {
                    entity.setStatus(previous.getStatus());
                    entity.setCarriedOver(true);
                    entity.setOriginFollowUp(previous);
                } else {
                    entity.setStatus(AdvisorActionFollowUpStatus.PENDING);
                    entity.setCarriedOver(false);
                }
            }
            applyActionData(entity, recommendation, i, action, now);
            out.add(followUpRepository.save(entity));
        }

        for (AdvisorActionFollowUp previous : previousOpenByKey.values()) {
            if (currentKeys.containsKey(previous.getActionKey())) continue;
            AdvisorActionFollowUp existingCarry = existingByKey.get(previous.getActionKey());
            AdvisorActionFollowUp entity = existingCarry == null ? new AdvisorActionFollowUp() : existingCarry;
            if (existingCarry == null) {
                entity.setCompany(recommendation.getCompany());
                entity.setPeriod(period);
                entity.setSource(source);
                entity.setActionKey(previous.getActionKey());
                entity.setCreatedAt(now);
                entity.setStatus(previous.getStatus());
                entity.setCarriedOver(true);
                entity.setOriginFollowUp(previous);
            }
            entity.setRecommendationSnapshot(recommendation);
            entity.setActionIndex(null);
            entity.setHorizon(previous.getHorizon());
            entity.setPriority(previous.getPriority());
            entity.setTitle(previous.getTitle());
            entity.setDetail(previous.getDetail());
            entity.setKpi(previous.getKpi());
            entity.setUpdatedAt(now);
            if (entity.getStatus() == AdvisorActionFollowUpStatus.RESOLVED && entity.getResolvedAt() == null) {
                entity.setResolvedAt(now);
            }
            out.add(followUpRepository.save(entity));
        }

        out.sort((a, b) -> {
            int byStatus = statusRank(a.getStatus()) - statusRank(b.getStatus());
            if (byStatus != 0) return byStatus;
            int byPriority = priorityRank(a.getPriority()) - priorityRank(b.getPriority());
            if (byPriority != 0) return byPriority;
            return String.valueOf(a.getTitle()).compareToIgnoreCase(String.valueOf(b.getTitle()));
        });
        return out;
    }

    @Transactional
    public List<AdvisorActionFollowUpDto> listForPeriod(Long companyId, String period, String objective) {
        String resolvedPeriod = normalizePeriod(period);
        String source = RecommendationObjective.toSource(objective);
        List<AdvisorActionFollowUp> items = followUpRepository.findByCompany_IdAndPeriodAndSourceOrderByCreatedAtAsc(companyId, resolvedPeriod, source);
        if (items.isEmpty()) {
            recommendationRepository.findByCompany_IdAndPeriodAndSource(companyId, resolvedPeriod, source)
                .ifPresent(this::syncForRecommendation);
            items = followUpRepository.findByCompany_IdAndPeriodAndSourceOrderByCreatedAtAsc(companyId, resolvedPeriod, source);
        }
        List<AdvisorActionFollowUpDto> out = new ArrayList<>(items.size());
        for (AdvisorActionFollowUp item : items) {
            out.add(toDto(item));
        }
        out.sort((a, b) -> {
            int byStatus = statusRank(parseStatus(a.status())) - statusRank(parseStatus(b.status()));
            if (byStatus != 0) return byStatus;
            int byPriority = priorityRank(a.priority()) - priorityRank(b.priority());
            if (byPriority != 0) return byPriority;
            if (a.actionIndex() != null && b.actionIndex() != null) return Integer.compare(a.actionIndex(), b.actionIndex());
            if (a.actionIndex() != null) return -1;
            if (b.actionIndex() != null) return 1;
            return String.valueOf(a.title()).compareToIgnoreCase(String.valueOf(b.title()));
        });
        return out;
    }

    @Transactional
    public AdvisorActionFollowUpDto updateStatus(Long companyId, Long followUpId, String status, User actor) {
        if (actor == null || (actor.getRole() != Role.ADMIN && actor.getRole() != Role.CONSULTOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo consultoria puede actualizar el seguimiento.");
        }
        AdvisorActionFollowUp entity = followUpRepository.findByIdAndCompany_Id(followUpId, companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Accion de seguimiento no encontrada."));
        AdvisorActionFollowUpStatus next = parseStatus(status);
        entity.setStatus(next);
        entity.setUpdatedAt(Instant.now());
        entity.setResolvedAt(next == AdvisorActionFollowUpStatus.RESOLVED ? entity.getUpdatedAt() : null);
        return toDto(followUpRepository.save(entity));
    }

    private void applyActionData(AdvisorActionFollowUp entity,
                                 AdvisorRecommendation recommendation,
                                 Integer actionIndex,
                                 AdvisorActionDto action,
                                 Instant now) {
        entity.setRecommendationSnapshot(recommendation);
        entity.setActionIndex(actionIndex);
        entity.setHorizon(trimToNull(action.horizon()));
        entity.setPriority(trimToNull(action.priority()));
        entity.setTitle(defaultText(action.title(), "Accion consultiva"));
        entity.setDetail(trimToNull(action.detail()));
        entity.setKpi(trimToNull(action.kpi()));
        entity.setUpdatedAt(now);
        if (entity.getStatus() == AdvisorActionFollowUpStatus.RESOLVED && entity.getResolvedAt() == null) {
            entity.setResolvedAt(now);
        }
    }

    private List<AdvisorActionDto> parseActions(AdvisorRecommendation recommendation) {
        try {
            List<AdvisorActionDto> actions = objectMapper.readValue(recommendation.getActionsJson(), ACTION_LIST);
            return actions == null ? List.of() : actions;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private AdvisorActionFollowUpDto toDto(AdvisorActionFollowUp item) {
        return new AdvisorActionFollowUpDto(
            item.getId(),
            item.getCompany() == null ? null : item.getCompany().getId(),
            item.getRecommendationSnapshot() == null ? null : item.getRecommendationSnapshot().getId(),
            item.getPeriod(),
            item.getSource(),
            item.getActionIndex(),
            item.getActionKey(),
            item.getHorizon(),
            item.getPriority(),
            item.getTitle(),
            item.getDetail(),
            item.getKpi(),
            item.getStatus() == null ? null : item.getStatus().name(),
            item.isCarriedOver(),
            item.getOriginFollowUp() == null ? null : item.getOriginFollowUp().getId(),
            item.getOriginFollowUp() == null ? null : item.getOriginFollowUp().getPeriod(),
            item.getCreatedAt(),
            item.getUpdatedAt(),
            item.getResolvedAt()
        );
    }

    private static String buildActionKey(AdvisorActionDto action) {
        String raw = String.join("|",
            normalize(action.priority()),
            normalize(action.horizon()),
            normalize(action.title()),
            normalize(action.detail()),
            normalize(action.kpi())
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar action key", e);
        }
    }

    private static String normalize(String text) {
        return String.valueOf(text == null ? "" : text).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizePeriod(String period) {
        return (period == null || period.isBlank()) ? YearMonth.now().toString() : period.trim();
    }

    private static String normalizeSource(String source) {
        return (source == null || source.isBlank()) ? "RULES" : source.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultText(String value, String fallback) {
        String normalized = trimToNull(value);
        return normalized == null ? fallback : normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static AdvisorActionFollowUpStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de seguimiento no valido.");
        }
        try {
            return AdvisorActionFollowUpStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de seguimiento no valido.");
        }
    }

    private static int statusRank(AdvisorActionFollowUpStatus status) {
        if (status == AdvisorActionFollowUpStatus.PENDING) return 0;
        if (status == AdvisorActionFollowUpStatus.IN_PROGRESS) return 1;
        if (status == AdvisorActionFollowUpStatus.RESOLVED) return 2;
        return 9;
    }

    private static int priorityRank(String priority) {
        String value = String.valueOf(priority == null ? "" : priority).trim().toUpperCase(Locale.ROOT);
        if ("HIGH".equals(value) || "ALTA".equals(value)) return 0;
        if ("MEDIUM".equals(value) || "MEDIA".equals(value)) return 1;
        if ("LOW".equals(value) || "BAJA".equals(value)) return 2;
        return 9;
    }
}
