package com.github.havardpede.partylink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;

public class LinkPollerTest {
	private FakeExecutor fakeExecutor;
	private String token;
	private FakeHttpClient fakeHttpClient;
	private LinkPoller poller;

	@Before
	public void setUp() {
		fakeExecutor = new FakeExecutor();
		token = "test-token";
		fakeHttpClient = new FakeHttpClient();
		FakeConfig fakeConfig = new FakeConfig("http://localhost:3000", true);
		LinkApiClient apiClient =
				new LinkApiClient(fakeHttpClient, "http://localhost:3000", () -> token);
		CommandExecutor stubCommandExecutor =
				new CommandExecutor(passphrase -> {}, message -> {}, apiClient, () -> null);
		RsnDetector stubRsnDetector = new RsnDetector(apiClient, () -> null, Runnable::run);
		poller =
				new LinkPoller(
						apiClient, fakeExecutor, fakeConfig, stubCommandExecutor, stubRsnDetector);
	}

	@Test
	public void startSchedulesPollAtBaseInterval() {
		poller.start();

		assertEquals(1, fakeExecutor.scheduledDelays.size());
		assertEquals(LinkPoller.BASE_INTERVAL_SECONDS, (long) fakeExecutor.scheduledDelays.get(0));
	}

	@Test
	public void successfulPollResetsIntervalToBase() {
		fakeHttpClient.responseCode = 200;
		fakeHttpClient.responseBody = "{\"commands\":[]}";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(LinkPoller.BASE_INTERVAL_SECONDS, poller.getCurrentIntervalSeconds());
	}

	@Test
	public void failedPollDoublesInterval() {
		fakeHttpClient.shouldFail = true;

		poller.start();
		fakeExecutor.runNext();
		assertEquals(10, poller.getCurrentIntervalSeconds());

		fakeExecutor.runNext();
		assertEquals(20, poller.getCurrentIntervalSeconds());

		fakeExecutor.runNext();
		assertEquals(40, poller.getCurrentIntervalSeconds());
	}

	@Test
	public void backoffCapsAtMaxInterval() {
		fakeHttpClient.shouldFail = true;

		poller.start();
		for (int i = 0; i < 6; i++) {
			fakeExecutor.runNext();
		}

		assertEquals(LinkPoller.MAX_INTERVAL_SECONDS, poller.getCurrentIntervalSeconds());
	}

	@Test
	public void stopCancelsPendingPollTask() {
		poller.start();
		FakeScheduledFuture lastTask = fakeExecutor.lastFuture;

		poller.stop();

		assertTrue(lastTask.cancelled);
	}

	@Test
	public void startIsIdempotent() {
		poller.start();
		poller.start();

		assertEquals(1, fakeExecutor.scheduledDelays.size());
	}

	@Test
	public void pollSkipsWhenBearerTokenIsEmpty() {
		token = "";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void pollSendsCorrectAuthorizationHeader() {
		token = "my-secret-token";
		fakeHttpClient.responseCode = 200;
		fakeHttpClient.responseBody = "{\"commands\":[]}";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(1, fakeHttpClient.capturedRequests.size());
		assertEquals(
				"Bearer my-secret-token",
				fakeHttpClient.capturedRequests.get(0).header("Authorization"));
	}

	@Test
	public void http401TriggersBackoff() {
		fakeHttpClient.responseCode = 401;

		poller.start();
		fakeExecutor.runNext();

		assertEquals(10, poller.getCurrentIntervalSeconds());
	}

	// --- Test doubles ---

	static class FakeScheduledFuture implements ScheduledFuture<Object> {
		boolean cancelled = false;
		boolean done = false;

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
			return done;
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

	static class FakeExecutor implements ScheduledExecutorService {
		List<Long> scheduledDelays = new ArrayList<>();
		List<Runnable> scheduledTasks = new ArrayList<>();
		FakeScheduledFuture lastFuture;

		void runNext() {
			if (!scheduledTasks.isEmpty()) {
				scheduledTasks.remove(0).run();
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
			scheduledDelays.add(unit.toSeconds(delay));
			scheduledTasks.add(command);
			lastFuture = new FakeScheduledFuture();
			return (ScheduledFuture) lastFuture;
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
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> List<Future<T>> invokeAll(
				Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(
				Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void execute(Runnable command) {
			command.run();
		}
	}
}
