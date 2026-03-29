package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.MacroContextDto;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.MacroDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies/{companyId}/macro")
public class MacroController {
    private final AccessService accessService;
    private final MacroDataService macroDataService;

    public MacroController(AccessService accessService, MacroDataService macroDataService) {
        this.accessService = accessService;
        this.macroDataService = macroDataService;
    }

    @GetMapping("/context")
    public MacroContextDto context(@PathVariable Long companyId, @RequestParam(required = false) String period) {
        var user = accessService.currentUser();
        accessService.requireCompanyAccess(user, companyId);
        macroDataService.refreshIfStale();
        return macroDataService.context(period);
    }
}

