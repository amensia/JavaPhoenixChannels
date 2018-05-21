package org.phoenixframework.channels;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;


public class Socket {

    private static final Logger log = LoggerFactory.getLogger(Socket.class);

    public static final int RECONNECT_INTERVAL_MS = 5000;

    private static final int DEFAULT_HEARTBEAT_INTERVAL = 7000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final OkHttpClient httpClient = new OkHttpClient();
    private Map<String, String> headers;
    private WebSocket webSocket = null;

    private String endpointUri = null;
    private final List<Channel> channels = new ArrayList<>();
    private int heartbeatInterval;
    private boolean reconnectOnFailure = true;

    private Timer timer = null;
    private TimerTask reconnectTimerTask = null;
    private TimerTask heartbeatTimerTask = null;

    private Set<ISocketOpenCallback> socketOpenCallbacks = Collections.newSetFromMap(new HashMap<ISocketOpenCallback, Boolean>());
    private Set<ISocketCloseCallback> socketCloseCallbacks = Collections.newSetFromMap(new HashMap<ISocketCloseCallback, Boolean>());
    private Set<IErrorCallback> errorCallbacks = Collections.newSetFromMap(new HashMap<IErrorCallback, Boolean>());
    private Set<IMessageCallback> messageCallbacks = Collections.newSetFromMap(new HashMap<IMessageCallback, Boolean>());

    private int refNo = 1;

    /**
     * Annotated WS Endpoint. Private member to prevent confusion with "onConn*" registration methods.
     */
    private final PhoenixWSListener wsListener = new PhoenixWSListener();
    private final LinkedBlockingQueue<RequestBody> sendBuffer = new LinkedBlockingQueue<>();

    public class PhoenixWSListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            log.trace("WebSocket onOpen: {0}", webSocket);
            Socket.this.webSocket = webSocket;
            cancelReconnectTimer();

            startHeartbeatTimer();

            for (final ISocketOpenCallback callback : socketOpenCallbacks) {
                callback.onOpen();
            }

            flushSendBuffer();
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                final Envelope envelope = objectMapper.readValue(text, Envelope.class);
                synchronized (channels) {
                    for (final Channel channel : channels) {

                        if (channel.isMember(envelope.getTopic())) {
                            channel.trigger(envelope.getEvent(), envelope);
                        }
                    }
                }

