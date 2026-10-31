package dev.crdt.editor.document;

import static org.assertj.core.api.Assertions.assertThat;

import dev.crdt.editor.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/** Who may delete and clear history, exercised over real HTTP with cookies handled by hand. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "crdt.admin-key-hash=pbkdf2-sha256:100000:MDEyMzQ1Njc4OWFiY2RlZg:CQEF03iMraucElCfobodRqkaFY16F3mxFDIvP9WoJcs")
@Import(TestcontainersConfiguration.class)
class DocumentAccessIntegrationTest {

    private static final String ADMIN_PASSWORD = "correct horse battery staple";
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @LocalServerPort
    int port;

    @Autowired
    DocumentService service;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void creatorGetsAnHttpOnlyCookieAndTheBodyCarriesNoSecret() throws Exception {
        HttpResponse<String> created = send("POST", "/api/docs", "{\"title\":\"mine\"}", null);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).matches("\\{\"id\":\"[0-9a-f-]{36}\"}");
        String setCookie = created.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).startsWith(DocumentAccess.OWNER_COOKIE + "=").contains("HttpOnly", "SameSite=Strict");
    }

    @Test
    void onlyTheCreatorCanClearHistoryAndDelete() throws Exception {
        HttpResponse<String> created = send("POST", "/api/docs", "{\"title\":\"mine\"}", null);
        String id = id(created);
        String owner = cookie(created);
        String stranger = cookie(send("POST", "/api/docs", "{\"title\":\"theirs\"}", null));

        assertThat(send("GET", "/api/docs/" + id + "/access", null, null).body()).contains("false");
        assertThat(send("DELETE", "/api/docs/" + id, null, null).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/docs/" + id, null, stranger).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/docs/" + id + "/versions", null, stranger).statusCode()).isEqualTo(403);

        assertThat(send("GET", "/api/docs/" + id + "/access", null, owner).body()).contains("true");
        assertThat(send("DELETE", "/api/docs/" + id + "/versions", null, owner).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/docs/" + id, null, owner).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/docs/" + id, null, owner).statusCode()).isEqualTo(404);
    }

    @Test
    void oneBrowserCookieOwnsEveryDocumentItCreates() throws Exception {
        HttpResponse<String> first = send("POST", "/api/docs", "{\"title\":\"one\"}", null);
        String owner = cookie(first);
        String second = id(send("POST", "/api/docs", "{\"title\":\"two\"}", owner));

        assertThat(send("DELETE", "/api/docs/" + id(first), null, owner).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/docs/" + second, null, owner).statusCode()).isEqualTo(204);
    }

    @Test
    void adminSignsInWithThePasswordAndCanManageDocumentsWithoutAnOwner() throws Exception {
        UUID legacy = service.create("made before owner keys");

        assertThat(send("POST", "/api/admin/session", "{\"password\":\"wrong\"}", null).statusCode()).isEqualTo(401);
        HttpResponse<String> signedIn = send("POST", "/api/admin/session",
                "{\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
        assertThat(signedIn.statusCode()).isEqualTo(204);
        String setCookie = signedIn.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(setCookie).contains("HttpOnly", "SameSite=Strict").doesNotContain("correct", "horse");
        String admin = cookie(signedIn);

        assertThat(send("GET", "/api/admin/session", null, admin).body()).contains("\"signedIn\":true");
        assertThat(send("DELETE", "/api/docs/" + legacy, null, null).statusCode()).isEqualTo(403);
        assertThat(send("DELETE", "/api/docs/" + legacy, null, admin).statusCode()).isEqualTo(204);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void guessingTheAdminPasswordIsRateLimitedPerIp() throws Exception {
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i <= AdminController.MAX_ATTEMPTS; i++) {
            codes.add(send("POST", "/api/admin/session", "{\"password\":\"guess" + i + "\"}", null,
                    "203.0.113.7").statusCode());
        }
        assertThat(codes.getLast()).isEqualTo(429);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void guessingFromManyIpsHitsTheSiteWideCap() throws Exception {
        List<Integer> codes = new ArrayList<>();
        for (int i = 0; i <= AdminController.MAX_ATTEMPTS_SITE_WIDE; i++) {
            codes.add(send("POST", "/api/admin/session", "{\"password\":\"guess\"}", null,
                    "198.51.100." + (i + 1)).statusCode());
        }
        assertThat(codes).contains(429);
    }

    @Test
    void writesFromAnotherSiteAreRejectedEvenWithTheOwnerCookie() throws Exception {
        HttpResponse<String> created = send("POST", "/api/docs", "{\"title\":\"mine\"}", null);
        String id = id(created);
        String owner = cookie(created);

        assertThat(send("DELETE", "/api/docs/" + id, null, owner, null, "https://evil.example").statusCode())
                .isEqualTo(403);
        assertThat(send("POST", "/api/docs", "{\"title\":\"x\"}", null, null, "https://evil.example").statusCode())
                .isEqualTo(403);
        assertThat(send("DELETE", "/api/docs/" + id, null, owner, null, "http://localhost:" + port).statusCode())
                .isEqualTo(204);
    }

    @Test
    void theAdminCookieIsUselessWithoutTheRightSignature() throws Exception {
        UUID legacy = service.create("legacy");
        String forged = DocumentAccess.ADMIN_COOKIE + "=" + "A".repeat(43);

        assertThat(send("DELETE", "/api/docs/" + legacy, null, forged).statusCode()).isEqualTo(403);
    }

    @Test
    void aPlainShaHashDoesNotEnableAdmin() {
        assertThat(new DocumentAccess(null, DocumentAccess.sha256Hex("password")).adminEnabled()).isFalse();
        assertThat(new DocumentAccess(null, "pbkdf2-sha256:1000:MDEyMzQ1Njc4OWFiY2RlZg:CQEF03iMraucElCfobodRqkaFY16F3mxFDIvP9WoJcs")
                .adminEnabled()).as("too few iterations").isFalse();
    }

    private HttpResponse<String> send(String method, String path, String json, String cookie) throws Exception {
        return send(method, path, json, cookie, null);
    }

    private HttpResponse<String> send(String method, String path, String json, String cookie, String forwardedFor)
            throws Exception {
        return send(method, path, json, cookie, forwardedFor, null);
    }

    private HttpResponse<String> send(String method, String path, String json, String cookie, String forwardedFor,
                                      String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String id(HttpResponse<String> created) {
        Matcher m = ID.matcher(created.body());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    /** The name=value part of the response's Set-Cookie, ready to send back. */
    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
