package dev.crdt.editor.document;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin sign-in. The password is checked against ADMIN_KEY_HASH and never stored or echoed. Each
 * check is deliberately slow (PBKDF2), so attempts are capped per IP and, against attackers rotating
 * IPs, for the whole site, which also bounds the CPU they can make the app spend.
 */
@RestController
@RequestMapping("/api/admin/session")
class AdminController {

    static final int MAX_ATTEMPTS = 5;
    static final int MAX_ATTEMPTS_SITE_WIDE = 30;
    static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);

    private final DocumentAccess access;
    private final CreationRateLimiter attempts = new CreationRateLimiter(MAX_ATTEMPTS, ATTEMPT_WINDOW, Clock.systemUTC());
    private final CreationRateLimiter siteWide =
            new CreationRateLimiter(MAX_ATTEMPTS_SITE_WIDE, ATTEMPT_WINDOW, Clock.systemUTC());

    AdminController(DocumentAccess access) {
        this.access = access;
    }

    @GetMapping
    Dtos.AdminStatus status(HttpServletRequest http) {
        return new Dtos.AdminStatus(access.adminEnabled(), access.isAdmin(http));
    }

    @PostMapping
    ResponseEntity<Void> signIn(@RequestBody Dtos.AdminSignIn request, HttpServletRequest http) {
        if (!access.adminEnabled()) {
            return ResponseEntity.notFound().build();
        }
        if (!attempts.tryAcquire(http.getRemoteAddr()) || !siteWide.tryAcquire("all")) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        if (!access.isAdminPassword(request.password())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, access.adminCookie(http).toString()).build();
    }

    @DeleteMapping
    ResponseEntity<Void> signOut(HttpServletRequest http) {
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, access.clearAdminCookie(http).toString()).build();
    }
}
