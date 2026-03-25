package com.example.link;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
import static org.junit.Assert.assertTrue;

public class CommandExecutorTest
{
	private static final String JOIN_JSON =
		"{\"commands\":[{\"id\":\"cmd-1\",\"type\":\"JOIN_PARTY\",\"passphrase\":\"test-pass\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String LEAVE_JSON =
		"{\"commands\":[{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String EMPTY_JSON = "{\"commands\":[]}";
	private static final String UNKNOWN_TYPE_JSON =
		"{\"commands\":[{\"id\":\"cmd-3\",\"type\":\"UNKNOWN_CMD\",\"passphrase\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String MULTI_JSON =
		"{\"commands\":[" +
			"{\"id\":\"cmd-1\",\"type\":\"JOIN_PARTY\",\"passphrase\":\"pass1\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}," +
			"{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}" +
		"]}";
	private static final String TOKEN = "test-bearer-token";

	private List<String> changePartyCalls;
	private boolean shouldThrowOnChangeParty;
	private AckCapturingHttpClient fakeHttpClient;
	private CommandExecutor executor;

	@Before
	public void setUp()
	{
		changePartyCalls = new ArrayList<>();
		shouldThrowOnChangeParty = false;
		fakeHttpClient = new AckCapturingHttpClient();

		Consumer<String> spyChangeParty = (passphrase) ->
		{
			if (shouldThrowOnChangeParty)
			{
				throw new RuntimeException("PartyService error");
			}
			changePartyCalls.add(passphrase);
		};

		executor = new CommandExecutor(spyChangeParty, fakeHttpClient);
	}

	@Test
	public void joinPartyCallsChangePartyWithPassphrase()
	{
		executor.executeCommands(JOIN_JSON, TOKEN);

		assertEquals(1, changePartyCalls.size());
		assertEquals("test-pass", changePartyCalls.get(0));
	}

	@Test
	public void leavePartyCallsChangePartyWithNull()
	{
		executor.executeCommands(LEAVE_JSON, TOKEN);

		assertEquals(1, changePartyCalls.size());
		assertNull(changePartyCalls.get(0));
	}

	@Test
	public void joinPartySendsAckWithBearerHeader()
	{
		executor.executeCommands(JOIN_JSON, TOKEN);

		assertEquals(1, fakeHttpClient.ackRequests.size());
		Request ackRequest = fakeHttpClient.ackRequests.get(0);
		assertEquals(CommandExecutor.ACK_URL_BASE + "cmd-1" + CommandExecutor.ACK_URL_SUFFIX, ackRequest.url().toString());
		assertEquals("Bearer " + TOKEN, ackRequest.header("Authorization"));
		assertEquals("POST", ackRequest.method());
	}

	@Test
	public void leavePartySendsAckToCorrectUrl()
	{
		executor.executeCommands(LEAVE_JSON, TOKEN);

		assertEquals(1, fakeHttpClient.ackRequests.size());
		Request ackRequest = fakeHttpClient.ackRequests.get(0);
		assertEquals(CommandExecutor.ACK_URL_BASE + "cmd-2" + CommandExecutor.ACK_URL_SUFFIX, ackRequest.url().toString());
	}

	@Test
	public void exceptionInChangePartyStillAcknowledges()
	{
		shouldThrowOnChangeParty = true;

		executor.executeCommands(JOIN_JSON, TOKEN);

		assertEquals(1, fakeHttpClient.ackRequests.size());
	}

	@Test
	public void unknownCommandTypeIsAcknowledged()
	{
		executor.executeCommands(UNKNOWN_TYPE_JSON, TOKEN);

		assertEquals(0, changePartyCalls.size());
		assertEquals(1, fakeHttpClient.ackRequests.size());
	}

	@Test
	public void emptyCommandsArrayMakesNoCalls()
	{
		executor.executeCommands(EMPTY_JSON, TOKEN);

		assertEquals(0, changePartyCalls.size());
		assertEquals(0, fakeHttpClient.ackRequests.size());
	}

	@Test
	public void parseCommandsDeserializesAllFields()
	{
		com.google.gson.JsonArray commands = executor.parseCommands(JOIN_JSON);

		assertEquals(1, commands.size());
		com.google.gson.JsonObject cmd = commands.get(0).getAsJsonObject();
		assertEquals("cmd-1", cmd.get("id").getAsString());
		assertEquals("JOIN_PARTY", cmd.get("type").getAsString());
		assertEquals("test-pass", cmd.get("passphrase").getAsString());
	}

	@Test
	public void multipleCommandsExecutedInOrder()
	{
		executor.executeCommands(MULTI_JSON, TOKEN);

		assertEquals(2, changePartyCalls.size());
		assertEquals("pass1", changePartyCalls.get(0));
		assertNull(changePartyCalls.get(1));
		assertEquals(2, fakeHttpClient.ackRequests.size());
	}

	@Test
	public void ackRequestsMatchCommandIds()
	{
		executor.executeCommands(MULTI_JSON, TOKEN);

		assertEquals(CommandExecutor.ACK_URL_BASE + "cmd-1" + CommandExecutor.ACK_URL_SUFFIX,
			fakeHttpClient.ackRequests.get(0).url().toString());
		assertEquals(CommandExecutor.ACK_URL_BASE + "cmd-2" + CommandExecutor.ACK_URL_SUFFIX,
			fakeHttpClient.ackRequests.get(1).url().toString());
	}

	// --- Test doubles ---

	static class AckCapturingHttpClient extends OkHttpClient
	{
		List<Request> ackRequests = new ArrayList<>();

		@Override
		public Call newCall(Request request)
		{
			ackRequests.add(request);
			return new AckFakeCall(request);
		}
	}

	static class AckFakeCall implements Call
	{
		private final Request request;

		AckFakeCall(Request request)
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
				.body(ResponseBody.create(
					MediaType.parse("application/json"),
					"{\"success\":true}"
				))
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
			return new AckFakeCall(request);
		}

		@Override
		public okio.Timeout timeout()
		{
			return okio.Timeout.NONE;
		}
	}
}
