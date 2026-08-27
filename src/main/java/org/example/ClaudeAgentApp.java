package org.example;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * Small HTTP wrapper around the Anthropic SDK so a React frontend can ask
 * Claude a question and display the answer.
 *
 * Endpoint:
 *   POST /api/ask
 *   body: {"question": "Cual es tu carro favorito?"}
 *   response: {"answer": "..."}
 *
 * Run with:
 *   ANTHROPIC_API_KEY=sk-ant-... mvn compile exec:java -Dexec.mainClass=org.example.ClaudeAgentApp
 */
public class ClaudeAgentApp {

    // Kept from the original sample as a reference for quick manual testing.
    public static String[] REQUEST1 = {"Cual es tu carro favorito?", "Cual es tu programa de TV favorito"};

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAgentApp.class);

    private static final Gson GSON = new Gson();

    static final String API_KEY_ENV = "ANTHROPIC_API_KEY";
    static final String AUTH_TOKEN_ENV = "ANTHROPIC_AUTH_TOKEN";
    static final String CONFIG_DIR_ENV = "ANTHROPIC_CONFIG_DIR";

    static final String NO_CREDENTIALS_HELP = """
            No Anthropic credentials found. An API key is not the only option \
            — the SDK resolves credentials in this order:
              1. ANTHROPIC_API_KEY
              2. ANTHROPIC_AUTH_TOKEN
              3. an OAuth profile under ~/.config/anthropic, written by `ant auth login`
            A Claude Pro or Max subscription authenticates through the OAuth profile:
              brew install anthropics/tap/ant && ant auth login
            Otherwise create an API key at https://console.anthropic.com/settings/keys \
            and export it as ANTHROPIC_API_KEY.""";

    /** Message returned to callers when Anthropic rejects whatever credential we did send. */
    static final String REJECTED_CREDENTIALS_MESSAGE =
            "The backend's Anthropic credentials were rejected. Check `ant auth status`, "
                    + "or re-run `ant auth login` if the OAuth profile's refresh token expired.";

    public static void main(String[] args) throws IOException {
        // Refuse to start without a credential. fromEnv() happily builds a client when none is
        // resolvable, so the server would otherwise look healthy and fail every request with a 401.
        Optional<String> credentialSource = resolveCredentialSource(System::getenv, defaultConfigDir());
        if (credentialSource.isEmpty()) {
            LOG.error(NO_CREDENTIALS_HELP);
            System.exit(1);
        }
        LOG.info("Authenticating with credential source: {}", credentialSource.get());

        // 1. Initialize the client (resolves the credential found above)
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        int port = 4567;
        HttpServer server = createServer(port, client);
        server.start();
        LOG.info("Claude backend listening on http://localhost:{}", port);
        LOG.info("POST a question to http://localhost:{}/api/ask", port);
    }

    /**
     * Names the first credential source the SDK will find, or empty if it will find none.
     * Mirrors the SDK's own resolution order; {@code env} and {@code configDir} are parameters
     * rather than direct lookups so this stays testable without mutating the real environment.
     */
    static Optional<String> resolveCredentialSource(UnaryOperator<String> env, Path configDir) {
        if (isSet(env.apply(API_KEY_ENV))) {
            return Optional.of(API_KEY_ENV);
        }
        if (isSet(env.apply(AUTH_TOKEN_ENV))) {
            return Optional.of(AUTH_TOKEN_ENV);
        }
        // `ant auth login` writes credentials/<profile>.json; an empty directory is not a credential.
        Path credentials = configDir.resolve("credentials");
        if (Files.isDirectory(credentials)) {
            try (Stream<Path> profiles = Files.list(credentials)) {
                if (profiles.anyMatch(Files::isRegularFile)) {
                    return Optional.of("OAuth profile in " + credentials);
                }
            } catch (IOException e) {
                LOG.warn("Could not read {}, ignoring it as a credential source", credentials, e);
            }
        }
        return Optional.empty();
    }

    /** An empty value still occupies its slot in the SDK's precedence chain, so treat it as unset. */
    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    static Path defaultConfigDir() {
        String configured = System.getenv(CONFIG_DIR_ENV);
        return isSet(configured)
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".config", "anthropic");
    }

    /**
     * Wires up the endpoint without starting it. Pass port 0 to let the OS pick a free
     * port, which is what the tests do so they never collide with a running server.
     */
    static HttpServer createServer(int port, AnthropicClient client) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/ask", exchange -> handleAsk(exchange, client));
        server.setExecutor(null); // default executor
        return server;
    }

    private static void handleAsk(HttpExchange exchange, AnthropicClient client) throws IOException {
        // Handle CORS preflight so the Vite dev server (different origin) can call us.
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorJson("Only POST method is supported"));
            return;
        }

        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject requestJson = GSON.fromJson(body, JsonObject.class);
            String question = requestJson != null && requestJson.has("question")
                    ? requestJson.get("question").getAsString()
                    : null;

            if (question == null || question.isBlank()) {
                sendJson(exchange, 400, errorJson("Missing 'question' field"));
                return;
            }

            LOG.info("Claude Question: {}", question);

            // 2. Build the request parameters
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_OPUS_5) // Or Model.CLAUDE_3_5_HAIKU
                    .maxTokens(1024L)
                    .addUserMessage(question)
                    .build();

            // 3. Send request and get response
            Message message = client.messages().create(params);

            // 4. Collect output text
            StringBuilder answer = new StringBuilder();
            message.content().forEach(contentBlock ->
                    contentBlock.text().ifPresent(text -> answer.append(text.text())));

            LOG.info("Claude Response: {}", answer);

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("answer", answer.toString());
            sendJson(exchange, 200, GSON.toJson(responseJson));
        } catch (UnauthorizedException e) {
            // 503 rather than 401: the caller's own credentials are fine, it is this server that is
            // misconfigured. Relaying 401 would tell the frontend to prompt its user to log in.
            LOG.error("Anthropic rejected the backend's credentials", e);
            sendJson(exchange, 503, errorJson(REJECTED_CREDENTIALS_MESSAGE));
        } catch (Exception e) {
            LOG.error("Error calling Claude API", e);
            sendJson(exchange, 500, errorJson("Error calling Claude API: " + e.getMessage()));
        }
    }

    private static String errorJson(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        return GSON.toJson(error);
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
