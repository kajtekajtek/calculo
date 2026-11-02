package dev.calculo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    public record LoginRequest(String password) {}

    private final PasswordChecker passwords;
    private final LoginRateLimiter limiter;
    private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

    public AuthController(PasswordChecker passwords, LoginRateLimiter limiter) {
        this.passwords = passwords;
        this.limiter = limiter;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        if (limiter.isBlocked()) {
            return message(HttpStatus.TOO_MANY_REQUESTS, "Zbyt wiele nieudanych prób logowania. Spróbuj za minutę.");
        }
        if (!passwords.matches(body.password())) {
            limiter.recordFailure();
            return message(HttpStatus.UNAUTHORIZED, "Nieprawidłowe hasło.");
        }
        limiter.reset();
        request.getSession(true);
        request.changeSessionId();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "owner", null, List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Boolean> me() {
        return Map.of("authenticated", true);
    }

    private static ResponseEntity<Map<String, String>> message(HttpStatus status, String text) {
        return ResponseEntity.status(status).body(Map.of("message", text));
    }
}
