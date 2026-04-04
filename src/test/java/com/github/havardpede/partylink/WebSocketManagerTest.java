package com.github.havardpede.partylink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import org.junit.Before;
import org.junit.Test;

public class WebSocketManagerTest {
	private RecordingWebSocket recordingWs;
	private List<String> changePartyCalls;
	private List<String> chatMessageCalls;
	private List<String> pairResults;
	private FakeScheduledExecutor fakeExecutor;
	private WebSocketManager manager;

	@Before
	public void setUp() {
		recordingWs = new RecordingWebSocket();
		changePartyCalls = new ArrayList<>();
		chatMessageCalls = new ArrayList<>();
		pairResults = new ArrayList<>();
		fakeExecutor = new FakeScheduledExecutor();

		manager = buildManager("", pairResults::add);
	}

	private WebSocketManager buildManager(String pairingCode, Consumer<String> onPairResult) {
		CommandExecutor commandExecutor =
				new CommandExecutor(changePartyCalls::add, chatMessageCalls::add);
		return new WebSocketManager(
				new OkHttpClient(),
				"ws://localhost:8080",
				() -> "test-token",
				() -> pairingCode,
				onPairResult,
				commandExecutor,
				fakeExecutor);
	}

	@Test
	public void onOpenSendsAuthMessage() {
		manager.onOpen(recordingWs, null);

		assertEquals(1, recordingWs.sentMessages.size());
		JsonObject auth = parse(recordingWs.sentMessages.get(0));
		assertEquals("AUTH", auth.get("type").getAsString());
		assertEquals("test-token", auth.get("token").getAsString());
	}

	@Test
	public void authOkSendsQueuedIdentify() {
		manager.sendIdentify("Zezima");
		assertEquals(0, recordingWs.sentMessages.size());

		simulateAuthFlow();

		assertEquals(2, recordingWs.sentMessages.size());
		JsonObject identify = parse(recordingWs.sentMessages.get(1));
		assertEquals("IDENTIFY", identify.get("type").getAsString());
		assertEquals("Zezima", identify.get("rsn").getAsString());
	}

	@Test
	public void identifyAfterAuthSendsImmediately() {
		simulateAuthFlow();

		manager.sendIdentify("Zezima");

		assertEquals(2, recordingWs.sentMessages.size());
		JsonObject identify = parse(recordingWs.sentMessages.get(1));
		assertEquals("IDENTIFY", identify.get("type").getAsString());
	}

	@Test
	public void commandMessageExecutesAndSendsAck() {
		simulateAuthFlow();

		String commandJson =
				"{\"type\":\"COMMAND\",\"id\":\"cmd-1\",\"command\":\"JOIN_PARTY\","
						+ "\"passphrase\":\"coral-lime-oak-river\",\"partyId\":\"p1\",\"reason\":null}";
		manager.onMessage(recordingWs, commandJson);

		assertEquals(1, changePartyCalls.size());
		assertEquals("coral-lime-oak-river", changePartyCalls.get(0));

		JsonObject ack = parse(recordingWs.lastSent());
		assertEquals("ACK", ack.get("type").getAsString());
		assertEquals("cmd-1", ack.get("commandId").getAsString());
	}

	@Test
	public void leavePartyCommandExecutes() {
		simulateAuthFlow();

		String commandJson =
				"{\"type\":\"COMMAND\",\"id\":\"cmd-2\",\"command\":\"LEAVE_PARTY\","
						+ "\"passphrase\":null,\"partyId\":\"p1\",\"reason\":\"KICKED\"}";
		manager.onMessage(recordingWs, commandJson);

		assertEquals(1, changePartyCalls.size());
		assertEquals(null, changePartyCalls.get(0));
		assertEquals("You have been kicked from the party.", chatMessageCalls.get(0));
	}

	@Test
	public void authErrorDoesNotReconnect() {
		manager.onOpen(recordingWs, null);
		manager.onMessage(recordingWs, "{\"type\":\"AUTH_ERROR\",\"reason\":\"Invalid token\"}");

		assertEquals(0, fakeExecutor.scheduledTasks.size());
	}

	@Test
	public void failureSchedulesReconnect() {
		manager.onFailure(recordingWs, new RuntimeException("connection lost"), null);

		assertEquals(1, fakeExecutor.scheduledTasks.size());
		assertEquals(
				WebSocketManager.BASE_BACKOFF_SECONDS, (long) fakeExecutor.scheduledDelays.get(0));
	}

