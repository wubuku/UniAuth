package org.dddml.uniauth.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveOutputPolicyTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath();

    @Test
    void applicationCodeDoesNotUseDirectConsoleOrStackTraceOutput() throws IOException {
        try (Stream<Path> files = Files.walk(PROJECT_ROOT.resolve("src/main/java"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> assertThat(read(path))
                            .as("direct output in %s", PROJECT_ROOT.relativize(path))
                            .doesNotContain("System.out.")
                            .doesNotContain("System.err.")
                            .doesNotContain(".printStackTrace("));
        }
    }

    @Test
    void knownSensitiveLogTemplatesAreAbsent() {
        assertSourceDoesNotContain(
                "src/main/java/org/dddml/uniauth/service/Web3NonceService.java",
                "nonce: {}", "wallet: {}"
        );
        assertSourceDoesNotContain(
                "src/main/java/org/dddml/uniauth/util/Web3SignatureUtils.java",
                "Signature components", "Message hash", "Recovered address",
                "expected: {}, recovered: {}"
        );
        assertSourceDoesNotContain(
                "src/main/java/org/dddml/uniauth/config/SecurityConfig.java",
                "Query String", "Email: ", "Saving frontend URL from referer",
                "Saved frontend URL to session"
        );
        assertThat(read(PROJECT_ROOT.resolve(
                "src/main/java/org/dddml/uniauth/config/GlobalExceptionHandler.java"
        ))).containsOnlyOnce(".detail(ex.getMessage())");
    }

    @Test
    void scriptsDoNotPrintSecretOrTokenPrefixes() {
        List<String> forbidden = List.of(
                "${CLIENT_SECRET:0",
                "${ACCESS_TOKEN:0",
                "${REFRESH_TOKEN:0",
                "${VERIFICATION_CODE}",
                "${RESET_CODE}",
                "response.text",
                "session.delete("
        );

        try (Stream<Path> files = Files.walk(PROJECT_ROOT)) {
            files.filter(path -> path.toString().endsWith(".sh")
                            || path.toString().endsWith(".py"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !path.toString().contains("/node_modules/"))
                    .forEach(path -> {
                        String content = read(path);
                        forbidden.forEach(value -> assertThat(content)
                                .as("sensitive output in %s", PROJECT_ROOT.relativize(path))
                                .doesNotContain(value));
                    });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan shell scripts", e);
        }
    }

    @Test
    void pythonResourceServerUsesSafeTokenDiagnostics() {
        assertSourceDoesNotContain(
                "python-resource-server/app.py",
                "verify=False",
                "debug=True",
                "traceback",
                "JWK:",
                "Token header:",
                "result.get('sub')",
                "result.get(\"sub\")}"
        );
        assertSourceDoesNotContain(
                "python-resource-server/test_token.py",
                "result.get(",
                "print(token"
        );
        assertThat(PROJECT_ROOT.resolve("python-resource-server/debug_token.py"))
                .doesNotExist();
    }

    @Test
    void liveCallersDoNotReferenceRemovedDangerousRoutes() throws IOException {
        List<Path> roots = List.of(
                PROJECT_ROOT.resolve("frontend/src"),
                PROJECT_ROOT.resolve("src/main/resources/templates"),
                PROJECT_ROOT.resolve("scripts"),
                PROJECT_ROOT.resolve("python-resource-server")
        );
        List<String> forbiddenRoutes = List.of(
                "/api/auth/check-user",
                "/api/auth/generate-hash",
                "/api/auth/create-test-user",
                "/api/auth/reset-password",
                "/api/validate-google-token",
                "/api/validate-github-token",
                "/api/validate-x-token",
                "/oauth2/introspect-test",
                "/oauth2/validate"
        );

        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> !path.toString().contains("/__pycache__/"))
                        .filter(path -> !isAutomatedTestHarness(path))
                        .filter(this::isLiveSourceFile)
                        .forEach(path -> {
                            String content = read(path);
                            forbiddenRoutes.forEach(route -> assertThat(content)
                                    .as("removed route in %s", PROJECT_ROOT.relativize(path))
                                    .doesNotContain(route));
                        });
            }
        }
    }

    @Test
    void trackedPythonSourcesDoNotContainEmbeddedJwtSamples() throws IOException {
        try (Stream<Path> files = Files.walk(PROJECT_ROOT.resolve("python-resource-server"))) {
            files.filter(path -> path.toString().endsWith(".py"))
                    .forEach(path -> assertThat(read(path))
                            .as("embedded JWT in %s", PROJECT_ROOT.relativize(path))
                            .doesNotMatch("(?s).*eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\..*"));
        }
    }

    private boolean isLiveSourceFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".ts")
                || name.endsWith(".tsx")
                || name.endsWith(".html")
                || name.endsWith(".sh")
                || name.endsWith(".py");
    }

    private boolean isAutomatedTestHarness(Path path) {
        Path relativePath = PROJECT_ROOT.relativize(path);
        String name = path.getFileName().toString();
        return relativePath.startsWith("scripts") && name.startsWith("test-")
                || relativePath.startsWith("python-resource-server") && name.startsWith("test_");
    }

    private void assertSourceDoesNotContain(String relativePath, String... forbiddenValues) {
        String content = read(PROJECT_ROOT.resolve(relativePath));
        assertThat(content).doesNotContain(forbiddenValues);
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + path, e);
        }
    }
}
