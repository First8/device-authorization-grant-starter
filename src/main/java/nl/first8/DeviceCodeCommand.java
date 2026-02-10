package nl.first8;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.List;

@Command(name = "device-code", showDefaultValues = true, mixinStandardHelpOptions = true)
public class DeviceCodeCommand implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DeviceCodeCommand.class);

    @CommandLine.Option(names = "--endpoint", required = true, description = "The OIDC base endpoint including the realm. For Keycloak based installations this is: https://<host>/realms/<realm>")
    String endpoint;

    @CommandLine.Option(names = "--client-id", required = true, description = "The id of the client to use.")
    String clientId;

    @CommandLine.Option(names = "--scopes", description = "The comma separated list of scopes to use.", defaultValue = "email")
    List<String> scopes;

    @CommandLine.Option(names = "--interval", description = "The polling interval in seconds to use for checking if the device code flow is completed.", defaultValue = "5")
    int interval;

    @CommandLine.Option(names = "--timeout", description = "The timeout in seconds to wait for the device code flow to complete.", defaultValue = "60")
    int timeout;

    @CommandLine.Option(names = "--headless", description = "Do not try to open a browser, but print instructions.")
    boolean headless = false;

    @Inject
    DeviceCodeClient deviceCodeClient;

    @Override
    public void run() {
        deviceCodeClient.authenticate(endpoint, clientId, scopes, interval, timeout, headless)
                .ifPresentOrElse(tokens -> {
                    log.info("Successfully authenticated device code with tokens: {}", tokens);
                }, () -> {
                    log.info("Failed to complete the device code flow");
                });
    }
}