	@Test
	public void backoffDoublesOnConsecutiveFailures() {
		manager.onFailure(recordingWs, new RuntimeException("fail 1"), null);
		assertEquals(1, fakeExecutor.scheduledDelays.size());
		assertEquals(5L, (long) fakeExecutor.scheduledDelays.get(0));

		manager.onFailure(recordingWs, new RuntimeException("fail 2"), null);
		assertEquals(2, fakeExecutor.scheduledDelays.size());
		assertEquals(10L, (long) fakeExecutor.scheduledDelays.get(1));
	}

	@Test
	public void backoffCapsAtMax() {
		for (int i = 0; i < 10; i++) {
			manager.onFailure(recordingWs, new RuntimeException("fail"), null);
		}

		long lastDelay = fakeExecutor.scheduledDelays.get(fakeExecutor.scheduledDelays.size() - 1);
		assertTrue(lastDelay <= WebSocketManager.MAX_BACKOFF_SECONDS);
	}

	@Test
	public void authOkResetsBackoff() {
		manager.onFailure(recordingWs, new RuntimeException("fail"), null);
		manager.onFailure(recordingWs, new RuntimeException("fail"), null);

		simulateAuthFlow();
		manager.onFailure(recordingWs, new RuntimeException("fail after auth"), null);

		long lastDelay = fakeExecutor.scheduledDelays.get(fakeExecutor.scheduledDelays.size() - 1);
		assertEquals(WebSocketManager.BASE_BACKOFF_SECONDS, lastDelay);
	}

	@Test
	public void sendPartyStateJoined() {
		simulateAuthFlow();

		manager.sendPartyState("JOINED", "coral-lime-oak-river");

		JsonObject msg = parse(recordingWs.lastSent());
		assertEquals("PARTY_STATE", msg.get("type").getAsString());
		assertEquals("JOINED", msg.get("state").getAsString());
		assertEquals("coral-lime-oak-river", msg.get("passphrase").getAsString());
	}

	@Test
	public void sendPartyStateLeft() {
		simulateAuthFlow();

		manager.sendPartyState("LEFT", null);

		JsonObject msg = parse(recordingWs.lastSent());
		assertEquals("PARTY_STATE", msg.get("type").getAsString());
		assertEquals("LEFT", msg.get("state").getAsString());
		assertFalse(msg.has("passphrase"));
	}

	@Test
	public void sendPartyStateIgnoredBeforeAuth() {
		manager.sendPartyState("JOINED", "pass");

		assertEquals(0, recordingWs.sentMessages.size());
	}

	@Test
	public void disconnectPreventsReconnect() {
		manager.disconnect();
		manager.onFailure(recordingWs, new RuntimeException("fail"), null);

		assertEquals(0, fakeExecutor.scheduledTasks.size());
	}

	@Test
	public void staleClosedCallbackDoesNotReconnect() {
		RecordingWebSocket oldWs = new RecordingWebSocket();
		manager.onOpen(oldWs, null);

		// Simulate connect() replacing the old WS — webSocket is now null or a new instance
		RecordingWebSocket newWs = new RecordingWebSocket();
		manager.onOpen(newWs, null);

		// Old WS close fires after replacement
		manager.onClosed(oldWs, 1000, "replaced");

		assertEquals(0, fakeExecutor.scheduledTasks.size());
	}

	@Test
	public void staleFailureCallbackDoesNotReconnect() {
		RecordingWebSocket oldWs = new RecordingWebSocket();
		manager.onOpen(oldWs, null);

		RecordingWebSocket newWs = new RecordingWebSocket();
		manager.onOpen(newWs, null);

		// Old WS failure fires after replacement
		manager.onFailure(oldWs, new RuntimeException("stale"), null);

		assertEquals(0, fakeExecutor.scheduledTasks.size());
	}

	@Test
	public void errorMessageIsLogged() {
		simulateAuthFlow();

		manager.onMessage(recordingWs, "{\"type\":\"ERROR\",\"message\":\"something broke\"}");

		// No exception thrown, no state change — just logged
		assertEquals(0, changePartyCalls.size());
	}

	@Test(expected = IllegalArgumentException.class)
	public void invalidWsUrlThrows() {
		new WebSocketManager(
				new OkHttpClient(),
				"http://invalid",
				() -> "token",
				() -> "",
				token -> {},
				new CommandExecutor(s -> {}, s -> {}),
				fakeExecutor);
	}

	@Test(expected = IllegalArgumentException.class)
	public void emptyWsUrlThrows() {
		new WebSocketManager(
				new OkHttpClient(),
				"",
				() -> "token",
				() -> "",
				token -> {},
				new CommandExecutor(s -> {}, s -> {}),
				fakeExecutor);
	}

	@Test
	public void onOpenSendsPairMessageWhenPairingCodeSet() {
		WebSocketManager pairingManager = buildManager("ABCD-1234", pairResults::add);

		pairingManager.onOpen(recordingWs, null);

		assertEquals(1, recordingWs.sentMessages.size());
		JsonObject msg = parse(recordingWs.sentMessages.get(0));
		assertEquals("PAIR", msg.get("type").getAsString());
		assertEquals("ABCD-1234", msg.get("code").getAsString());
	}

