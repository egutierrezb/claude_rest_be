# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Rules

@.claude/rules/pr-comments.md

## Commands

```bash
# Run the server (reads ANTHROPIC_API_KEY from the environment)
export ANTHROPIC_API_KEY=sk-ant-...
mvn compile exec:java              # mainClass is preconfigured in pom.xml

mvn test                           # full suite
mvn test -Dtest='ClaudeAgentAppTest#getIsRejected'   # single test
mvn test -Dtest='ClaudeAgentAppTest'                 # single class
```

The tests need no API key and make no network calls — they mock the Anthropic client.

Manual check against a running server:

```bash
curl -X POST http://localhost:4567/api/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"Cual es tu carro favorito?"}'
```

## Repository layout gotchas

- **The git repository root is `backend/`, not the project root.** `claude-chat-app/` contains `backend/`, `frontend/`, and a `README.md` (written in Spanish), but only `backend/` is version controlled. The React frontend is not in any git repo.
- **`origin/main` and `origin/master` have unrelated histories.** `main` holds a standalone initial commit from repo creation; all real work descends from `master`. Open pull requests against `master` — a PR targeting `main` shows every file as new and will not merge cleanly.

## Architecture

A single class, `ClaudeAgentApp`, wrapping the Anthropic SDK in the JDK's built-in `com.sun.net.httpserver.HttpServer` (no servlet container or web framework). One endpoint: `POST /api/ask`, taking `{"question": "..."}` and returning `{"answer": "..."}`, or `{"error": "..."}` on 400/405/500.

The design decision that shapes everything else is the **testability seam in `createServer(int port, AnthropicClient client)`**:

- The `AnthropicClient` is a parameter rather than constructed internally, so tests inject a Mockito mock and no request leaves the machine. `main()` is the only place that calls `AnthropicOkHttpClient.fromEnv()`.
- `createServer` wires the route but does not start the server; the caller starts it. Tests pass port `0` so the OS assigns a free port, then read it back via `server.getAddress().getPort()`. This is why the suite never collides with a server already running on 4567.

The result is that `ClaudeAgentAppTest` drives real HTTP through `java.net.http.HttpClient` against a real `HttpServer` — these are integration tests over the actual wire format, not handler unit tests. Assertions cover status codes, the JSON body, and the `Content-Type` and CORS headers.

CORS is wide open (`Access-Control-Allow-Origin: *`) because the Vite dev server runs on a different port. `OPTIONS` is short-circuited with a 204 before method validation.

## Working with the Anthropic SDK here

`anthropic-java` builders validate **every** declared field at `build()` time, including optional ones, which have no defaults — `TextBlock` requires `citations`, `Usage` requires seven separate fields, all as explicit `Optional.empty()`. Constructing a full `Message` in a test costs a dozen lines that no assertion reads.

So tests `mock(Message.class)` and stub only `content()`, while keeping real `ContentBlock`/`TextBlock` values where the handler actually walks them (see `messageWithText` and `textBlock`).

Two traps when extending these tests:

- Build the mocked reply into a local variable *before* `when(messageService.create(...)).thenReturn(reply)`. Stubbing a second mock inside an in-flight stubbing throws `UnfinishedStubbing`, and Mockito only surfaces it on the *next* test's first mock interaction — so the stack trace blames the wrong test.
- `anthropicClient.messages()` is stubbed with `lenient()` because the validation tests (400/405) return before reaching the SDK, and strict stubs would fail them as unnecessary.

## Logging

Use the SLF4J `LOG` field on `ClaudeAgentApp`; there is no `System.out`/`System.err` printing in this codebase.

`slf4j-api` is declared explicitly in `pom.xml` even though the Anthropic SDK already pulls it in transitively — the transitive copy is `runtime` scope only and will not compile. `slf4j-simple` is the provider; **without a provider SLF4J binds to a no-op and silently discards every line**, so logging code looks correct and produces nothing.

Log exceptions by passing the throwable as the trailing argument (`LOG.error("...", e)`) so the stack trace is preserved — `e.getMessage()` alone is empty for exceptions like `NullPointerException`.
