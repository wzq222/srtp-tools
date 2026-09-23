package com.srtp.server.controller;

import com.srtp.server.model.User;
import com.srtp.server.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository userRepo;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserRepository userRepo, PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (username == null || username.trim().isEmpty()) {
            return Map.of("ok", false, "error", "用户名不能为空");
        }
        if (password == null || password.length() < 4) {
            return Map.of("ok", false, "error", "密码至少4位");
        }
        if (userRepo.findByUsername(username).isPresent()) {
            return Map.of("ok", false, "error", "用户名已存在");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        long id = userRepo.insert(user);
        user.setId(id);

        return Map.of("ok", true, "id", id, "username", username);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return Map.of("ok", false, "error", "用户名和密码不能为空");
        }

        Optional<User> opt = userRepo.findByUsername(username);
        if (opt.isEmpty()) {
            return Map.of("ok", false, "error", "用户名或密码错误");
        }

        User user = opt.get();
        String storedHash = user.getPasswordHash();

        // Three-way password check: BCrypt > SHA256 legacy > plain text
        boolean matched;
        if (storedHash != null && storedHash.startsWith("$2a$")) {
            // BCrypt (standard)
            matched = passwordEncoder.matches(password, storedHash);
        } else if (storedHash != null && storedHash.length() == 64 && storedHash.matches("[0-9a-fA-F]+")) {
            // SHA-256 legacy: compare hash of input
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                for (byte b : digest) sb.append(String.format("%02x", b));
                matched = sb.toString().equalsIgnoreCase(storedHash);
            } catch (Exception e) { matched = false; }
        } else {
            // Plain text legacy
            matched = password.equals(storedHash);
        }
        // Auto-migrate to BCrypt if matched via legacy method
        if (matched && (storedHash == null || !storedHash.startsWith("$2a$"))) {
            userRepo.updatePassword(user.getId(), passwordEncoder.encode(password));
        }

        if (!matched) {
            return Map.of("ok", false, "error", "用户名或密码错误");
        }

        // Create Spring Security authentication
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new UserPrincipal(user.getId(), user.getUsername()), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Persist to HTTP session
        HttpSession session = request.getSession(true);
        session.setAttribute("SPRING_SECURITY_CONTEXT", SecurityContextHolder.getContext());

        return Map.of("ok", true, "username", user.getUsername(), "userId", user.getId());
    }

    @GetMapping("/session")
    public Map<String, Object> checkSession(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Map.of("ok", false, "error", "未登录");
        }
        UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
        return Map.of("ok", true, "username", principal.username(), "userId", principal.userId());
    }

    /** Principal stored in SecurityContext */
    public record UserPrincipal(Long userId, String username) implements java.security.Principal, java.io.Serializable {
        @Override public String getName() { return username; }
    }
}