	@Test
	public void pairOkInvokesCallbackAndAuthenticates() {
		WebSocketManager pairingManager = buildManager("ABCD-1234", pairResults::add);

		pairingManager.onOpen(recordingWs, null);
		pairingManager.onMessage(recordingWs, "{\"type\":\"PAIR_OK\",\"token\":\"my-token\"}");

		assertEquals(1, pairResults.size());
		assertEquals("my-token", pairResults.get(0));
	}

	@Test
	public void pairOkSendsQueuedIdentify() {
		WebSocketManager pairingManager = buildManager("ABCD-1234", pairResults::add);
		pairingManager.sendIdentify("Zezima");

		pairingManager.onOpen(recordingWs, null);
		pairingManager.onMessage(recordingWs, "{\"type\":\"PAIR_OK\",\"token\":\"my-token\"}");

		assertEquals(2, recordingWs.sentMessages.size());
		JsonObject identify = parse(recordingWs.sentMessages.get(1));
		assertEquals("IDENTIFY", identify.get("type").getAsString());
		assertEquals("Zezima", identify.get("rsn").getAsString());
	}

	@Test
	public void pairErrorInvokesCallbackWithNull() {
		WebSocketManager pairingManager = buildManager("ABCD-1234", pairResults::add);

		pairingManager.onOpen(recordingWs, null);
		pairingManager.onMessage(
				recordingWs, "{\"type\":\"PAIR_ERROR\",\"reason\":\"Invalid code\"}");

		assertEquals(1, pairResults.size());
		assertEquals(null, pairResults.get(0));
	}

	@Test
	public void pairErrorDoesNotReconnect() {
		WebSocketManager pairingManager = buildManager("ABCD-1234", pairResults::add);

		pairingManager.onOpen(recordingWs, null);
		pairingManager.onMessage(
				recordingWs, "{\"type\":\"PAIR_ERROR\",\"reason\":\"Invalid code\"}");

		assertEquals(0, fakeExecutor.scheduledTasks.size());
	}

	private void simulateAuthFlow() {
		manager.onOpen(recordingWs, null);
		manager.onMessage(recordingWs, "{\"type\":\"AUTH_OK\"}");
	}

	private static JsonObject parse(String json) {
		return new JsonParser().parse(json).getAsJsonObject();
	}

	// --- Test doubles ---

	static class RecordingWebSocket implements WebSocket {
		List<String> sentMessages = new ArrayList<>();
		boolean closed = false;

		String lastSent() {
			return sentMessages.get(sentMessages.size() - 1);
		}

		@Override
		public boolean send(String text) {
			sentMessages.add(text);
			return true;
		}

		@Override
		public boolean send(okio.ByteString bytes) {
			return true;
		}

		@Override
		public boolean close(int code, String reason) {
			closed = true;
			return true;
		}

		@Override
		public void cancel() {}

		@Override
		public okhttp3.Request request() {
			return new okhttp3.Request.Builder().url("http://fake").build();
		}

		@Override
		public long queueSize() {
			return 0;
		}
	}

	static class FakeScheduledFuture implements ScheduledFuture<Object> {
		boolean cancelled = false;

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return false;
		}

		@Override
		public Object get() {
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit) {
			return null;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return 0;
		}

		@Override
		public int compareTo(java.util.concurrent.Delayed o) {
			return 0;
		}
	}

	static class FakeScheduledExecutor implements ScheduledExecutorService {
		List<Long> scheduledDelays = new ArrayList<>();
		List<Runnable> scheduledTasks = new ArrayList<>();

		@Override
		@SuppressWarnings("unchecked")
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			scheduledDelays.add(unit.toSeconds(delay));
			scheduledTasks.add(command);
			return (ScheduledFuture) new FakeScheduledFuture();
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(
				Runnable command, long initialDelay, long period, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(
				Runnable command, long initialDelay, long delay, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void shutdown() {}

		@Override
		public List<Runnable> shutdownNow() {
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown() {
			return false;
		}

		@Override
		public boolean isTerminated() {
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit) {
			return true;
		}

		@Override
		public <T> Future<T> submit(Callable<T> task) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> Future<T> submit(Runnable task, T result) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Future<?> submit(Runnable task) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> List<Future<T>> invokeAll(java.util.Collection<? extends Callable<T>> tasks) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> List<Future<T>> invokeAll(
				java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(java.util.Collection<? extends Callable<T>> tasks) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(
				java.util.Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void execute(Runnable command) {
			command.run();
		}
	}
}