                for (final IMessageCallback callback : messageCallbacks) {
                    callback.onMessage(envelope);
                }
            } catch (IOException e) {
                log.trace("Failed to read message payload", e);
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            onMessage(webSocket, bytes.toString());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            Socket.this.webSocket = null;
            for (final ISocketCloseCallback callback : socketCloseCallbacks) {
                callback.onClose();
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            try {
                //TODO if there are multiple errorCallbacks do we really want to trigger
                //the same channel error callbacks multiple times?
                triggerChannelError();
                for (final IErrorCallback callback : errorCallbacks) {
                    callback.onError(t.getMessage());
                }
            } finally {
                // Assume closed on failure
                if (Socket.this.webSocket != null) {
                    try {
                        Socket.this.webSocket.close(1001 /*CLOSE_GOING_AWAY*/, "EOF received");
                    } finally {
                        Socket.this.webSocket = null;
                    }
                }
            }
        }
    }

    private List<Channel> getChannels() {
        synchronized (channels) {
            return new ArrayList<>(channels);
        }
    }

    private void startHeartbeatTimer() {
        Socket.this.heartbeatTimerTask = new TimerTask() {
            @Override
            public void run() {
                log.trace("heartbeatTimerTask run");
                if (isConnected()) {
                    try {
                        Envelope envelope = new Envelope("phoenix", "heartbeat",
                                new ObjectNode(JsonNodeFactory.instance), makeRef(), null);
                        push(envelope);
                    } catch (Exception e) {
                        log.trace("Failed to send heartbeat", e);
                    }
                }
            }
        };

        timer.schedule(Socket.this.heartbeatTimerTask, Socket.this.heartbeatInterval, Socket.this.heartbeatInterval);
    }

    private void cancelHeartbeatTimer() {
        if (Socket.this.heartbeatTimerTask != null) {
            Socket.this.heartbeatTimerTask.cancel();
        }
    }

    /**
     * Sets up and schedules a timer task to make repeated reconnect attempts at configured intervals
     */
    private void scheduleReconnectTimer() {
        cancelReconnectTimer();
        cancelHeartbeatTimer();

        Socket.this.reconnectTimerTask = new TimerTask() {
            @Override
            public void run() {
                log.trace("reconnectTimerTask run");
                try {
                    connect();
                } catch (Exception e) {
                    log.trace("Failed to reconnect to " + Socket.this.wsListener, e);
                }
            }
        };
        timer.schedule(Socket.this.reconnectTimerTask, RECONNECT_INTERVAL_MS);
    }

    private void cancelReconnectTimer() {
        if (this.reconnectTimerTask != null) {
            this.reconnectTimerTask.cancel();
        }
    }

    public Socket(final String endpointUri, Map<String, String> headers) throws IOException {
        this(endpointUri);
        this.headers = headers;
    }

    public Socket(final String endpointUri) throws IOException {
        this(endpointUri, DEFAULT_HEARTBEAT_INTERVAL);
    }

    public Socket(final String endpointUri, final int heartbeatIntervalInMs, Map<String, String> headers) throws IOException {
        this(endpointUri, heartbeatIntervalInMs);
        this.headers = headers;
    }

    public Socket(final String endpointUri, final int heartbeatIntervalInMs) throws IOException {
        log.trace("PhoenixSocket({0})", endpointUri);
        this.endpointUri = endpointUri;
        this.heartbeatInterval = heartbeatIntervalInMs;
        this.timer = new Timer("Reconnect Timer for " + endpointUri);
    }

    public void disconnect() throws IOException {
        log.trace("disconnect");
        if (webSocket != null) {
            webSocket.close(1001 /*CLOSE_GOING_AWAY*/, "Disconnected by client");
        }
        cancelHeartbeatTimer();
        cancelReconnectTimer();
    }

    public void connect() throws IOException {
        log.trace("connect");
        disconnect();
        // No support for ws:// or ws:// in okhttp. See https://github.com/square/okhttp/issues/1652
        final String httpUrl = endpointUri.replaceFirst("^ws:", "http:").replaceFirst("^wss:", "https:");
        Request.Builder builder = new Request.Builder();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        }
        final Request request = builder.url(httpUrl).build();
        webSocket = httpClient.newWebSocket(request, wsListener);
    }

    /**
     * @return true if the socket connection is connected
     */
    public boolean isConnected() {
        return webSocket != null;
    }


    /**
     * Retrieve a channel instance for the specified topic
     *
     * @param topic   The channel topic
     * @param payload The message payload
     * @return A Channel instance to be used for sending and receiving events for the topic
     */
    public Channel chan(final String topic, final JsonNode payload) {
        log.trace("chan: {0}, {1}", new Object[]{topic, payload});
        final Channel channel = new Channel(topic, payload, this);
        synchronized (channels) {
            channels.add(channel);
        }
        return channel;
    }

    /**
     * Removes the specified channel if it is known to the socket
     *
     * @param channel The channel to be removed
     */
    public void remove(final Channel channel) {
        synchronized (channels) {
            for (final Iterator chanIter = channels.iterator(); chanIter.hasNext(); ) {
                if (chanIter.next() == channel) {
                    chanIter.remove();
                    break;
                }
            }
        }
    }

    /**
     * Sends a message envelope on this socket
     *
     * @param envelope The message envelope
     * @return This socket instance
     * @throws IOException Thrown if the message cannot be sent
     */
    public Socket push(final Envelope envelope) throws IOException {
        log.trace("Pushing envelope: {0}", envelope);
        final ObjectNode node = objectMapper.createObjectNode();
        node.put("topic", envelope.getTopic());
        node.put("event", envelope.getEvent());
        node.put("ref", envelope.getRef());
        node.put("join_ref", envelope.getJoinRef());
        node.set("payload", envelope.getPayload() == null ? objectMapper.createObjectNode() : envelope.getPayload());
        final String json = objectMapper.writeValueAsString(node);
        log.trace("Sending JSON: {0}", json);

        RequestBody body = RequestBody.create(MediaType.parse("text/xml"), json);

        if (this.isConnected()) {
            try {
                webSocket.send(json);
            } catch (IllegalStateException e) {
                log.trace("Attempted to send push when socket is not open", e);
            }
        } else {
            sendBuffer.add(body);
        }

        return this;
    }

    /**
     * Register a callback for SocketEvent.OPEN events
     *
     * @param callback The callback to receive OPEN events
     * @return This Socket instance
     */
    public Socket onOpen(final ISocketOpenCallback callback) {
        cancelReconnectTimer();
        this.socketOpenCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive CLOSE events
     * @return This Socket instance
     */
    public Socket onClose(final ISocketCloseCallback callback) {
        this.socketCloseCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.ERROR events
     *
     * @param callback The callback to receive ERROR events
     * @return This Socket instance
     */
    public Socket onError(final IErrorCallback callback) {
        Socket.this.errorCallbacks.add(callback);
        return this;
    }

    /**
     * Register a callback for SocketEvent.MESSAGE events
     *
     * @param callback The callback to receive MESSAGE events
     * @return This Socket instance
     */
    public Socket onMessage(final IMessageCallback callback) {
        this.messageCallbacks.add(callback);
        return this;
    }

    @Override
    public String toString() {
        return "PhoenixSocket{" +
                "endpointUri='" + endpointUri + '\'' +
                ", channels=" + channels +
                ", refNo=" + refNo +
                ", webSocket=" + webSocket +
                '}';
    }

    /**
     * Should the socket attempt to reconnect if websocket.onFailure is called.
     *
     * @param reconnectOnFailure reconnect value
     */
    public void reconectOnFailure(final boolean reconnectOnFailure) {
        this.reconnectOnFailure = reconnectOnFailure;
    }

    synchronized String makeRef() {
        refNo = (refNo + 1) % Integer.MAX_VALUE;
        return Integer.toString(refNo);
    }

    private void triggerChannelError() {
        for (final Channel channel : getChannels()) {
            channel.trigger(ChannelEvent.ERROR.getPhxEvent(), null);
        }
    }

    public void removeAllChannels() {
        synchronized (channels) {
            channels.clear();
        }
    }

    private void flushSendBuffer() {
        while (this.isConnected() && !sendBuffer.isEmpty()) {
            final RequestBody body = sendBuffer.remove();
            this.webSocket.send(body.toString());
        }
    }

    static String replyEventName(final String ref) {
        return "chan_reply_" + ref;
    }
}