package dev.crdt.editor.document;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Who may delete a document or clear its history. There are no accounts.
 *
 * <ul>
 *   <li>The browser that creates a document gets a random 256-bit owner token in an HttpOnly cookie,
 *       so page scripts and the console can't read it. The database keeps only its SHA-256, which is
 *       enough for a random token of that size.
 *   <li>An admin can manage every document. The server knows only a salted PBKDF2 hash of the admin
 *       password ({@code ADMIN_KEY_HASH}, made by {@code deploy/admin-hash.py}). A signed-in admin
 *       holds an HttpOnly cookie with an HMAC keyed by that hash, never the password itself.
 * </ul>
 *
 * Cookies are SameSite=Strict, so other sites can't make the browser send them, and Secure everywhere
 * except on localhost.
 */
@Component
class DocumentAccess {

    static final String OWNER_COOKIE = "crdt_owner";
    static final String ADMIN_COOKIE = "crdt_admin";
    static final String HASH_PREFIX = "pbkdf2-sha256";
    static final int MIN_ITERATIONS = 100_000;
    private static final Duration OWNER_TTL = Duration.ofDays(365);
    private static final Duration ADMIN_TTL = Duration.ofDays(30);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "0:0:0:0:0:0:0:1");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(DocumentAccess.class);

    private record AdminHash(int iterations, byte[] salt, byte[] hash) {}

    private final DocumentRepository repo;
    private final AdminHash admin;

    DocumentAccess(DocumentRepository repo, @Value("${crdt.admin-key-hash:}") String adminKeyHash) {
        this.repo = repo;
        this.admin = parse(adminKeyHash.strip());
        log.info("Admin sign-in is {}", admin == null ? "disabled (ADMIN_KEY_HASH not set)" : "enabled");
    }

    static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The caller's owner token, or a fresh one if the browser has none yet. */
    String ownerToken(HttpServletRequest request) {
        String existing = cookie(request, OWNER_COOKIE);
        if (existing != null && existing.matches("[A-Za-z0-9_-]{43}")) {
            return existing;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    ResponseCookie ownerCookie(String token, HttpServletRequest request) {
        return cookie(OWNER_COOKIE, token, OWNER_TTL, request);
    }

    boolean adminEnabled() {
        return admin != null;
    }

    /** Checks a typed admin password against the configured hash, in constant time. */
    boolean isAdminPassword(String password) {
        if (admin == null || password == null || password.isEmpty() || password.length() > 200) {
            return false;
        }
        return MessageDigest.isEqual(admin.hash(), pbkdf2(password, admin.salt(), admin.iterations()));
    }

    ResponseCookie adminCookie(HttpServletRequest request) {
        return cookie(ADMIN_COOKIE, adminSessionValue(), ADMIN_TTL, request);
    }

    ResponseCookie clearAdminCookie(HttpServletRequest request) {
        return cookie(ADMIN_COOKIE, "", Duration.ZERO, request);
    }

    boolean isAdmin(HttpServletRequest request) {
        String value = cookie(request, ADMIN_COOKIE);
        return admin != null && value != null && MessageDigest.isEqual(
                adminSessionValue().getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    /** Throws {@link java.util.NoSuchElementException} if the document doesn't exist. */
    boolean canManage(UUID docId, HttpServletRequest request) {
        String ownerHash = repo.findOwnerKeyHash(docId);
        if (isAdmin(request)) {
            return true;
        }
        String token = cookie(request, OWNER_COOKIE);
        return ownerHash != null && token != null && MessageDigest.isEqual(
                ownerHash.getBytes(StandardCharsets.UTF_8), sha256Hex(token).getBytes(StandardCharsets.UTF_8));
    }

    /** Changing ADMIN_KEY_HASH changes this value, which signs every admin out. */
    private String adminSessionValue() {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(admin.hash(), "HmacSHA256"));
            byte[] tag = mac.doFinal("crdt-admin-session-v1".getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code pbkdf2-sha256:iterations:salt:hash}, salt and hash in URL-safe base64. Anything else disables admin. */
    private static AdminHash parse(String value) {
        if (value.isEmpty()) {
            return null;
        }
        String[] parts = value.split(":");
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] hash = Base64.getUrlDecoder().decode(parts[3]);
            if (parts.length == 4 && parts[0].equals(HASH_PREFIX) && iterations >= MIN_ITERATIONS
                    && salt.length >= 16 && hash.length == 32) {
                return new AdminHash(iterations, salt, hash);
            }
        } catch (RuntimeException ignored) {
            // falls through to the warning below
        }
        log.warn("ADMIN_KEY_HASH is not a valid {} hash; admin sign-in stays disabled. Make one with deploy/admin-hash.py",
                HASH_PREFIX);
        return null;
    }

    static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            spec.clearPassword();
        }
    }

    private static ResponseCookie cookie(String name, String value, Duration maxAge, HttpServletRequest request) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(!LOCAL_HOSTS.contains(request.getServerName()))
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies).filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst().orElse(null);
    }
}
