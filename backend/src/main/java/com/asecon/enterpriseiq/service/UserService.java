package com.asecon.enterpriseiq.service;

import com.asecon.enterpriseiq.dto.CreateUserRequest;
import com.asecon.enterpriseiq.dto.UpdateUserRequest;
import com.asecon.enterpriseiq.model.Company;
import com.asecon.enterpriseiq.model.AuditEvent;
import com.asecon.enterpriseiq.model.Role;
import com.asecon.enterpriseiq.model.User;
import com.asecon.enterpriseiq.repo.AuditEventRepository;
import com.asecon.enterpriseiq.repo.CompanyRepository;
import com.asecon.enterpriseiq.repo.UserRepository;
import com.asecon.enterpriseiq.security.TokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;
    private final PasswordPolicyService passwordPolicyService;
    private final UserTokenService userTokenService;
    private final TokenService tokenService;

    public UserService(UserRepository userRepository,
                       CompanyRepository companyRepository,
                       PasswordEncoder passwordEncoder,
                       AuditEventRepository auditEventRepository,
                       ObjectMapper objectMapper,
                       PasswordPolicyService passwordPolicyService,
                       UserTokenService userTokenService,
                       TokenService tokenService) {
        this.userRepository = userRepository;
        this.companyRepository = companyRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
        this.passwordPolicyService = passwordPolicyService;
        this.userTokenService = userTokenService;
        this.tokenService = tokenService;
    }

    public List<User> findAll() { return userRepository.findAllWithCompanies(); }

    public List<User> listForActor(User actor) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (actor.getRole() == Role.ADMIN) {
            return userRepository.findAllWithCompanies();
        }
        if (actor.getRole() != Role.CONSULTOR) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado");
        }

        Set<Long> myCompanyIds = myCompanyIds(actor);
        if (myCompanyIds.isEmpty()) {
            // still return self, so UI can show who you are
            User me = userRepository.findByIdWithCompanies(actor.getId()).orElseThrow();
            return List.of(me);
        }

        List<User> scoped = userRepository.findDistinctByCompanyIdsWithCompanies(myCompanyIds);
        return scoped.stream()
            .filter(u -> u != null && (u.getRole() == Role.CLIENTE || (actor.getId() != null && actor.getId().equals(u.getId()))))
            .collect(Collectors.toList());
    }

    public User createForActor(User actor, CreateUserRequest request) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta payload");

        boolean isAdmin = actor.getRole() == Role.ADMIN;
        boolean isConsultor = actor.getRole() == Role.CONSULTOR;
        if (!isAdmin && !isConsultor) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado");
        }
        if (isConsultor && request.getRole() != Role.CLIENTE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un consultor solo puede crear usuarios CLIENTE");
        }

        Set<Long> requestedCompanyIds = request.getCompanyIds() == null ? Set.of() : request.getCompanyIds();
        if (isConsultor) {
            if (requestedCompanyIds.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un cliente debe tener al menos una empresa asignada");
            }
            Set<Long> myCompanyIds = myCompanyIds(actor);
            if (!myCompanyIds.containsAll(requestedCompanyIds)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes asignar empresas fuera de tu cartera");
            }
        }

        User user = new User();
        String email = request.getEmail() == null ? null : request.getEmail().trim().toLowerCase();
        user.setEmail(email);
        passwordPolicyService.requireValid(request.getPassword());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setEnabled(true);

        if (!requestedCompanyIds.isEmpty()) {
            Set<Company> companies = new HashSet<>(companyRepository.findAllById(requestedCompanyIds));
            user.setCompanies(companies);
        }

        User created = userRepository.save(user);
        auditUserCreate(actor, created, requestedCompanyIds);
        return created;
    }

    public UserTokenService.IssuedToken issueInviteLinkForActor(User actor, Long userId) {
        User target = requireManageTarget(actor, userId);
        var issued = userTokenService.issue(actor, target, com.asecon.enterpriseiq.model.UserTokenPurpose.INVITE, java.time.Duration.ofDays(7));
        auditUserTokenIssued(actor, target, "USER_INVITE_LINK_ISSUED", issued.expiresAt());
        return issued;
    }

    public UserTokenService.IssuedToken issuePasswordResetLinkForActor(User actor, Long userId) {
        User target = requireManageTarget(actor, userId);
        var issued = userTokenService.issue(actor, target, com.asecon.enterpriseiq.model.UserTokenPurpose.PASSWORD_RESET, java.time.Duration.ofMinutes(30));
        auditUserTokenIssued(actor, target, "USER_PASSWORD_RESET_LINK_ISSUED", issued.expiresAt());
        return issued;
    }

    public void setPasswordFromToken(String rawToken, String newPassword, com.asecon.enterpriseiq.model.UserTokenPurpose purpose) {
        passwordPolicyService.requireValid(newPassword);
        var token = userTokenService.consumeValid(rawToken, purpose);
        var user = token.getUser();
        if (user == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token inválido");

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setEnabled(true);
        userRepository.save(user);
        tokenService.revokeAllRefreshTokensForUser(user.getId());

        auditUserTokenConsumed(user, purpose == com.asecon.enterpriseiq.model.UserTokenPurpose.INVITE ? "USER_INVITE_USED" : "USER_PASSWORD_RESET_USED");
    }

    public void changeOwnPassword(User actor, String currentPassword, String newPassword) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta contraseña actual");
        }
        passwordPolicyService.requireValid(newPassword);
        User me = userRepository.findById(actor.getId()).orElseThrow();
        if (!passwordEncoder.matches(currentPassword, me.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La contraseña actual no es correcta");
        }
        me.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(me);
        tokenService.revokeAllRefreshTokensForUser(me.getId());
    }

    private User requireManageTarget(User actor, Long userId) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (userId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta userId");

        boolean isAdmin = actor.getRole() == Role.ADMIN;
        boolean isConsultor = actor.getRole() == Role.CONSULTOR;
        if (!isAdmin && !isConsultor) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado");

        User target = userRepository.findByIdWithCompanies(userId).orElseThrow();
        if (isConsultor) {
            if (target.getRole() != Role.CLIENTE) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo puedes gestionar CLIENTES");
            Set<Long> myCompanyIds = myCompanyIds(actor);
            Set<Long> targetCompanies = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
            boolean isInMyPortfolio = targetCompanies.stream().anyMatch(myCompanyIds::contains);
            if (!isInMyPortfolio) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario fuera de tu cartera");
        }
        return target;
    }

    public User updateCompaniesForActor(User actor, Long userId, Set<Long> companyIds) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (userId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta userId");

        boolean isAdmin = actor.getRole() == Role.ADMIN;
        boolean isConsultor = actor.getRole() == Role.CONSULTOR;
        if (!isAdmin && !isConsultor) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado");

        User target = userRepository.findByIdWithCompanies(userId).orElseThrow();
        if (isConsultor) {
            if (target.getRole() != Role.CLIENTE) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo puedes gestionar CLIENTES");
            Set<Long> myCompanyIds = myCompanyIds(actor);
            Set<Long> before = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
            boolean isInMyPortfolio = before.stream().anyMatch(myCompanyIds::contains);
            if (!isInMyPortfolio) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario fuera de tu cartera");
            Set<Long> requested = companyIds == null ? Set.of() : companyIds;
            if (requested.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un cliente debe tener al menos una empresa asignada");
            }
            if (!myCompanyIds.containsAll(requested)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No puedes asignar empresas fuera de tu cartera");
            }
        }

        Set<Long> beforeCompanyIds = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
        Set<Long> requested = companyIds == null ? Set.of() : companyIds;
        Set<Company> companies = new HashSet<>(companyRepository.findAllById(requested));
        target.setCompanies(companies);
        User saved = userRepository.save(target);
        Set<Long> afterCompanyIds = saved.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
        auditUserCompaniesUpdate(actor, saved, beforeCompanyIds, afterCompanyIds);
        return saved;
    }

    public User updateUserForActor(User actor, Long userId, UpdateUserRequest request) {
        if (actor == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No autenticado");
        if (userId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta userId");
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta payload");

        boolean isAdmin = actor.getRole() == Role.ADMIN;
        boolean isConsultor = actor.getRole() == Role.CONSULTOR;
        if (!isAdmin && !isConsultor) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No autorizado");

        User target = userRepository.findByIdWithCompanies(userId).orElseThrow();
        if (isConsultor) {
            if (target.getRole() != Role.CLIENTE) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo puedes gestionar CLIENTES");
            if (request.getRole() != null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Un consultor no puede cambiar roles");
            Set<Long> myCompanyIds = myCompanyIds(actor);
            Set<Long> beforeCompanies = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
            boolean isInMyPortfolio = beforeCompanies.stream().anyMatch(myCompanyIds::contains);
            if (!isInMyPortfolio) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario fuera de tu cartera");
        }

        Role beforeRole = target.getRole();
        boolean beforeEnabled = target.isEnabled();
        if (request.getRole() != null) target.setRole(request.getRole());
        if (request.getEnabled() != null) target.setEnabled(request.getEnabled());

        User saved = userRepository.save(target);
        auditUserUpdate(actor, saved, beforeRole, beforeEnabled);
        return saved;
    }

    private Set<Long> myCompanyIds(User actor) {
        if (actor == null || actor.getId() == null) return Set.of();
        return companyRepository.findByUsers_Id(actor.getId()).stream()
            .map(Company::getId)
            .collect(Collectors.toSet());
    }

    private void auditUserCreate(User actor, User created, Set<Long> companyIds) {
        if (actor == null || created == null) return;
        if (companyIds == null || companyIds.isEmpty()) return;
        for (Long companyId : companyIds) {
            writeAudit(actor, companyId, "USER_CREATE", created.getId(), meta(
                "targetEmail", created.getEmail(),
                "targetRole", String.valueOf(created.getRole()),
                "enabled", created.isEnabled()
            ));
        }
    }

    private void auditUserCompaniesUpdate(User actor, User target, Set<Long> before, Set<Long> after) {
        if (actor == null || target == null) return;
        Set<Long> b = before == null ? Set.of() : before;
        Set<Long> a = after == null ? Set.of() : after;
        Set<Long> added = a.stream().filter(id -> !b.contains(id)).collect(Collectors.toSet());
        Set<Long> removed = b.stream().filter(id -> !a.contains(id)).collect(Collectors.toSet());
        Set<Long> affected = new HashSet<>();
        affected.addAll(b);
        affected.addAll(a);
        if (affected.isEmpty()) return;

        for (Long companyId : affected) {
            writeAudit(actor, companyId, "USER_COMPANIES_UPDATE", target.getId(), meta(
                "targetEmail", target.getEmail(),
                "addedCompanyIds", added,
                "removedCompanyIds", removed
            ));
        }
    }

    private void auditUserUpdate(User actor, User target, Role beforeRole, boolean beforeEnabled) {
        if (actor == null || target == null) return;
        boolean roleChanged = beforeRole != target.getRole();
        boolean enabledChanged = beforeEnabled != target.isEnabled();
        if (!roleChanged && !enabledChanged) return;

        Set<Long> companyIds = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
        if (companyIds.isEmpty()) return;

        for (Long companyId : companyIds) {
            writeAudit(actor, companyId, "USER_UPDATE", target.getId(), meta(
                "targetEmail", target.getEmail(),
                "roleFrom", String.valueOf(beforeRole),
                "roleTo", String.valueOf(target.getRole()),
                "enabledFrom", beforeEnabled,
                "enabledTo", target.isEnabled()
            ));
        }
    }

    private void auditUserTokenIssued(User actor, User target, String action, java.time.Instant expiresAt) {
        if (actor == null || target == null || action == null) return;
        Set<Long> companyIds = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
        if (companyIds.isEmpty()) return;
        for (Long companyId : companyIds) {
            writeAudit(actor, companyId, action, target.getId(), meta(
                "targetEmail", target.getEmail(),
                "expiresAt", expiresAt == null ? null : expiresAt.toString()
            ));
        }
    }

    private void auditUserTokenConsumed(User target, String action) {
        if (target == null || action == null) return;
        Set<Long> companyIds = target.getCompanies().stream().map(Company::getId).collect(Collectors.toSet());
        if (companyIds.isEmpty()) return;
        for (Long companyId : companyIds) {
            try {
                AuditEvent e = new AuditEvent();
                e.setAt(Instant.now());
                e.setUserId(target.getId());
                e.setCompanyId(companyId);
                e.setAction(action);
                e.setResourceType("USER");
                e.setResourceId(String.valueOf(target.getId()));
                e.setMetaJson(meta("targetEmail", target.getEmail()));
                auditEventRepository.save(e);
            } catch (Exception ignored) {
            }
        }
    }

    private void writeAudit(User actor, Long companyId, String action, Long targetUserId, String metaJson) {
        try {
            AuditEvent e = new AuditEvent();
            e.setAt(Instant.now());
            e.setUserId(actor.getId());
            e.setCompanyId(companyId);
            e.setAction(action);
            e.setResourceType("USER");
            e.setResourceId(targetUserId == null ? null : String.valueOf(targetUserId));
            e.setMetaJson(metaJson);
            auditEventRepository.save(e);
        } catch (Exception ignored) {
        }
    }

    private String meta(Object... kv) {
        try {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String k = String.valueOf(kv[i]);
                Object v = kv[i + 1];
                map.put(k, v);
            }
            return objectMapper.writeValueAsString(map);
        } catch (Exception ignored) {
            return null;
        }
    }
}
