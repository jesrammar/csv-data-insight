package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.UniversalChartDataDto;
import com.asecon.enterpriseiq.dto.UniversalEvidenceDto;
import com.asecon.enterpriseiq.dto.UniversalViewDto;
import com.asecon.enterpriseiq.dto.UniversalViewRequest;
import com.asecon.enterpriseiq.model.Plan;
import com.asecon.enterpriseiq.model.UniversalImport;
import com.asecon.enterpriseiq.model.UniversalView;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.asecon.enterpriseiq.repo.UniversalViewRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.UniversalViewService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/companies/{companyId}/universal")
public class UniversalViewController {
    private final AccessService accessService;
    private final CompanyRepository companyRepository;
    private final UniversalImportRepository universalImportRepository;
    private final UniversalViewRepository universalViewRepository;
    private final UniversalViewService universalViewService;

    public UniversalViewController(AccessService accessService,
                                   CompanyRepository companyRepository,
                                   UniversalImportRepository universalImportRepository,
                                   UniversalViewRepository universalViewRepository,
                                   UniversalViewService universalViewService) {
        this.accessService = accessService;
        this.companyRepository = companyRepository;
        this.universalImportRepository = universalImportRepository;
        this.universalViewRepository = universalViewRepository;
        this.universalViewService = universalViewService;
    }

    @PostMapping("/builder/preview")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalChartDataDto preview(@PathVariable Long companyId,
                                         @RequestParam(name = "importId", required = false) Long importId,
                                         @Valid @RequestBody UniversalViewRequest request) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalViewService.previewSnapshot(companyId, request, importId);
    }

    @PostMapping(value = "/builder/problems.csv", produces = "text/csv")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public ResponseEntity<byte[]> problemsCsv(@PathVariable Long companyId,
                                              @Valid @RequestBody UniversalViewRequest request,
                                              @RequestParam(defaultValue = "100") int limit,
                                              @RequestParam(name = "importId", required = false) Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        byte[] bytes = universalViewService.problemsCsv(companyId, request, limit, importId);
        return ResponseEntity.ok()
            .contentType(new MediaType("text", "csv"))
            .body(bytes);
    }

    @PostMapping("/builder/evidence")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalEvidenceDto evidence(@PathVariable Long companyId,
                                         @RequestParam(name = "importId", required = false) Long importId,
                                         @RequestParam(name = "focusLabel", required = false) String focusLabel,
                                         @RequestParam(defaultValue = "40") int limit,
                                         @Valid @RequestBody UniversalViewRequest request) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        return universalViewService.evidence(companyId, request, focusLabel, limit, importId);
    }

    @GetMapping("/views")
    public List<UniversalViewDto> list(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        return universalViewRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
            .map(this::toDto)
            .collect(Collectors.toList());
    }

    @PostMapping("/views")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UniversalViewDto create(@PathVariable Long companyId,
                                   @RequestParam(name = "importId", required = false) Long importId,
                                   @Valid @RequestBody UniversalViewRequest request) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        var company = companyRepository.findById(companyId).orElseThrow();

        // Snapshot: pin dashboards to the current Universal dataset to avoid "datos antiguos" or drifting results.
        UniversalImport pinned = importId == null
            ? universalImportRepository.findFirstByCompanyIdOrderByCreatedAtDesc(companyId).orElse(null)
            : universalImportRepository.findByIdAndCompanyId(importId, companyId).orElse(null);
        if (pinned == null) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "No hay dataset Universal para guardar el dashboard. Sube un fichero en Universal primero.");
        }

        if (request.getName() == null || request.getName().isBlank()) {
            request.setName("Dashboard " + Instant.now().toString().substring(0, 10));
        }

        UniversalView view = new UniversalView();
        view.setCompany(company);
        view.setName(request.getName().trim());
        view.setType(request.getType() == null ? "TIME_SERIES" : request.getType().trim().toUpperCase());
        view.setConfigJson(universalViewService.encodeConfig(request));
        view.setSourceUniversalImportId(pinned.getId());
        view.setCreatedAt(Instant.now());
        view = universalViewRepository.save(view);
        return toDto(view);
    }

    @PostMapping("/views/{viewId}/data")
    public UniversalChartDataDto data(@PathVariable Long companyId,
                                      @PathVariable Long viewId,
                                      @RequestParam(name = "importId", required = false) Long importId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.BRONZE);
        UniversalView view = universalViewRepository.findByIdAndCompanyId(viewId, companyId)
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Dashboard no encontrado"));
        UniversalViewRequest req = universalViewService.decodeConfig(view.getConfigJson());
        if (req.getType() == null || req.getType().isBlank()) req.setType(view.getType());
        Long src = importId != null ? importId : view.getSourceUniversalImportId();
        UniversalChartDataDto out = universalViewService.previewSnapshot(companyId, req, src);
        if (src == null) return out;

        UniversalImport imp = universalImportRepository.findByIdAndCompanyId(src, companyId).orElse(null);
        if (imp == null || out.meta() == null) return out;
        var meta = new java.util.LinkedHashMap<>(out.meta());
        meta.putIfAbsent("sourceFilename", imp.getFilename());
        meta.putIfAbsent("sourceImportedAt", imp.getCreatedAt() == null ? null : imp.getCreatedAt().toString());
        meta.putIfAbsent("sourceImportId", imp.getId());
        if (importId != null && view.getSourceUniversalImportId() != null && !Objects.equals(importId, view.getSourceUniversalImportId())) {
            meta.putIfAbsent("templateImportId", view.getSourceUniversalImportId());
        }
        return new UniversalChartDataDto(out.type(), out.labels(), out.series(), meta);
    }

    @PostMapping("/views/{viewId}/evidence")
    public UniversalEvidenceDto viewEvidence(@PathVariable Long companyId,
                                             @PathVariable Long viewId,
                                             @RequestParam(name = "importId", required = false) Long importId,
                                             @RequestParam(name = "focusLabel", required = false) String focusLabel,
                                             @RequestParam(defaultValue = "40") int limit) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        accessService.requirePlanAtLeast(companyId, Plan.GOLD);
        UniversalView view = universalViewRepository.findByIdAndCompanyId(viewId, companyId)
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "Dashboard no encontrado"));
        UniversalViewRequest req = universalViewService.decodeConfig(view.getConfigJson());
        if (req.getType() == null || req.getType().isBlank()) req.setType(view.getType());
        Long src = importId != null ? importId : view.getSourceUniversalImportId();
        return universalViewService.evidence(companyId, req, focusLabel, limit, src);
    }

    private UniversalViewDto toDto(UniversalView view) {
        UniversalImport imp = null;
        Long companyId = view.getCompany() == null ? null : view.getCompany().getId();
        if (companyId != null && view.getSourceUniversalImportId() != null) {
            imp = universalImportRepository.findByIdAndCompanyId(view.getSourceUniversalImportId(), companyId).orElse(null);
        }
        return new UniversalViewDto(
            view.getId(),
            companyId,
            view.getName(),
            view.getType(),
            view.getCreatedAt(),
            view.getSourceUniversalImportId(),
            imp == null ? null : imp.getFilename(),
            imp == null || imp.getCreatedAt() == null ? null : imp.getCreatedAt().toString()
        );
    }
}
