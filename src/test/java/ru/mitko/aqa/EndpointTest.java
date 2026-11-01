package ru.mitko.aqa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.qameta.allure.Allure;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Проверка сессий и доступа к сервису")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointTest {
    private final WireMockServer mock = new WireMockServer(wireMockConfig().dynamicPort().bindAddress("127.0.0.1"));
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private static final String KEY = "qazWSXedc";
    private Process app;
    private String endpoint;

    @BeforeAll
    void start() throws Exception {
        Path jar = Path.of("internal-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar), "Положите JAR рядом с pom.xml, см. README.md");
        Files.createDirectories(Path.of("target"));
        try {
            mock.start();
            int port;
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                port = socket.getLocalPort();
            }
            endpoint = "http://127.0.0.1:" + port + "/endpoint";
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            app = new ProcessBuilder(java, "-Dsecret=" + KEY, "-Dmock=" + mock.baseUrl() + "/",
                    "-Dserver.address=127.0.0.1", "-Dserver.port=" + port, "-jar", jar.toString())
                    .redirectErrorStream(true).redirectOutput(Path.of("target/app.log").toFile()).start();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                assertTrue(app.isAlive(), "Приложение завершилось: см. target/app.log");
                try {
                    client.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(1)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    return;
                } catch (java.io.IOException e) {
                    Thread.sleep(200);
                }
            }
            fail("Приложение не запустилось за 45 секунд: см. target/app.log");
        } catch (Exception | AssertionError e) {
            stop();
            throw e;
        }
    }

    @AfterAll
    void stop() throws InterruptedException {
        try {
            if (app != null) {
                app.destroy();
                if (!app.waitFor(5, TimeUnit.SECONDS)) {
                    app.destroyForcibly();
                    assertTrue(app.waitFor(5, TimeUnit.SECONDS), "Не удалось остановить приложение");
                }
            }
        } finally {
            mock.stop();
        }
    }

    @BeforeEach
    void reset() {
        mock.resetAll();
        stub("/auth", 200, "not json");
        stub("/doAction", 200, "");
    }

    private String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private void stub(String path, int status, String body) {
        mock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(status).withBody(body)));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> request(String token, String action, String key) throws Exception {
        String body = (token == null ? "" : "token=" + encode(token) + "&")
                + (action == null ? "" : "action=" + encode(action));
        return Allure.step("Отправить " + action + ", token=" + token + ", ключ=" + (key == null ? "отсутствует" : key.equals(KEY) ? "верный" : "неверный"), () -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (key != null) request.header("X-Api-Key", key);
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            Allure.addAttachment("Ответ сервиса", "text/plain", "HTTP " + response.statusCode() + "\n" + response.body());
            return response;
        });
    }

    private HttpResponse<String> request(String token, String action) throws Exception {
        return request(token, action, KEY);
    }

    private void result(HttpResponse<String> response, String expected) throws Exception {
        Allure.step("Проверить результат: " + expected, () -> {
            assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"), "Ожидается JSON");
            JsonNode body = json.readTree(response.body());
            assertNotNull(body, "Ответ не должен быть пустым");
            assertEquals(expected, body.path("result").asText(), "Неожиданный результат: " + response.body());
            if (expected.equals("ERROR")) {
                assertTrue(body.path("message").isTextual() && !body.path("message").asText().isBlank(), "Ошибка должна содержать причину");
            } else {
                assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "Успешный HTTP-ответ");
            }
        });
    }

    private void external(String path, String token, int count) {
        Allure.step("Внешний сервис: " + path + ", число обращений " + count, () ->
            mock.verify(count, postRequestedFor(urlEqualTo(path))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withHeader("Accept", containing("application/json"))
                    .withRequestBody(equalTo("token=" + encode(token)))));
    }

    private void noExternal() {
        assertEquals(0, mock.getAllServeEvents().size(), "Внешний сервис не должен вызываться");
    }

    @Test @DisplayName("Вход, действие и выход: после выхода действие запрещено")
    void sessionLifecycle() throws Exception {
        String token = token();
        result(request(token, "LOGIN"), "OK");
        result(request(token, "ACTION"), "OK");
        result(request(token, "LOGOUT"), "OK");
        result(request(token, "ACTION"), "ERROR");
        external("/auth", token, 1);
        external("/doAction", token, 1);
        assertEquals(2, mock.getAllServeEvents().size(), "Выход не вызывает внешний сервис");
    }

    @Test @DisplayName("Без входа действие запрещено и не передаётся внешнему сервису")
    void actionWithoutLogin() throws Exception {
        result(request(token(), "ACTION"), "ERROR");
        noExternal();
    }

    @Test @DisplayName("Выход одного пользователя не завершает сессию другого")
    void independentSessions() throws Exception {
        String first = token(), second = token();
        result(request(first, "LOGIN"), "OK");
        result(request(second, "LOGIN"), "OK");
        result(request(first, "LOGOUT"), "OK");
        result(request(first, "ACTION"), "ERROR");
        result(request(second, "ACTION"), "OK");
        external("/doAction", first, 0);
        external("/doAction", second, 1);
    }

    @Test @DisplayName("После выхода можно войти заново")
    void loginAgain() throws Exception {
        String token = token();
        result(request(token, "LOGIN"), "OK");
        result(request(token, "LOGOUT"), "OK");
        result(request(token, "LOGIN"), "OK");
        result(request(token, "ACTION"), "OK");
        external("/auth", token, 2);
        external("/doAction", token, 1);
    }

    @ParameterizedTest(name = "Внешняя авторизация вернула {0}: вход отклонён, сессия не создана")
    @ValueSource(ints = {400, 401, 403, 500, 503})
    void failedLogin(int status) throws Exception {
        String token = token();
        stub("/auth", status, "upstream error");
        result(request(token, "LOGIN"), "ERROR");
        result(request(token, "ACTION"), "ERROR");
        external("/auth", token, 1);
        external("/doAction", token, 0);
    }

    @ParameterizedTest(name = "Внешнее действие вернуло {0}: пользователь получает ошибку")
    @ValueSource(ints = {400, 401, 403, 500, 503})
    void failedAction(int status) throws Exception {
        String token = token();
        result(request(token, "LOGIN"), "OK");
        stub("/doAction", status, "upstream error");
        result(request(token, "ACTION"), "ERROR");
        external("/doAction", token, 1);
    }

    @ParameterizedTest(name = "Допустимый токен: {0}")
    @ValueSource(strings = {"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "00000000000000000000000000000000", "ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"})
    void validToken(String token) throws Exception {
        result(request(token, "LOGIN"), "OK");
        result(request(token, "LOGOUT"), "OK");
        external("/auth", token, 1);
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(Arguments.of("отсутствует", null), Arguments.of("пустой", ""),
                Arguments.of("31 символ", "A".repeat(31)), Arguments.of("33 символа", "A".repeat(33)),
                Arguments.of("строчные буквы", "a".repeat(32)), Arguments.of("кириллица", "А".repeat(32)),
                Arguments.of("пробел", "A".repeat(31) + " "), Arguments.of("спецсимвол", "A".repeat(31) + "!"));
    }

    @ParameterizedTest(name = "Недопустимый токен ({0}), операция {2}: отказ без внешнего вызова")
    @MethodSource("invalidTokenActions")
    void invalidToken(String label, String token, String action) throws Exception {
        result(request(token, action), "ERROR");
        noExternal();
    }

    static Stream<Arguments> invalidTokenActions() {
        return invalidTokens().flatMap(a -> Stream.of("LOGIN", "ACTION", "LOGOUT")
                .map(action -> Arguments.of(a.get()[0], a.get()[1], action)));
    }

    @ParameterizedTest(name = "Неизвестное или отсутствующее действие: [{0}]")
    @NullAndEmptySource @ValueSource(strings = {"login", "UNKNOWN", " LOGIN", "ACTION "})
    void invalidAction(String action) throws Exception {
        result(request(token(), action), "ERROR");
        noExternal();
    }

    @ParameterizedTest(name = "Неверный или отсутствующий API-ключ [{0}] блокирует {1}")
    @MethodSource("invalidKeys")
    void invalidKey(String key, String action) throws Exception {
        String token = token();
        result(request(token, "LOGIN"), "OK");
        mock.resetRequests();
        HttpResponse<String> denied = request(token, action, key);
        Allure.step("Доступ отклонён", () -> {
            assertTrue(denied.statusCode() == 401 || denied.statusCode() == 403
                    || (denied.statusCode() >= 200 && denied.statusCode() < 300
                        && json.readTree(denied.body()).path("result").asText().equals("ERROR")),
                    "Запрос должен быть отклонён: " + denied.statusCode() + " " + denied.body());
        });
        noExternal();
        result(request(token, "ACTION"), "OK");
        external("/doAction", token, 1);
    }

    static Stream<Arguments> invalidKeys() {
        return Stream.of("LOGIN", "ACTION", "LOGOUT").flatMap(action ->
                Stream.of(Arguments.of(null, action), Arguments.of("", action), Arguments.of("wrong", action)));
    }
}
