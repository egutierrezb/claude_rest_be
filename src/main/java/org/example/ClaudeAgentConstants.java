package org.example;

import java.util.regex.Pattern;

/**
 * Fixed values shared by {@link ClaudeAgentApp}. Only immutable constants belong here —
 * the logger, the Gson instance and the HttpClient stay on the app class, since those are
 * collaborators rather than configuration.
 */
final class ClaudeAgentConstants {

    private ClaudeAgentConstants() {
        // Constants holder; not instantiable.
    }

    // Kept from the original sample as a reference for quick manual testing.
    public static final String[] REQUEST1 =
            {"Cual es tu carro favorito?", "Cual es tu programa de TV favorito"};

    // --- YouTube lookup behind /api/video ---

    /** First {@code "videoId":"XXXXXXXXXXX"} in a YouTube results page is the top hit. */
    static final Pattern VIDEO_ID = Pattern.compile("\"videoId\":\"([\\w-]{11})\"");

    /** {@code sp} filter that restricts a YouTube search to videos only. */
    static final String YT_VIDEOS_ONLY = "EgIQAQ%3D%3D";

    static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    // --- Credential resolution ---

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
}
