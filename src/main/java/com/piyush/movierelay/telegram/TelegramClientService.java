package com.piyush.movierelay.telegram;

import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import it.tdlight.util.UnsupportedNativeLibraryException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class TelegramClientService {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientService.class);

    private final TelegramProperties telegramProperties;

    @Value("${tdlib.data-path}")
    private String dataPath;

    private SimpleTelegramClientFactory clientFactory;
    private SimpleTelegramClient client;
    // Tracks TDLib's own connection state (network down, connecting, ready, ...) so the app can
    // show an honest "reconnecting" banner instead of only ever reacting after an individual
    // search/click times out. Starts as "connecting" since that's the true state before the
    // first UpdateConnectionState arrives.
    private volatile TdApi.ConnectionState connectionState = new TdApi.ConnectionStateConnecting();

    public TelegramClientService(TelegramProperties telegramProperties) {
        this.telegramProperties = telegramProperties;
    }

    // Must run before any other ApplicationReadyEvent listener that touches Telegram (e.g.
    // DownloadService resuming in-progress downloads on startup) - client is only assigned
    // partway through this method, and Spring does not otherwise guarantee listener ordering
    // for the same event.
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void login() throws UnsupportedNativeLibraryException, IOException {
        Init.init();
        Log.setVerbosityLevel(1);

        clientFactory = new SimpleTelegramClientFactory();

        APIToken apiToken = new APIToken(telegramProperties.getApiId(), telegramProperties.getApiHash());
        TDLibSettings settings = TDLibSettings.create(apiToken);

        Path sessionPath = Path.of(dataPath, "session");
        Path downloadsPath = Path.of(dataPath, "downloads");
        settings.setDatabaseDirectoryPath(sessionPath);
        settings.setDownloadedFilesDirectoryPath(downloadsPath);

        // TDLib's storage optimizer periodically deletes downloaded files it thinks are no
        // longer needed - fine for a normal chat client, but wrong for us since downloaded
        // movies ARE the point of this app and must persist until we explicitly delete them.
        settings.setEnableStorageOptimizer(false);

        SimpleTelegramClientBuilder clientBuilder = clientFactory.builder(settings);

        AuthenticationSupplier<?> authenticationData = AuthenticationSupplier.consoleLogin();

        client = clientBuilder.build(authenticationData);
        client.addUpdateHandler(TdApi.UpdateConnectionState.class,
                update -> connectionState = update.state);

        try {
            TdApi.User me = client.getMeAsync().get(2, TimeUnit.MINUTES);
            log.info("Logged in to Telegram as: {} {} (@{})", me.firstName, me.lastName,
                    me.usernames != null ? me.usernames.editableUsername : "no-username");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Login interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Telegram login failed or timed out", e);
        }
    }

    /**
     * True once TDLib reports the connection to Telegram is fully up (ConnectionStateReady) -
     * false for every other state (connecting, waiting for network, updating, ...), which is
     * exactly when searches/clicks would otherwise just silently hang or fail.
     */
    public boolean isConnected() {
        return connectionState instanceof TdApi.ConnectionStateReady;
    }

    /** A short, human-readable label for the current connection state, for the status banner. */
    public String getConnectionStateLabel() {
        if (connectionState instanceof TdApi.ConnectionStateWaitingForNetwork) return "waiting_for_network";
        if (connectionState instanceof TdApi.ConnectionStateConnectingToProxy) return "connecting_to_proxy";
        if (connectionState instanceof TdApi.ConnectionStateConnecting) return "connecting";
        if (connectionState instanceof TdApi.ConnectionStateUpdating) return "updating";
        if (connectionState instanceof TdApi.ConnectionStateReady) return "ready";
        return "unknown";
    }

    public SimpleTelegramClient getClient() {
        return client;
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing Telegram client", e);
            }
        }
        if (clientFactory != null) {
            try {
                clientFactory.close();
            } catch (Exception e) {
                log.warn("Error closing Telegram client factory", e);
            }
        }
    }
}
