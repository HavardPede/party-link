package com.github.havardpede.partylink;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@Slf4j
class WebSocketManager extends WebSocketListener {
	static final int BASE_BACKOFF_SECONDS = 5;
	static final int MAX_BACKOFF_SECONDS = 60;

	private final OkHttpClient httpClient;
	private final String wsUrl;
	private final Supplier<String> tokenSupplier;
	private final Supplier<String> pairingCodeSupplier;
	private final Consumer<String> onPairResult;
	private final CommandExecutor commandExecutor;
	private final ScheduledExecutorService executor;

	private volatile WebSocket webSocket;
	private volatile boolean authenticated;
	private ScheduledFuture<?> reconnectTask;
	private int backoffSeconds = BASE_BACKOFF_SECONDS;
	private boolean shouldReconnect = true;
	private String pendingRsn;

	WebSocketManager(
			OkHttpClient httpClient,
			String wsUrl,
			Supplier<String> tokenSupplier,
			Supplier<String> pairingCodeSupplier,
			Consumer<String> onPairResult,
			CommandExecutor commandExecutor,
			ScheduledExecutorService executor) {
		this.httpClient = httpClient;
		this.wsUrl = validateWsUrl(wsUrl);
		this.tokenSupplier = tokenSupplier;
		this.pairingCodeSupplier = pairingCodeSupplier;
		this.onPairResult = onPairResult;
		this.commandExecutor = commandExecutor;
		this.executor = executor;
	}

	void connect() {
		shouldReconnect = true;
		cancelReconnect();
		closeExisting();
		Request request = new Request.Builder().url(wsUrl).build();
		webSocket = httpClient.newWebSocket(request, this);
		log.info("WebSocket connecting to {}", wsUrl);
	}

	void disconnect() {
		shouldReconnect = false;
		cancelReconnect();
		closeExisting();
		authenticated = false;
		pendingRsn = null;
		log.info("WebSocket disconnected");
	}

	void sendIdentify(String rsn) {
		if (authenticated && webSocket != null) {
			doSendIdentify(rsn);
		} else {
			pendingRsn = rsn;
		}
	}

	void sendPartyState(String state, String passphrase) {
		if (!authenticated || webSocket == null) {
			return;
		}
		JsonObject msg = new JsonObject();
		msg.addProperty("type", "PARTY_STATE");
		msg.addProperty("state", state);
		if (passphrase != null) {
			msg.addProperty("passphrase", passphrase);
		}
		send(msg);
	}

	int getBackoffSeconds() {
		return backoffSeconds;
	}

	@Override
	public void onOpen(WebSocket ws, Response response) {
		log.info("WebSocket opened");
		webSocket = ws;
		String pairingCode = pairingCodeSupplier.get();
		if (pairingCode != null && !pairingCode.isEmpty()) {
			JsonObject pair = new JsonObject();
			pair.addProperty("type", "PAIR");
			pair.addProperty("code", pairingCode);
			send(pair);
		} else {
			JsonObject auth = new JsonObject();
			auth.addProperty("type", "AUTH");
			auth.addProperty("token", tokenSupplier.get());
			send(auth);
		}
	}

	@Override
	public void onMessage(WebSocket ws, String text) {
		JsonObject msg = new JsonParser().parse(text).getAsJsonObject();
		String type = msg.has("type") ? msg.get("type").getAsString() : "";

		switch (type) {
			case "AUTH_OK":
				handleAuthOk();
				break;
			case "AUTH_ERROR":
				handleAuthError(msg);
				break;
			case "PAIR_OK":
				handlePairOk(msg);
				break;
			case "PAIR_ERROR":
				handlePairError(msg);
				break;
			case "COMMAND":
				handleCommand(text);
				break;
			case "ERROR":
				handleError(msg);
				break;
			default:
				log.warn("Unknown WebSocket message type: {}", type);
				break;
		}
	}

	@Override
	public void onFailure(WebSocket ws, Throwable t, Response response) {
		log.warn("WebSocket failure: {}", t.getMessage());
		if (ws != webSocket) {
			return;
		}
		authenticated = false;
		scheduleReconnect();
	}

	@Override
	public void onClosed(WebSocket ws, int code, String reason) {
		log.info("WebSocket closed: {} {}", code, reason);
		if (ws != webSocket) {
			return;
		}
		authenticated = false;
		scheduleReconnect();
	}

	private void handleAuthOk() {
		log.info("WebSocket authenticated");
		authenticated = true;
		backoffSeconds = BASE_BACKOFF_SECONDS;
		if (pendingRsn != null) {
			doSendIdentify(pendingRsn);
			pendingRsn = null;
		}
	}

	private void handleAuthError(JsonObject msg) {
		String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
		log.error("WebSocket auth failed: {}", reason);
		shouldReconnect = false;
		closeExisting();
	}

	private void handlePairOk(JsonObject msg) {
		String token = msg.has("token") ? msg.get("token").getAsString() : null;
		log.info("WebSocket pairing succeeded");
		onPairResult.accept(token);
		handleAuthOk();
	}

	private void handlePairError(JsonObject msg) {
		String reason = msg.has("reason") ? msg.get("reason").getAsString() : "unknown";
		log.error("WebSocket pairing failed: {}", reason);
		onPairResult.accept(null);
		shouldReconnect = false;
		closeExisting();
	}

	private void handleCommand(String json) {
		Command command = Command.fromJson(json);
		commandExecutor.execute(command);
		sendAck(command.id);
	}

	private void handleError(JsonObject msg) {
		String message = msg.has("message") ? msg.get("message").getAsString() : "unknown";
		log.warn("Server error: {}", message);
	}

	private void sendAck(String commandId) {
		JsonObject ack = new JsonObject();
		ack.addProperty("type", "ACK");
		ack.addProperty("commandId", commandId);
		send(ack);
	}

	private void doSendIdentify(String rsn) {
		JsonObject identify = new JsonObject();
		identify.addProperty("type", "IDENTIFY");
		identify.addProperty("rsn", rsn);
		send(identify);
	}

	private void send(JsonObject message) {
		WebSocket ws = webSocket;
		if (ws != null) {
			ws.send(message.toString());
		}
	}

	private void scheduleReconnect() {
		if (!shouldReconnect) {
			return;
		}
		log.info("Reconnecting in {}s", backoffSeconds);
		reconnectTask = executor.schedule(this::connect, backoffSeconds, TimeUnit.SECONDS);
		backoffSeconds = Math.min(backoffSeconds * 2, MAX_BACKOFF_SECONDS);
	}

	private void cancelReconnect() {
		if (reconnectTask != null) {
			reconnectTask.cancel(false);
			reconnectTask = null;
		}
	}

	private void closeExisting() {
		WebSocket ws = webSocket;
		if (ws != null) {
			ws.close(1000, "Client disconnect");
			webSocket = null;
		}
	}

	private static String validateWsUrl(String url) {
		if (url == null || url.isEmpty()) {
			throw new IllegalArgumentException("WebSocket URL must not be empty");
		}
		if (!url.startsWith("wss://") && !url.startsWith("ws://")) {
			throw new IllegalArgumentException("WebSocket URL must use ws or wss: " + url);
		}
		return url;
	}
}
