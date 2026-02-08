package com.asecon.enterpriseiq.controller;

import com.asecon.enterpriseiq.dto.CreateUserRequest;
import com.asecon.enterpriseiq.dto.UserDto;
import com.asecon.enterpriseiq.model.User;
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

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserDto> list() {
        return userService.findAll().stream().map(this::toDto).collect(Collectors.toList());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto create(@Valid @RequestBody CreateUserRequest request) {
        return toDto(userService.create(request));
    }

    @PutMapping("/{id}/companies")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDto updateCompanies(@PathVariable Long id, @RequestBody Set<Long> companyIds) {
        return toDto(userService.updateCompanies(id, companyIds));
    }

    private UserDto toDto(User user) {
        Set<Long> companyIds = user.getCompanies().stream().map(c -> c.getId()).collect(Collectors.toSet());
        return new UserDto(user.getId(), user.getEmail(), user.getRole(), user.isEnabled(), companyIds);
    }
}
