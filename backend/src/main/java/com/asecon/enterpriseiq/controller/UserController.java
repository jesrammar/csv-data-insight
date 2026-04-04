package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.CreateUserRequest;
import com.asecon.enterpriseiq.dto.UserActionLinkDto;
import com.asecon.enterpriseiq.dto.UpdateUserRequest;
import com.asecon.enterpriseiq.dto.UserDto;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.service.AccessService;
import com.asecon.enterpriseiq.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {
    private final UserService userService;
    private final AccessService accessService;

    public UserController(UserService userService, AccessService accessService) {
        this.userService = userService;
        this.accessService = accessService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public List<UserDto> list() {
        var actor = accessService.currentUser();
        return userService.listForActor(actor).stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        var actor = accessService.currentUser();
        return toDto(userService.createForActor(actor, request));
    }

    @PutMapping("/{id}/companies")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UserDto updateCompanies(@PathVariable Long id, @RequestBody Set<Long> companyIds) {
        var actor = accessService.currentUser();
        return toDto(userService.updateCompaniesForActor(actor, id, companyIds));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UserDto update(@PathVariable Long id, @RequestBody UpdateUserRequest request) {
        var actor = accessService.currentUser();
        return toDto(userService.updateUserForActor(actor, id, request));
    }

    @PostMapping("/{id}/invite-link")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UserActionLinkDto inviteLink(@PathVariable Long id) {
        var actor = accessService.currentUser();
        var issued = userService.issueInviteLinkForActor(actor, id);
        return new UserActionLinkDto("/?action=invite&token=" + issued.token(), issued.token(), issued.expiresAt());
    }

    @PostMapping("/{id}/password-reset-link")
    @PreAuthorize("hasAnyRole('ADMIN','CONSULTOR')")
    public UserActionLinkDto passwordResetLink(@PathVariable Long id) {
        var actor = accessService.currentUser();
        var issued = userService.issuePasswordResetLinkForActor(actor, id);
        return new UserActionLinkDto("/?action=reset&token=" + issued.token(), issued.token(), issued.expiresAt());
    }

    private UserDto toDto(User user) {
        Set<Long> companyIds = user.getCompanies().stream().map(c -> c.getId()).collect(Collectors.toSet());
        return new UserDto(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), companyIds);
    }
}
