package org.example;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.example.ClaudeAgentConstants.API_KEY_ENV;
import static org.example.ClaudeAgentConstants.AUTH_TOKEN_ENV;
import static org.example.ClaudeAgentConstants.BROWSER_UA;
import static org.example.ClaudeAgentConstants.CONFIG_DIR_ENV;
import static org.example.ClaudeAgentConstants.NO_CREDENTIALS_HELP;
import static org.example.ClaudeAgentConstants.REJECTED_CREDENTIALS_MESSAGE;
import static org.example.ClaudeAgentConstants.VIDEO_ID;
import static org.example.ClaudeAgentConstants.YT_VIDEOS_ONLY;

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

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAgentApp.class);

    private static final Gson GSON = new Gson();

    /** Shared client for the outbound YouTube lookups behind {@code /api/video}. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
        server.createContext("/api/video", ClaudeAgentApp::handleVideo);
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
                    .model(Model.CLAUDE_SONNET_4_5) // Or Model.CLAUDE_3_5_HAIKU
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

    /** Largest {@code count} the frontend's selector offers; also the hard cap here. */
    private static final int MAX_VIDEOS = 10;

    /**
     * GET /api/video?q=...&count=N — resolves the top N (1..10, default 1) YouTube videos
     * for a query and returns {@code {"videos": [{"videoId","title","author"}, ...]}}. The
     * frontend lists them and embeds whichever one the user picks. No API key: it reads the
     * public search-results page and grabs video ids, then oEmbed for each title/author.
     */
    private static void handleVideo(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, errorJson("Only GET method is supported"));
            return;
        }

        String rawQuery = exchange.getRequestURI().getRawQuery();
        String query = queryParam(rawQuery, "q");
        if (query == null || query.isBlank()) {
            sendJson(exchange, 400, errorJson("Missing 'q' query parameter"));
            return;
        }
        int count = clampCount(queryParam(rawQuery, "count"));

        LOG.info("YouTube lookup: {} (count={})", query, count);

        try {
            List<String> videoIds = searchYouTube(query, count);
            if (videoIds.isEmpty()) {
                sendJson(exchange, 404, errorJson("No video found for that query"));
                return;
            }

            // oEmbed is one HTTP round-trip per id; fan them out so 10 videos don't
            // cost 10x one lookup.
            List<CompletableFuture<JsonObject>> pending = videoIds.stream()
                    .map(id -> CompletableFuture.supplyAsync(() -> videoJson(id)))
                    .toList();

            JsonArray videos = new JsonArray();
            for (CompletableFuture<JsonObject> future : pending) {
                videos.add(future.join());
            }

            JsonObject responseJson = new JsonObject();
            responseJson.add("videos", videos);

            LOG.info("YouTube lookup resolved {} video(s)", videos.size());
            sendJson(exchange, 200, GSON.toJson(responseJson));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(exchange, 502, errorJson("YouTube lookup was interrupted"));
        } catch (Exception e) {
            LOG.error("YouTube lookup failed for query: {}", query, e);
            sendJson(exchange, 502, errorJson("YouTube lookup failed: " + e.getMessage()));
        }
    }

    /** Parses {@code count}, clamping to 1..{@value #MAX_VIDEOS}; missing/garbage -> 1. */
    private static int clampCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Math.min(MAX_VIDEOS, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** {videoId, title, author} for one id, title/author best-effort via oEmbed. */
    private static JsonObject videoJson(String videoId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("videoId", videoId);
        fetchOembed(videoId).ifPresent(meta -> {
            if (meta.has("title")) {
                obj.addProperty("title", meta.get("title").getAsString());
            }
            if (meta.has("author_name")) {
                obj.addProperty("author", meta.get("author_name").getAsString());
            }
        });
        return obj;
    }

    /** Reads the public results page for {@code query} and returns up to {@code limit} distinct video ids. */
    private static List<String> searchYouTube(String query, int limit) throws IOException, InterruptedException {
        String url = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&sp=" + YT_VIDEOS_ONLY;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", BROWSER_UA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = VIDEO_ID.matcher(response.body());
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        while (matcher.find() && ids.size() < limit) {
            ids.add(matcher.group(1));
        }
        return new ArrayList<>(ids);
    }

    /** Best-effort title/author for a video id via YouTube's public oEmbed endpoint. */
    private static Optional<JsonObject> fetchOembed(String videoId) {
        try {
            String url = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Optional.ofNullable(GSON.fromJson(response.body(), JsonObject.class));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.warn("Could not fetch oEmbed metadata for {}", videoId, e);
        }
        return Optional.empty();
    }

    /** Pulls a single decoded parameter out of a raw (still URL-encoded) query string. */
    private static String queryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
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
