package org.example;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

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

    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws IOException {
        // 1. Initialize the client (automatically reads ANTHROPIC_API_KEY from environment)
        AnthropicClient client = AnthropicOkHttpClient.fromEnv();

        int port = 4567;
        HttpServer server = createServer(port, client);
        server.start();
        System.out.println("Claude backend listening on http://localhost:" + port);
        System.out.println("POST a question to http://localhost:" + port + "/api/ask");
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
            sendJson(exchange, 405, errorJson("Only POST is supported"));
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

            System.out.println("Claude Question: " + question);

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

            System.out.println("Claude Response: " + answer);

            JsonObject responseJson = new JsonObject();
            responseJson.addProperty("answer", answer.toString());
            sendJson(exchange, 200, GSON.toJson(responseJson));
        } catch (Exception e) {
            System.err.println("Error calling Claude API: " + e.getMessage());
            e.printStackTrace();
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
