package pt.isep.psoft.aisafe.application;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import pt.isep.psoft.aisafe.domain.Role;
import pt.isep.psoft.aisafe.domain.User;
import pt.isep.psoft.aisafe.repositories.UserRepository;

import java.util.Set;

@Service
public class UserService {
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public void registerUser(String username, String rawPassword, Set<Role> roles) {
        audit("CREATE", username);
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists!");
        }


        String encodedPassword = passwordEncoder.encode(rawPassword);

        User newUser = new User(username, encodedPassword, roles);
        userRepository.save(newUser);
    }


    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    private void audit(String action, Object id) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        String username = principal instanceof org.springframework.security.core.userdetails.UserDetails
                ? ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername()
                : principal instanceof String ? (String) principal : null;
        if (username == null || username.isBlank()) username = "SYSTEM";
        logger.info("AUDIT - User: {} - Action: {} - Target ID: {}", username, action, id);
    }
}