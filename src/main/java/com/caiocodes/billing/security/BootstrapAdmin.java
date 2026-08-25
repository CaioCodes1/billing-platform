package com.caiocodes.billing.security;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.caiocodes.billing.config.BillingProperties;
import com.caiocodes.billing.security.entity.Role;
import com.caiocodes.billing.security.entity.User;
import com.caiocodes.billing.security.repository.RoleRepository;
import com.caiocodes.billing.security.repository.UserRepository;

/**
 * Cria o primeiro ADMIN no boot, e só se não existir nenhum usuário.
 *
 * <p>Por que não uma migration: credencial em migration é credencial versionada
 * no git e idêntica em todos os ambientes. Aqui a senha vem de variável de
 * ambiente e some do repositório.
 *
 * <p>Se a variável não estiver definida, o sistema sobe sem admin e diz isso no
 * log — em vez de inventar uma senha padrão, que é como nascem os "admin/admin"
 * que sobrevivem até produção.
 */
@Component
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final BillingProperties properties;

    public BootstrapAdmin(UserRepository userRepository, RoleRepository roleRepository,
                          PasswordEncoder passwordEncoder, BillingProperties properties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }

        var bootstrap = properties.bootstrap();
        if (bootstrap == null
                || bootstrap.adminPassword() == null
                || bootstrap.adminPassword().isBlank()) {
            log.warn("Nenhum usuário cadastrado e BOOTSTRAP_ADMIN_PASSWORD não definida. "
                    + "A API está sem administrador — defina a variável e reinicie.");
            return;
        }

        Role admin = roleRepository.findByName("ADMIN").orElseThrow(
                () -> new IllegalStateException("Papel ADMIN ausente: migration V4 não aplicada?"));

        String email = bootstrap.adminEmail() == null || bootstrap.adminEmail().isBlank()
                ? "admin@billing.local"
                : bootstrap.adminEmail();

        userRepository.save(new User(
                "Administrador",
                email,
                passwordEncoder.encode(bootstrap.adminPassword()),
                Set.of(admin)));

        log.info("Administrador inicial criado: {}", email);
    }
}
