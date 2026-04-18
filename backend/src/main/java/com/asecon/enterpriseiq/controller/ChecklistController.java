package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.ChecklistDto;
import com.asecon.enterpriseiq.model.AutomationJobStatus;
import com.asecon.enterpriseiq.model.AutomationJobType;
import com.asecon.enterpriseiq.repo.AutomationJobRepository;
import com.asecon.enterpriseiq.repo.ReportRepository;
import com.asecon.enterpriseiq.repo.TransactionRepository;
import com.asecon.enterpriseiq.repo.TribunalImportRepository;
import com.asecon.enterpriseiq.repo.UniversalImportRepository;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.CompanySettingsService;
import java.time.YearMonth;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies/{companyId}/checklist")
public class ChecklistController {
    private final AccessService accessService;
    private final CompanySettingsService settingsService;
    private final TransactionRepository transactionRepository;
    private final TribunalImportRepository tribunalImportRepository;
    private final UniversalImportRepository universalImportRepository;
    private final ReportRepository reportRepository;
    private final AutomationJobRepository automationJobRepository;

    public ChecklistController(AccessService accessService,
                               CompanySettingsService settingsService,
                               TransactionRepository transactionRepository,
                               TribunalImportRepository tribunalImportRepository,
                               UniversalImportRepository universalImportRepository,
                               ReportRepository reportRepository,
                               AutomationJobRepository automationJobRepository) {
        this.accessService = accessService;
        this.settingsService = settingsService;
        this.transactionRepository = transactionRepository;
        this.tribunalImportRepository = tribunalImportRepository;
        this.universalImportRepository = universalImportRepository;
        this.reportRepository = reportRepository;
        this.automationJobRepository = automationJobRepository;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR','CLIENTE')")
    public ChecklistDto get(@PathVariable Long companyId, @RequestParam(required = false) String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);

        String fallback = YearMonth.now().toString();
        String resolved = (period == null || period.isBlank())
            ? settingsService.resolveWorkingPeriod(companyId, fallback)
            : period.trim();

        boolean cash = transactionRepository.existsByCompanyIdAndPeriod(companyId, resolved);
        boolean tribunal = tribunalImportRepository.existsByCompanyId(companyId);
        boolean universal = universalImportRepository.existsByCompanyId(companyId);
        boolean report = reportRepository.findByCompanyIdAndPeriod(companyId, resolved).isPresent();
        boolean reportJobActive = automationJobRepository.existsByCompany_IdAndTypeAndStatusInAndPayloadJsonContaining(
            companyId,
            AutomationJobType.GENERATE_MONTHLY_REPORT,
            List.of(AutomationJobStatus.PENDING, AutomationJobStatus.RETRY, AutomationJobStatus.RUNNING),
            "\"period\":\"" + resolved + "\""
        );

        List<ChecklistDto.ChecklistItemDto> items = List.of(
            new ChecklistDto.ChecklistItemDto("cash", "Subir transacciones (Caja) para " + resolved, cash,
                cash ? "OK" : "Carga el CSV/XLSX del mes de trabajo.", "/imports?mode=transactions"),
            new ChecklistDto.ChecklistItemDto("tribunal", "Subir cartera (Tribunal)", tribunal,
                tribunal ? "OK" : "Opcional si trabajas cumplimiento/cartera.", "/imports?mode=auto"),
            new ChecklistDto.ChecklistItemDto("universal", "Subir tabla extra (Universal)", universal,
                universal ? "OK" : "Opcional: análisis exploratorio/asesoramiento.", "/imports?mode=universal"),
            new ChecklistDto.ChecklistItemDto("deliverable", "Entregable mensual (reporte)", report || reportJobActive,
                report ? "Reporte listo." : (reportJobActive ? "Generándose…" : "Genera el reporte del mes."), "/reports")
        );

        return new ChecklistDto(companyId, resolved, items);
    }
}

