package com.example.link;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import static org.junit.Assert.assertNull;

public class RsnDetectorTest
{
	private static final String FAKE_SERVER_URL = "http://localhost:3000";
	private static final String BEARER_TOKEN = "test-token";

	private CapturingHttpClient fakeHttpClient;
	private FakeConfig fakeConfig;
	private List<String> suppliedNames;
	private int supplierCallCount;

	@Before
	public void setUp()
	{
		fakeHttpClient = new CapturingHttpClient();
		fakeConfig = new FakeConfig(BEARER_TOKEN, FAKE_SERVER_URL);
		suppliedNames = new ArrayList<>();
		supplierCallCount = 0;
	}

	private RsnDetector buildDetector(String... names)
	{
		suppliedNames = new ArrayList<>();
		for (String name : names)
		{
			suppliedNames.add(name);
		}
		supplierCallCount = 0;

		return new RsnDetector(fakeHttpClient, fakeConfig, () ->
		{
			if (supplierCallCount < suppliedNames.size())
			{
				return suppliedNames.get(supplierCallCount++);
			}
			return null;
		});
	}

	@Test
	public void nameAvailableAtTick1PostsRsnOnce()
	{
		RsnDetector detector = buildDetector("Zezima");

		detector.onLoggedIn();
		detector.onGameTick(); // tick 1 — check fires, name found

		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
		assertEquals(FAKE_SERVER_URL + RsnDetector.RSN_PATH, fakeHttpClient.capturedRequests.get(0).url().toString());
	}

	@Test
	public void nameNullAtTick1And2ThenFoundAtTick4PostsOnce()
	{
		RsnDetector detector = buildDetector(null, null, "Zezima");

		detector.onLoggedIn();
		detector.onGameTick(); // tick 1 — check fires, null
		detector.onGameTick(); // tick 2 — check fires, null
		detector.onGameTick(); // tick 3 — no check (backoff gap)
		detector.onGameTick(); // tick 4 — check fires, "Zezima"

		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void nameNullAtAllCheckPointsNeverPostsRsn()
	{
		RsnDetector detector = buildDetector(null, null, null, null);

		detector.onLoggedIn();
		detector.onGameTick(); // tick 1
		detector.onGameTick(); // tick 2
		detector.onGameTick(); // tick 3 (gap)
		detector.onGameTick(); // tick 4
		detector.onGameTick(); // tick 5 (gap)
		detector.onGameTick(); // tick 6 (gap)
		detector.onGameTick(); // tick 7 (gap)
		detector.onGameTick(); // tick 8

		assertNull(detector.getDetectedName());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void gameTickIsNoOpWhenNoPendingLogin()
	{
		RsnDetector detector = buildDetector("Zezima");

		// No onLoggedIn called — ticks are no-ops
		detector.onGameTick();
		detector.onGameTick();
		detector.onGameTick();

		assertNull(detector.getDetectedName());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void postRsnSendsCorrectUrlAndAuthHeader()
	{
		RsnDetector detector = buildDetector("Zezima");

		detector.onLoggedIn();
		detector.onGameTick(); // tick 1 — posts

		assertEquals(1, fakeHttpClient.capturedRequests.size());
		Request request = fakeHttpClient.capturedRequests.get(0);
		assertEquals(FAKE_SERVER_URL + RsnDetector.RSN_PATH, request.url().toString());
		assertEquals("Bearer " + BEARER_TOKEN, request.header("Authorization"));
		assertEquals("POST", request.method());
	}

	@Test
	public void secondOnLoggedInResetsTickCounterForRetry()
	{
		RsnDetector detector = buildDetector(null, null, null, null, "Retry");

		// First login attempt — all 4 checks return null, gives up
		detector.onLoggedIn();
		detector.onGameTick(); // tick 1
		detector.onGameTick(); // tick 2
		detector.onGameTick(); // tick 3 (gap)
		detector.onGameTick(); // tick 4
		detector.onGameTick(); // tick 5 (gap)
		detector.onGameTick(); // tick 6 (gap)
		detector.onGameTick(); // tick 7 (gap)
		detector.onGameTick(); // tick 8

		assertNull(detector.getDetectedName());

		// Second login — fresh attempt, name available at tick 1
		detector.onLoggedIn();
		detector.onGameTick(); // tick 1

		assertEquals("Retry", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void postRsnSkipsRequestWhenTokenIsEmpty()
	{
		fakeConfig = new FakeConfig("", FAKE_SERVER_URL);
		RsnDetector detector = buildDetector("Zezima");

		detector.onLoggedIn();
		detector.onGameTick(); // tick 1 — would post, but token is empty

		// Name is detected (getDetectedName set before postRsn call is aborted by token check)
		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	// --- Test doubles ---

	static class CapturingHttpClient extends OkHttpClient
	{
		List<Request> capturedRequests = new ArrayList<>();

		@Override
		public Call newCall(Request request)
		{
			capturedRequests.add(request);
			return new FakeOkCall(request);
		}
	}

	static class FakeOkCall implements Call
	{
		private final Request request;

		FakeOkCall(Request request)
		{
			this.request = request;
		}

		@Override
		public Response execute() throws IOException
		{
			return new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), "{\"success\":true}"))
				.build();
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
		public void cancel() {}

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
			return new FakeOkCall(request);
		}

		@Override
		public okio.Timeout timeout()
		{
			return okio.Timeout.NONE;
		}
	}

	static class FakeConfig implements LinkConfig
	{
		private final String token;
		private final String serverUrl;

		FakeConfig(String token, String serverUrl)
		{
			this.token = token;
			this.serverUrl = serverUrl;
		}

		@Override
		public String bearerToken()
		{
			return token;
		}

		@Override
		public String serverUrl()
		{
			return serverUrl;
		}

		@Override
		public boolean enabled()
		{
			return true;
		}
	}
}
