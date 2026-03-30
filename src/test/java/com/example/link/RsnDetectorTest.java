package com.example.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;

public class RsnDetectorTest {
	private static final String FAKE_SERVER_URL = "http://localhost:3000";
	private static final String BEARER_TOKEN = "test-token";

	private FakeHttpClient fakeHttpClient;
	private String token;

	@Before
	public void setUp() {
		fakeHttpClient = new FakeHttpClient();
		token = BEARER_TOKEN;
	}

	private RsnDetector buildDetector(String nameOnLogin, String nameOnTick) {
		LinkApiClient apiClient = new LinkApiClient(fakeHttpClient, FAKE_SERVER_URL, () -> token);
		boolean[] loginCalled = {false};
		return new RsnDetector(
				apiClient,
				() -> {
					if (!loginCalled[0]) {
						loginCalled[0] = true;
						return nameOnLogin;
					}
					return nameOnTick;
				},
				Runnable::run);
	}

	@Test
	public void nameAvailableAtLoginPostsImmediately() {
		RsnDetector detector = buildDetector("Zezima", null);

		detector.onLoggedIn();

		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void nameNullAtLoginFoundOnNextTick() {
		RsnDetector detector = buildDetector(null, "Zezima");

		detector.onLoggedIn();
		detector.onGameTick();

		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void nameNullAtLoginAndTickGivesUp() {
		RsnDetector detector = buildDetector(null, null);

		detector.onLoggedIn();
		detector.onGameTick();

		assertNull(detector.getDetectedName());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void gameTickIsNoOpWhenNotPending() {
		RsnDetector detector = buildDetector("Zezima", null);

		detector.onLoggedIn();
		detector.onGameTick(); // should be no-op since already resolved
		detector.onGameTick();

		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void postRsnSendsCorrectUrlAndAuthHeader() {
		RsnDetector detector = buildDetector("Zezima", null);

		detector.onLoggedIn();

		Request request = fakeHttpClient.capturedRequests.get(0);
		assertEquals(FAKE_SERVER_URL + "/api/plugin/rsn", request.url().toString());
		assertEquals("Bearer " + BEARER_TOKEN, request.header("Authorization"));
		assertEquals("POST", request.method());
	}

	@Test
	public void secondLoginResetsDetection() {
		RsnDetector detector = buildDetector(null, null);

		detector.onLoggedIn();
		detector.onGameTick();
		assertNull(detector.getDetectedName());

		// Rebuild with a name available — simulates second login
		LinkApiClient apiClient = new LinkApiClient(fakeHttpClient, FAKE_SERVER_URL, () -> token);
		detector = new RsnDetector(apiClient, () -> "Retry", Runnable::run);
		detector.onLoggedIn();

		assertEquals("Retry", detector.getDetectedName());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void postRsnSkipsRequestWhenTokenIsEmpty() {
		token = "";
		RsnDetector detector = buildDetector("Zezima", null);

		detector.onLoggedIn();

		assertEquals("Zezima", detector.getDetectedName());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void reloginWithSameNameSkipsPost() {
		LinkApiClient apiClient = new LinkApiClient(fakeHttpClient, FAKE_SERVER_URL, () -> token);
		RsnDetector detector = new RsnDetector(apiClient, () -> "Zezima", Runnable::run);

		detector.onLoggedIn();
		assertEquals(1, fakeHttpClient.capturedRequests.size());

		detector.onLoggedIn();
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void reloginWithDifferentNamePostsAgain() {
		LinkApiClient apiClient = new LinkApiClient(fakeHttpClient, FAKE_SERVER_URL, () -> token);
		String[] name = {"Zezima"};
		RsnDetector detector = new RsnDetector(apiClient, () -> name[0], Runnable::run);

		detector.onLoggedIn();
		assertEquals(1, fakeHttpClient.capturedRequests.size());

		name[0] = "Lynx Titan";
		detector.onLoggedIn();
		assertEquals(2, fakeHttpClient.capturedRequests.size());
	}

	@Test(expected = IllegalArgumentException.class)
	public void invalidServerUrlThrows() {
		new LinkApiClient(fakeHttpClient, "ftp://evil.com", () -> token);
	}

	@Test(expected = IllegalArgumentException.class)
	public void emptyServerUrlThrows() {
		new LinkApiClient(fakeHttpClient, "", () -> token);
	}
}
