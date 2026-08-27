package org.example;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises POST /api/ask end to end over real HTTP, with the Anthropic client
 * mocked out so no request ever leaves the machine and no API key is needed.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeAgentAppTest {

    private static final Gson GSON = new Gson();

    @Mock
    private AnthropicClient anthropicClient;

    @Mock
    private MessageService messageService;

    private HttpServer server;
    private HttpClient httpClient;
    private URI askUri;

    @BeforeEach
    void startServer() throws IOException {
        // lenient: the validation tests never reach the SDK, so this stub goes unused there.
        lenient().when(anthropicClient.messages()).thenReturn(messageService);

        server = ClaudeAgentApp.createServer(0, anthropicClient);
        server.start();

        httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        askUri = URI.create("http://localhost:" + server.getAddress().getPort() + "/api/ask");
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("POST with a question returns Claude's answer as JSON")
    void postReturnsAnswer() throws Exception {
        // Build the reply first: messageWithText() stubs a mock, and Mockito forbids that
        // while another stubbing is still in flight.
        Message reply = messageWithText("Mi carro favorito es el Delorean.");
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);

        HttpResponse<String> response = post("{\"question\": \"Cual es tu carro favorito?\"}");

        assertEquals(200, response.statusCode());
        assertEquals("Mi carro favorito es el Delorean.", answerOf(response));
        assertEquals("application/json; charset=utf-8",
                response.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    @DisplayName("POST forwards the question to the Anthropic SDK with the expected params")
    void postForwardsQuestionToSdk() throws Exception {
        Message reply = messageWithText("ok");
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);

        post("{\"question\": \"Cual es tu programa de TV favorito\"}");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messageService).create(captor.capture());

        MessageCreateParams params = captor.getValue();
        assertEquals(Model.CLAUDE_OPUS_5, params.model());
        assertEquals(1024L, params.maxTokens());
        assertEquals(1, params.messages().size());
        assertTrue(params.messages().get(0).content().toString().contains("Cual es tu programa de TV favorito"));
    }

    @Test
    @DisplayName("POST concatenates every text block of the response")
    void postConcatenatesTextBlocks() throws Exception {
        Message reply = messageWithText("Primero. ", "Segundo.");
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);

        HttpResponse<String> response = post("{\"question\": \"dos partes\"}");

        assertEquals(200, response.statusCode());
        assertEquals("Primero. Segundo.", answerOf(response));
    }

    @Test
    @DisplayName("POST answers with CORS headers so the Vite dev server can call it")
    void postSendsCorsHeaders() throws Exception {
        Message reply = messageWithText("ok");
        when(messageService.create(any(MessageCreateParams.class))).thenReturn(reply);

        HttpResponse<String> response = post("{\"question\": \"hola\"}");

        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    }

    @Test
    @DisplayName("POST without a question field returns 400 and never calls Claude")
    void postWithoutQuestionReturns400() throws Exception {
        HttpResponse<String> response = post("{\"foo\": \"bar\"}");

        assertEquals(400, response.statusCode());
        assertEquals("Missing 'question' field", errorOf(response));
        verify(messageService, never()).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("POST with a blank question returns 400 and never calls Claude")
    void postWithBlankQuestionReturns400() throws Exception {
        HttpResponse<String> response = post("{\"question\": \"   \"}");

        assertEquals(400, response.statusCode());
        assertEquals("Missing 'question' field", errorOf(response));
        verify(messageService, never()).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("POST with an empty body returns 400 and never calls Claude")
    void postWithEmptyBodyReturns400() throws Exception {
        HttpResponse<String> response = post("");

        assertEquals(400, response.statusCode());
        assertEquals("Missing 'question' field", errorOf(response));
        verify(messageService, never()).create(any(MessageCreateParams.class));
    }

    @Test
    @DisplayName("POST surfaces an SDK failure as a 500 instead of hanging the client")
    void postReturns500WhenSdkFails() throws Exception {
        when(messageService.create(any(MessageCreateParams.class)))
                .thenThrow(new RuntimeException("rate limited"));

        HttpResponse<String> response = post("{\"question\": \"hola\"}");

        assertEquals(500, response.statusCode());
        assertTrue(errorOf(response).contains("rate limited"),
                "expected the SDK failure in the error payload, got: " + errorOf(response));
    }

    @Test
    @DisplayName("A rejected credential is reported as 503, not as a generic 500")
    void rejectedCredentialsReturn503() throws Exception {
        when(messageService.create(any(MessageCreateParams.class)))
                .thenThrow(UnauthorizedException.builder()
                        .headers(Headers.builder().build())
                        .body(JsonValue.from(Map.of("error", Map.of("message", "x-api-key header is required"))))
                        .build());

        HttpResponse<String> response = post("{\"question\": \"hola\"}");

        assertEquals(503, response.statusCode());
        assertEquals(ClaudeAgentApp.REJECTED_CREDENTIALS_MESSAGE, errorOf(response));
    }

    @Test
    @DisplayName("ANTHROPIC_API_KEY is reported as the credential source when set")
    void apiKeyIsResolved() {
        assertEquals(Optional.of("ANTHROPIC_API_KEY"),
                ClaudeAgentApp.resolveCredentialSource(
                        name -> "ANTHROPIC_API_KEY".equals(name) ? "sk-ant-test" : null,
                        Path.of("/nonexistent")));
    }

    @Test
    @DisplayName("An OAuth profile counts as a credential when no env var is set")
    void oauthProfileIsResolved(@TempDir Path configDir) throws Exception {
        Files.createDirectory(configDir.resolve("credentials"));
        Files.writeString(configDir.resolve("credentials").resolve("default.json"), "{}");

        assertTrue(ClaudeAgentApp.resolveCredentialSource(name -> null, configDir)
                .orElse("").startsWith("OAuth profile in "));
    }

    @Test
    @DisplayName("A blank env var does not count as a credential")
    void blankEnvVarIsNotACredential() {
        assertEquals(Optional.empty(),
                ClaudeAgentApp.resolveCredentialSource(name -> "  ", Path.of("/nonexistent")));
    }

    @Test
    @DisplayName("An empty credentials directory does not count as a credential")
    void emptyCredentialsDirIsNotACredential(@TempDir Path configDir) throws Exception {
        Files.createDirectory(configDir.resolve("credentials"));

        assertEquals(Optional.empty(),
                ClaudeAgentApp.resolveCredentialSource(name -> null, configDir));
    }

    @Test
    @DisplayName("Non-POST methods are rejected with 405")
    void getIsRejected() throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(askUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(405, response.statusCode());
        assertEquals("Only POST method is supported", errorOf(response));
        verify(messageService, never()).create(any(MessageCreateParams.class));
    }

    private HttpResponse<String> post(String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(askUri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String answerOf(HttpResponse<String> response) {
        return GSON.fromJson(response.body(), JsonObject.class).get("answer").getAsString();
    }

    private static String errorOf(HttpResponse<String> response) {
        return GSON.fromJson(response.body(), JsonObject.class).get("error").getAsString();
    }

    /**
     * Stands in for the Message the SDK hands back, carrying one real text block per
     * argument. Only content() is stubbed: the handler reads nothing else, and a full
     * Message would need a dozen id/usage/stop-reason fields that no assertion looks at.
     */
    private static Message messageWithText(String... texts) {
        Message message = mock(Message.class);
        when(message.content()).thenReturn(
                Arrays.stream(texts).map(ClaudeAgentAppTest::textBlock).toList());
        return message;
    }

    private static ContentBlock textBlock(String text) {
        return ContentBlock.ofText(TextBlock.builder()
                .text(text)
                // The builder has no default for citations, so it has to be set explicitly.
                .citations(Optional.empty())
                .type(JsonValue.from("text"))
                .build());
    }
}
