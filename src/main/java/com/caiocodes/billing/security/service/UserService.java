package com.caiocodes.billing.security.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.security.dto.AuthDtos.CreateUserRequest;
import com.caiocodes.billing.security.dto.AuthDtos.UserResponse;
import com.caiocodes.billing.security.entity.Role;
import com.caiocodes.billing.security.entity.User;
import com.caiocodes.billing.security.repository.RoleRepository;
import com.caiocodes.billing.security.repository.UserRepository;
import com.caiocodes.billing.shared.exception.BusinessRuleException;
import com.caiocodes.billing.shared.exception.ConflictException;
import com.caiocodes.billing.shared.exception.ResourceNotFoundException;

/**
 * Gestão de usuários internos — exclusiva de ADMIN.
 *
 * <p>As anotações {@code @PreAuthorize} ficam <strong>no service</strong>, não
 * no controller. Assim a regra continua valendo se a rota mudar, se outro
 * controller chamar o mesmo método, ou se um job passar por aqui.
 */
@Service
@PreAuthorize("hasRole('ADMIN')")
public class UserService {

    private final UserRepository repository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public UserService(UserRepository repository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder, AuthService authService) {
        this.repository = repository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (repository.existsByEmailIgnoreCase(request.email())) {
            throw new ConflictException("EMAIL_JA_CADASTRADO",
                    "Já existe um usuário com o e-mail %s.".formatted(request.email()));
        }

        Set<Role> papeis = roleRepository.findByNameIn(request.roles());
        if (papeis.size() != request.roles().size()) {
            throw new BusinessRuleException("PAPEL_INVALIDO",
                    "Papéis válidos: ADMIN, FINANCIAL, SUPPORT.");
        }

        User usuario = new User(
                request.name(),
                request.email(),
                passwordEncoder.encode(request.password()),
                papeis);

        return toResponse(repository.saveAndFlush(usuario));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public void disable(UUID id) {
        User usuario = buscar(id);
        usuario.disable();
        // Desativar sem derrubar as sessões deixaria o usuário trabalhando por
        // até 15 minutos com um access token já emitido.
        authService.logoutTudo(id);
    }

    @Transactional
    public UserResponse enable(UUID id) {
        User usuario = buscar(id);
        usuario.enable();
        return toResponse(usuario);
    }

    private User buscar(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuário", id));
    }

    private UserResponse toResponse(User usuario) {
        return new UserResponse(
                usuario.getId(),
                usuario.getName(),
                usuario.getEmail(),
                usuario.isEnabled(),
                usuario.getRoles().stream().map(Role::getName).sorted().toList(),
                usuario.getCreatedAt());
    }
}
