package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.PipelineFileDto;
import com.asecon.enterpriseiq.dto.PipelineKindUpdateDto;
import com.asecon.enterpriseiq.dto.PipelinePeriodUpdateDto;
import com.asecon.enterpriseiq.dto.PipelineScanResultDto;
import com.asecon.enterpriseiq.dto.PipelineSummaryDto;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.PipelineInboxService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/pipeline")
public class PipelineController {
    private final AccessService accessService;
    private final PipelineInboxService pipelineInboxService;

    public PipelineController(AccessService accessService, PipelineInboxService pipelineInboxService) {
        this.accessService = accessService;
        this.pipelineInboxService = pipelineInboxService;
    }

    @GetMapping("/files")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public List<PipelineFileDto> files(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.listFiles(companyId);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PipelineSummaryDto summary(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.summary(companyId);
    }

    @PostMapping("/scan")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PipelineScanResultDto scan(@PathVariable Long companyId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.scanCompanyInbox(companyId);
    }

    @PostMapping("/files/{fileId}/retry")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PipelineFileDto retry(@PathVariable Long companyId, @PathVariable Long fileId) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.retryFile(companyId, fileId);
    }

    @PostMapping("/files/{fileId}/period")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PipelineFileDto updatePeriod(@PathVariable Long companyId,
                                        @PathVariable Long fileId,
                                        @RequestBody PipelinePeriodUpdateDto body) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.updatePeriod(companyId, fileId, body == null ? null : body.getPeriod());
    }

    @PostMapping("/files/{fileId}/kind")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public PipelineFileDto updateKind(@PathVariable Long companyId,
                                      @PathVariable Long fileId,
                                      @RequestBody PipelineKindUpdateDto body) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        return pipelineInboxService.updateKind(companyId, fileId, body == null ? null : body.getKind());
    }
}
