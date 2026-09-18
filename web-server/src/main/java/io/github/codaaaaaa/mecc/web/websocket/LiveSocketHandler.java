package io.github.codaaaaaa.mecc.web.websocket;

import io.github.codaaaaaa.mecc.core.auth.ClientInfo;
import io.github.codaaaaaa.mecc.core.error.ErrorCode;
import io.github.codaaaaaa.mecc.core.error.MeccException;
import io.github.codaaaaaa.mecc.core.live.LiveEvent;
import io.github.codaaaaaa.mecc.core.live.LiveEventService;
import io.github.codaaaaaa.mecc.web.api.ApiSecurity;
import io.github.codaaaaaa.mecc.web.api.ErrorResponse;
import io.github.codaaaaaa.mecc.web.api.RequestTrust;
import io.github.codaaaaaa.mecc.web.api.SessionResolver;
import io.github.codaaaaaa.mecc.web.http.ClientAddressResolver;
import io.github.codaaaaaa.mecc.web.json.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /ws/v1}: live updates for the web UI (spec section 31).
 *
 * <p>Security mirrors the REST API: the upgrade is refused for foreign browser origins (no cross-site
 * WebSocket hijacking), the device token is resolved before any event is sent, and it is re-checked every
 * {@link #REAUTHENTICATE_EVERY}, so a revoked device is disconnected. What each connection may see is decided
 * by the {@link LiveEventService}.
 *
 * <p>Client messages (JSON): {@code {"type":"subscribe","networkId":"...","locale":"zh_cn"}},
 * {@code {"type":"unsubscribe","networkId":"..."}}, {@code {"type":"ping"}}.
 */
public final class LiveSocketHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(LiveSocketHandler.class);
    public static final String PATH = "/ws/v1";
    static final Duration IDLE_TIMEOUT = Duration.ofSeconds(90);
    static final Duration REAUTHENTICATE_EVERY = Duration.ofSeconds(60);
    static final int MAX_MESSAGE_BYTES = 4096;
    /** A client that falls this far behind is disconnected; it recovers through REST on reconnect. */
    static final int MAX_QUEUED_MESSAGES = 256;
    static final int POLICY_VIOLATION = StatusCode.POLICY_VIOLATION;

    private LiveSocketHandler() {
    }

    public static WebSocketUpgradeHandler create(Server server, LiveEventService live, SessionResolver sessions,
                                                 ApiSecurity security, JsonCodec json, ScheduledExecutorService scheduler) {
        ClientAddressResolver addresses = new ClientAddressResolver(security.trustedProxies());
        return WebSocketUpgradeHandler.from(server, container -> {
            container.setIdleTimeout(IDLE_TIMEOUT);
            container.setMaxTextMessageSize(MAX_MESSAGE_BYTES);
            container.setMaxBinaryMessageSize(MAX_MESSAGE_BYTES);
            container.addMapping(PATH, (request, response, callback) ->
                    upgrade(request, response, callback, live, sessions, security, addresses, json, scheduler));
        });
    }

    private static Object upgrade(ServerUpgradeRequest request, ServerUpgradeResponse response,
                                  org.eclipse.jetty.util.Callback callback, LiveEventService live, SessionResolver sessions,
                                  ApiSecurity security, ClientAddressResolver addresses, JsonCodec json,
                                  ScheduledExecutorService scheduler) {
        if (!RequestTrust.originAllowed(request, security.allowedOrigins())) {
            response.setStatus(403);
            callback.succeeded();
            return null;
        }
        RequestTrust.PresentedToken token = RequestTrust.presentedToken(request);
        if (token.token() == null) {
            response.setStatus(401);
            callback.succeeded();
            return null;
        }
        String remote = org.eclipse.jetty.server.Request.getRemoteAddr(request);
        ClientInfo client = new ClientInfo(
                addresses.clientAddress(remote, request.getHeaders().getValuesList(HttpHeader.X_FORWARDED_FOR)),
                request.getHeaders().get(HttpHeader.USER_AGENT));
        return new Endpoint(token.token(), client, live, sessions, json, scheduler);
    }

    /** Client-to-server message. */
    record ClientMessage(String type, String networkId, String locale) {
    }

    /** Public because Jetty binds listener methods reflectively. */
    public static final class Endpoint implements Session.Listener.AutoDemanding, LiveEventService.Sink {
        private final String token;
        private final ClientInfo client;
        private final LiveEventService live;
        private final SessionResolver sessions;
        private final JsonCodec json;
        private final ScheduledExecutorService scheduler;
        private final Deque<String> outgoing = new ArrayDeque<>();
        private final Deque<ClientMessage> early = new ArrayDeque<>();
        private boolean sending;
        private volatile Session socket;
        private volatile LiveEventService.Connection connection;
        private volatile ScheduledFuture<?> reauthentication;
        private volatile boolean closed;

        Endpoint(String token, ClientInfo client, LiveEventService live, SessionResolver sessions, JsonCodec json,
                 ScheduledExecutorService scheduler) {
            this.token = token;
            this.client = client;
            this.live = live;
            this.sessions = sessions;
            this.json = json;
            this.scheduler = scheduler;
        }

        @Override
        public void onWebSocketOpen(Session session) {
            this.socket = session;
            sessions.resolve(token, client).whenComplete((resolved, error) -> {
                if (error != null || resolved.isEmpty()) {
                    close(POLICY_VIOLATION, "unauthenticated");
                    return;
                }
                LiveEventService.Connection opened = live.connect(resolved.get(), this);
                synchronized (this) {
                    if (closed) {
                        opened.close();
                        return;
                    }
                    connection = opened;
                }
                try {
                    reauthentication = scheduler.scheduleWithFixedDelay(this::reauthenticate,
                            REAUTHENTICATE_EVERY.toSeconds(), REAUTHENTICATE_EVERY.toSeconds(), TimeUnit.SECONDS);
                } catch (RuntimeException e) {
                    close(StatusCode.SERVER_ERROR, "shutting down");
                    return;
                }
                ClientMessage pending;
                while ((pending = pollEarly()) != null) {
                    handle(pending);
                }
            });
        }

        private synchronized ClientMessage pollEarly() {
            return early.pollFirst();
        }

        private void reauthenticate() {
            sessions.resolve(token, client).whenComplete((resolved, error) -> {
                if (error == null && resolved.isEmpty()) {
                    close(POLICY_VIOLATION, "revoked");
                } else if (resolved != null && resolved.isPresent() && connection != null) {
                    connection.refresh(resolved.get());
                }
            });
        }

        @Override
        public void onWebSocketText(String message) {
            ClientMessage parsed;
            try {
                parsed = json.fromBytes(message.getBytes(StandardCharsets.UTF_8), ClientMessage.class);
            } catch (Exception e) {
                sendError(null, new MeccException(ErrorCode.BAD_REQUEST, "Messages must be JSON objects"));
                return;
            }
            if (parsed == null || parsed.type() == null) {
                sendError(null, new MeccException(ErrorCode.BAD_REQUEST, "Messages need a type"));
                return;
            }
            synchronized (this) {
                if (connection == null) {
                    if (early.size() < 16) {
                        early.addLast(parsed);
                    }
                    return;
                }
            }
            handle(parsed);
        }

        private void handle(ClientMessage message) {
            LiveEventService.Connection current = connection;
            switch (message.type()) {
                case "ping" -> send(new LiveEvent(LiveEvent.PONG, Instant.now(), null, Map.of()));
                case "subscribe", "unsubscribe" -> {
                    UUID networkId;
                    try {
                        networkId = UUID.fromString(String.valueOf(message.networkId()));
                    } catch (IllegalArgumentException e) {
                        sendError(null, new MeccException(ErrorCode.NETWORK_NOT_FOUND, "Network not found"));
                        return;
                    }
                    if (message.type().equals("unsubscribe")) {
                        current.unsubscribe(networkId);
                    } else {
                        current.subscribe(networkId, message.locale()).whenComplete((ignored, error) -> {
                            if (error != null) {
                                sendError(networkId, error);
                            }
                        });
                    }
                }
                default -> sendError(null, new MeccException(ErrorCode.BAD_REQUEST, "Unknown message type"));
            }
        }

        private void sendError(UUID networkId, Throwable error) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            ErrorResponse body = cause instanceof MeccException mecc
                    ? ErrorResponse.of(mecc.code(), mecc.getMessage(), mecc.details())
                    : ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal server error");
            if (!(cause instanceof MeccException)) {
                LOGGER.warn("Live update request failed", cause);
            }
            send(new LiveEvent(LiveEvent.ERROR, Instant.now(), networkId, body));
        }

        @Override
        public void send(LiveEvent event) {
            String text;
            try {
                text = new String(json.toBytes(event), StandardCharsets.UTF_8);
            } catch (Exception e) {
                LOGGER.warn("Could not serialize live event {}", event.type(), e);
                return;
            }
            synchronized (this) {
                if (closed) {
                    return;
                }
                if (outgoing.size() >= MAX_QUEUED_MESSAGES) {
                    outgoing.clear();
                    scheduler.execute(() -> close(StatusCode.SERVER_ERROR, "too slow"));
                    return;
                }
                outgoing.addLast(text);
                if (sending) {
                    return;
                }
                sending = true;
            }
            flush();
        }

        /** Jetty allows one outstanding send per session, so messages go out one after another. */
        private void flush() {
            String next;
            synchronized (this) {
                next = outgoing.pollFirst();
                if (next == null || closed || socket == null) {
                    sending = false;
                    return;
                }
            }
            socket.sendText(next, Callback.from(this::flush, error -> close(StatusCode.SERVER_ERROR, "send failed")));
        }

        private void close(int code, String reason) {
            Session current = socket;
            shutdown();
            if (current != null && current.isOpen()) {
                current.close(code, reason, Callback.NOOP);
            }
        }

        private void shutdown() {
            LiveEventService.Connection current;
            synchronized (this) {
                closed = true;
                outgoing.clear();
                current = connection;
                connection = null;
            }
            ScheduledFuture<?> task = reauthentication;
            if (task != null) {
                task.cancel(false);
            }
            if (current != null) {
                current.close();
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            shutdown();
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            LOGGER.debug("Live connection error: {}", cause.toString());
            shutdown();
        }
    }
}
