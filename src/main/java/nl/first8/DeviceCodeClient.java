package nl.first8;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@ApplicationScoped
public class DeviceCodeClient {

    private static final Logger log = LoggerFactory.getLogger(DeviceCodeClient.class);

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Inject
    ObjectMapper objectMapper;

    public Optional<Tokens> authenticate(String endpoint, String clientId, List<String> scopes, int interval, int timeout, boolean headless) {
        try {
            // Step 1: Request device code
            DeviceCodeResponse deviceCodeResponse = requestDeviceCode(endpoint, clientId, scopes);

            String deviceCode = deviceCodeResponse.deviceCode();
            String userCode = deviceCodeResponse.userCode();
            String verificationUri = deviceCodeResponse.verificationUri();
            String verificationUriComplete = deviceCodeResponse.verificationUriComplete();

            int prefInterval = deviceCodeResponse.interval.orElse(interval);

            // Optional: auto-open browser
            if (!headless) {
                try {
                    java.awt.Desktop.getDesktop().browse(new URI(verificationUriComplete));
                } catch (Exception ignored) {
                    // fallback to manual
                    printDeviceCodeInstructions(verificationUri, userCode);
                }
            } else {
                printDeviceCodeInstructions(verificationUri, userCode);
            }

            // Step 2: Poll token endpoint until user authenticates
            return pollDeviceFlow(endpoint, clientId, deviceCode, prefInterval, timeout)
                    .await().asOptional().atMost(Duration.ofSeconds(timeout));
        } catch (Exception e) {
            log.error(e.getMessage());
            return Optional.empty();
        }
    }

    private static void printDeviceCodeInstructions(String verificationUri, String userCode) {
        log.info("Open this URL in your browser: {}", verificationUri);
        log.info("Enter this code when prompted: {}", userCode);
    }

    private DeviceCodeResponse requestDeviceCode(String endpoint, String clientId, List<String> scopes) throws IOException, InterruptedException {
        String url = String.format("%s/protocol/openid-connect/auth/device", endpoint);

        Set<String> allScopes = new HashSet<>(scopes);
        allScopes.add("oidc");

        String body = "client_id=" + clientId + "&scope=" + String.join(" ", allScopes);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to get device code: " + response.body());
        }

        return objectMapper.readValue(response.body(), DeviceCodeResponse.class);
    }

    public Uni<Tokens> pollDeviceFlow(String endpoint, String clientId, String deviceCode, int interval, int timeout) {
        return Uni.createFrom().deferred(() -> callTokenEndpoint(endpoint, clientId, deviceCode))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .flatMap(response -> {
                    if (response.isComplete()) {
                        // token ready → emit normally
                        return Uni.createFrom().item(new Tokens(response.tokenResponse().accessToken(), response.tokenResponse().refreshToken()));
                    } else if (response.isFailed()) {
                        // token request failed → propagate failure
                        return Uni.createFrom().failure(new RuntimeException("Token request failed"));
                    } else {
                        // token not ready → treat as failure to trigger retry
                        return Uni.createFrom().failure(new TokenNotReadyException());
                    }
                })
                .onFailure(TokenNotReadyException.class)
                .retry()
                .withBackOff(Duration.ofSeconds(interval), Duration.ofSeconds(interval)) // poll every 5 seconds
                .expireIn(Duration.ofSeconds(timeout).toMillis());
    }

    private Uni<PollResponse> callTokenEndpoint(String endpoint, String clientId, String deviceCode) {
        try {
            String url = String.format("%s/protocol/openid-connect/token", endpoint);
            String body = "grant_type=urn:ietf:params:oauth:grant-type:device_code"
                    + "&device_code=" + deviceCode
                    + "&client_id=" + clientId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
                return Uni.createFrom().item(new PollResponse(tokenResponse, null));
            } else {
                ErrorResponse error = objectMapper.readValue(response.body(), ErrorResponse.class);
                return Uni.createFrom().item(new PollResponse(null, error));
            }
        } catch (Exception e) {
            log.error("Failed to poll device code", e);
            return Uni.createFrom().item(new PollResponse(null, null));
        }
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("refresh_expires_in") int refreshExpiresIn,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("not-before-policy") Instant notBefore,
            @JsonProperty("session_state") String sessionState,
            @JsonProperty("scope") String scope) {
    }


    public record DeviceCodeResponse(
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri,
            @JsonProperty("verification_uri_complete") String verificationUriComplete,
            @JsonProperty("expires_in") int expiresIn,
            @JsonProperty("interval") Optional<Integer> interval
    ) {
    }

    public record PollResponse(TokenResponse tokenResponse, ErrorResponse errorResponse) {

        boolean isComplete() {
            return tokenResponse != null;
        }

        boolean isFailed() {
            return tokenResponse == null &&
                    (errorResponse == null || !errorResponse.slowDown() && !errorResponse.authPending());
        }
    }

    public record ErrorResponse(@JsonProperty("error") String error,
                                @JsonProperty("error_description") String errorDescription) {

        boolean authPending() {
            return error.equals("authorization_pending");
        }

        boolean slowDown() {
            return error.equals("slow_down");
        }
    }

    private static class TokenNotReadyException extends Exception {
    }
}
