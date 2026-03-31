package com.github.havardpede.partylink;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import okhttp3.Request;
import org.junit.Before;
import org.junit.Test;

public class CommandExecutorTest {
	private static final String JOIN_JSON =
			"{\"commands\":[{\"id\":\"cmd-1\",\"type\":\"JOIN_PARTY\",\"passphrase\":\"test-pass\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String LEAVE_JSON =
			"{\"commands\":[{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"reason\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String LEAVE_KICKED_JSON =
			"{\"commands\":[{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"reason\":\"KICKED\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String LEAVE_CLOSED_JSON =
			"{\"commands\":[{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"reason\":\"CLOSED\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String LEAVE_LEFT_JSON =
			"{\"commands\":[{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"reason\":\"LEFT\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String EMPTY_JSON = "{\"commands\":[]}";
	private static final String UNKNOWN_TYPE_JSON =
			"{\"commands\":[{\"id\":\"cmd-3\",\"type\":\"UNKNOWN_CMD\",\"passphrase\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String PATH_TRAVERSAL_JSON =
			"{\"commands\":[{\"id\":\"../../admin/delete\",\"type\":\"JOIN_PARTY\",\"passphrase\":\"test-pass\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}]}";
	private static final String MULTI_JSON =
			"{\"commands\":["
					+ "{\"id\":\"cmd-1\",\"type\":\"JOIN_PARTY\",\"passphrase\":\"pass1\",\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"},"
					+ "{\"id\":\"cmd-2\",\"type\":\"LEAVE_PARTY\",\"passphrase\":null,\"partyId\":\"p1\",\"createdAt\":\"2026-01-01\"}"
					+ "]}";
	private static final String TOKEN = "test-bearer-token";
	private static final String FAKE_SERVER_URL = "http://localhost:3000";

	private List<String> changePartyCalls;
	private List<String> chatMessageCalls;
	private boolean shouldThrowOnChangeParty;
	private FakeHttpClient fakeHttpClient;
	private CommandExecutor executor;

	@Before
	public void setUp() {
		changePartyCalls = new ArrayList<>();
		chatMessageCalls = new ArrayList<>();
		shouldThrowOnChangeParty = false;
		fakeHttpClient = new FakeHttpClient();

		Consumer<String> spyChangeParty =
				passphrase -> {
					if (shouldThrowOnChangeParty) {
						throw new RuntimeException("PartyService error");
					}
					changePartyCalls.add(passphrase);
				};

		LinkApiClient apiClient = new LinkApiClient(fakeHttpClient, FAKE_SERVER_URL, () -> TOKEN);
		executor =
				new CommandExecutor(spyChangeParty, chatMessageCalls::add, apiClient, () -> null);
	}

	@Test
	public void joinPartyCallsChangePartyWithPassphrase() {
		executor.executeCommands(JOIN_JSON);

		assertEquals(1, changePartyCalls.size());
		assertEquals("test-pass", changePartyCalls.get(0));
	}

	@Test
	public void leavePartyCallsChangePartyWithNull() {
		executor.executeCommands(LEAVE_JSON);

		assertEquals(1, changePartyCalls.size());
		assertNull(changePartyCalls.get(0));
	}

	@Test
	public void joinPartySendsAckWithBearerHeader() {
		executor.executeCommands(JOIN_JSON);

		assertEquals(1, fakeHttpClient.capturedRequests.size());
		Request ackRequest = fakeHttpClient.capturedRequests.get(0);
		assertEquals(
				FAKE_SERVER_URL + "/api/plugin/commands/cmd-1/ack", ackRequest.url().toString());
		assertEquals("Bearer " + TOKEN, ackRequest.header("Authorization"));
		assertEquals("POST", ackRequest.method());
	}

	@Test
	public void leavePartySendsAckToCorrectUrl() {
		executor.executeCommands(LEAVE_JSON);

		assertEquals(1, fakeHttpClient.capturedRequests.size());
		Request ackRequest = fakeHttpClient.capturedRequests.get(0);
		assertEquals(
				FAKE_SERVER_URL + "/api/plugin/commands/cmd-2/ack", ackRequest.url().toString());
	}

	@Test
	public void exceptionInChangePartyStillAcknowledges() {
		shouldThrowOnChangeParty = true;

		executor.executeCommands(JOIN_JSON);

		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void unknownCommandTypeIsAcknowledged() {
		executor.executeCommands(UNKNOWN_TYPE_JSON);

		assertEquals(0, changePartyCalls.size());
		assertEquals(1, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void emptyCommandsArrayMakesNoCalls() {
		executor.executeCommands(EMPTY_JSON);

		assertEquals(0, changePartyCalls.size());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void multipleCommandsExecutedInOrder() {
		executor.executeCommands(MULTI_JSON);

		assertEquals(2, changePartyCalls.size());
		assertEquals("pass1", changePartyCalls.get(0));
		assertNull(changePartyCalls.get(1));
		assertEquals(2, fakeHttpClient.capturedRequests.size());
	}

	@Test
	public void ackRequestsMatchCommandIds() {
		executor.executeCommands(MULTI_JSON);

		assertEquals(
				FAKE_SERVER_URL + "/api/plugin/commands/cmd-1/ack",
				fakeHttpClient.capturedRequests.get(0).url().toString());
		assertEquals(
				FAKE_SERVER_URL + "/api/plugin/commands/cmd-2/ack",
				fakeHttpClient.capturedRequests.get(1).url().toString());
	}

	@Test
	public void joinPartySendsChatMessage() {
		executor.executeCommands(JOIN_JSON);

		assertEquals(1, chatMessageCalls.size());
		assertEquals("You have joined the party.", chatMessageCalls.get(0));
	}

	@Test
	public void leavePartyKickedSendsChatMessage() {
		executor.executeCommands(LEAVE_KICKED_JSON);

		assertEquals(1, chatMessageCalls.size());
		assertEquals("You have been kicked from the party.", chatMessageCalls.get(0));
	}

	@Test
	public void leavePartyClosedSendsChatMessage() {
		executor.executeCommands(LEAVE_CLOSED_JSON);

		assertEquals(1, chatMessageCalls.size());
		assertEquals("The party has been closed by the leader.", chatMessageCalls.get(0));
	}

	@Test
	public void leavePartyLeftSendsNoChatMessage() {
		executor.executeCommands(LEAVE_LEFT_JSON);

		assertEquals(0, chatMessageCalls.size());
	}

	@Test
	public void leavePartyNullReasonSendsNoChatMessage() {
		executor.executeCommands(LEAVE_JSON);

		assertEquals(0, chatMessageCalls.size());
	}

	@Test
	public void pathTraversalCommandIdIsRejected() {
		executor.executeCommands(PATH_TRAVERSAL_JSON);

		assertEquals(1, changePartyCalls.size());
		assertEquals(0, fakeHttpClient.capturedRequests.size());
	}
}
