package com.example.link;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LinkPollerTest
{
	private FakeExecutor fakeExecutor;
	private FakeConfig fakeConfig;
	private FakeHttpClient fakeHttpClient;
	private LinkPoller poller;

	private CommandExecutor stubCommandExecutor;

	@Before
	public void setUp()
	{
		fakeExecutor = new FakeExecutor();
		fakeConfig = new FakeConfig("test-token", true);
		fakeHttpClient = new FakeHttpClient();
		stubCommandExecutor = new CommandExecutor(passphrase -> {}, new OkHttpClient(), fakeConfig, () -> null);
		RsnDetector stubRsnDetector = new RsnDetector(new OkHttpClient(), fakeConfig, () -> null);
		poller = new LinkPoller(fakeHttpClient, fakeExecutor, fakeConfig, stubCommandExecutor, stubRsnDetector);
	}

	@Test
	public void startSchedulesPollAtBaseInterval()
	{
		poller.start();

		assertEquals(1, fakeExecutor.scheduledDelays.size());
		assertEquals(LinkPoller.BASE_INTERVAL_SECONDS, (long) fakeExecutor.scheduledDelays.get(0));
	}

	@Test
	public void successfulPollResetsIntervalToBase()
	{
		fakeHttpClient.responseCode = 200;
		fakeHttpClient.responseBody = "{\"commands\":[]}";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(LinkPoller.BASE_INTERVAL_SECONDS, poller.getCurrentIntervalSeconds());
	}

	@Test
	public void failedPollDoublesInterval()
	{
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
	public void backoffCapsAtMaxInterval()
	{
		fakeHttpClient.shouldFail = true;

		poller.start();

		// Run through: 5 -> 10 -> 20 -> 40 -> 60 -> 60
		for (int i = 0; i < 6; i++)
		{
			fakeExecutor.runNext();
		}

		assertEquals(LinkPoller.MAX_INTERVAL_SECONDS, poller.getCurrentIntervalSeconds());
	}

	@Test
	public void stopCancelsPendingPollTask()
	{
		poller.start();
		FakeScheduledFuture lastTask = fakeExecutor.lastFuture;

		poller.stop();

		assertTrue(lastTask.cancelled);
	}

	@Test
	public void startIsIdempotent()
	{
		poller.start();
		poller.start();

		assertEquals(1, fakeExecutor.scheduledDelays.size());
	}

	@Test
	public void pollSkipsWhenBearerTokenIsEmpty()
	{
		fakeConfig.token = "";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(0, fakeHttpClient.requestCount);
	}

	@Test
	public void pollSendsCorrectAuthorizationHeader()
	{
		fakeConfig.token = "my-secret-token";
		fakeHttpClient.responseCode = 200;
		fakeHttpClient.responseBody = "{\"commands\":[]}";

		poller.start();
		fakeExecutor.runNext();

		assertEquals(1, fakeHttpClient.requestCount);
		assertEquals("Bearer my-secret-token", fakeHttpClient.lastAuthHeader);
	}

	@Test
	public void http401TriggersBackoff()
	{
		fakeHttpClient.responseCode = 401;

		poller.start();
		fakeExecutor.runNext();

		assertEquals(10, poller.getCurrentIntervalSeconds());
	}

	// --- Test doubles ---

	static class FakeConfig implements LinkConfig
	{
		String token;
		boolean isEnabled;

		FakeConfig(String token, boolean isEnabled)
		{
			this.token = token;
			this.isEnabled = isEnabled;
		}

		@Override
		public String bearerToken()
		{
			return token;
		}

		@Override
		public String serverUrl()
		{
			return "http://localhost:3000";
		}

		@Override
		public boolean enabled()
		{
			return isEnabled;
		}
	}

	static class FakeHttpClient extends OkHttpClient
	{
		int responseCode = 200;
		String responseBody = "{\"commands\":[]}";
		boolean shouldFail = false;
		int requestCount = 0;
		String lastAuthHeader;

		@Override
		public Call newCall(Request request)
		{
			requestCount++;
			lastAuthHeader = request.header("Authorization");

			return new FakeCall(request, this);
		}
	}

	static class FakeCall implements Call
	{
		private final Request request;
		private final FakeHttpClient client;

		FakeCall(Request request, FakeHttpClient client)
		{
			this.request = request;
			this.client = client;
		}

		@Override
		public Response execute() throws IOException
		{
			if (client.shouldFail)
			{
				throw new IOException("Connection refused");
			}

			Response.Builder builder = new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(client.responseCode)
				.message("OK");

			if (client.responseBody != null)
			{
				builder.body(ResponseBody.create(
					MediaType.parse("application/json"),
					client.responseBody
				));
			}

			return builder.build();
		}

		@Override
		public Request request()
		{
			return request;
		}

		@Override
		public void enqueue(okhttp3.Callback callback)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void cancel()
		{
		}

		@Override
		public boolean isExecuted()
		{
			return false;
		}

		@Override
		public boolean isCanceled()
		{
			return false;
		}

		@Override
		public Call clone()
		{
			return new FakeCall(request, client);
		}

		@Override
		public okio.Timeout timeout()
		{
			return okio.Timeout.NONE;
		}
	}

	static class FakeScheduledFuture implements ScheduledFuture<Object>
	{
		boolean cancelled = false;
		boolean done = false;

		@Override
		public boolean cancel(boolean mayInterruptIfRunning)
		{
			cancelled = true;
			return true;
		}

		@Override
		public boolean isCancelled()
		{
			return cancelled;
		}

		@Override
		public boolean isDone()
		{
			return done;
		}

		@Override
		public Object get()
		{
			return null;
		}

		@Override
		public Object get(long timeout, TimeUnit unit)
		{
			return null;
		}

		@Override
		public long getDelay(TimeUnit unit)
		{
			return 0;
		}

		@Override
		public int compareTo(java.util.concurrent.Delayed o)
		{
			return 0;
		}
	}

	static class FakeExecutor implements ScheduledExecutorService
	{
		List<Long> scheduledDelays = new ArrayList<>();
		List<Runnable> scheduledTasks = new ArrayList<>();
		FakeScheduledFuture lastFuture;

		void runNext()
		{
			if (!scheduledTasks.isEmpty())
			{
				Runnable task = scheduledTasks.remove(0);
				task.run();
			}
		}

		@Override
		@SuppressWarnings("unchecked")
		public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit)
		{
			scheduledDelays.add(unit.toSeconds(delay));
			scheduledTasks.add(command);
			lastFuture = new FakeScheduledFuture();
			return (ScheduledFuture) lastFuture;
		}

		@Override
		public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void shutdown()
		{
		}

		@Override
		public List<Runnable> shutdownNow()
		{
			return Collections.emptyList();
		}

		@Override
		public boolean isShutdown()
		{
			return false;
		}

		@Override
		public boolean isTerminated()
		{
			return false;
		}

		@Override
		public boolean awaitTermination(long timeout, TimeUnit unit)
		{
			return true;
		}

		@Override
		public <T> Future<T> submit(Callable<T> task)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> Future<T> submit(Runnable task, T result)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public Future<?> submit(Runnable task)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
		{
			throw new UnsupportedOperationException();
		}

		@Override
		public void execute(Runnable command)
		{
			command.run();
		}
	}
}
