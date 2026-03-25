package com.example.link;

import java.io.IOException;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@Slf4j
public class CommandExecutor
{
	static final String ACK_URL_BASE = "https://osrs-party-finder.vercel.app/api/plugin/commands/";
	static final String ACK_URL_SUFFIX = "/ack";

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final String JOIN_PARTY = "JOIN_PARTY";
	private static final String LEAVE_PARTY = "LEAVE_PARTY";
	private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
	private static final RequestBody EMPTY_BODY = RequestBody.create(JSON_MEDIA_TYPE, "{}");

	private final Consumer<String> changeParty;
	private final OkHttpClient httpClient;

	/**
	 * @param changeParty action to join/leave a party. Pass a non-null passphrase to join, null to leave.
	 *                    Typically wired as: passphrase -> clientThread.invokeLater(() -> partyService.changeParty(passphrase))
	 * @param httpClient  OkHttp client for ACK requests
	 */
	public CommandExecutor(Consumer<String> changeParty, OkHttpClient httpClient)
	{
		this.changeParty = changeParty;
		this.httpClient = httpClient;
	}

	public void executeCommands(String json, String bearerToken)
	{
		JsonArray commands = parseCommands(json);

		for (JsonElement element : commands)
		{
			JsonObject command = element.getAsJsonObject();
			String id = command.get("id").getAsString();
			String type = command.get("type").getAsString();
			String passphrase = extractPassphrase(command);

			executeCommand(id, type, passphrase);
			acknowledgeCommand(id, bearerToken);
		}
	}

	JsonArray parseCommands(String json)
	{
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();
		if (!root.has("commands"))
		{
			return new JsonArray();
		}
		return root.getAsJsonArray("commands");
	}

	private String extractPassphrase(JsonObject command)
	{
		if (!command.has("passphrase") || command.get("passphrase").isJsonNull())
		{
			return null;
		}
		return command.get("passphrase").getAsString();
	}

	private void executeCommand(String id, String type, String passphrase)
	{
		try
		{
			switch (type)
			{
				case JOIN_PARTY:
					log.info("Executing JOIN_PARTY (command={}, passphrase={})", id, passphrase);
					changeParty.accept(passphrase);
					break;
				case LEAVE_PARTY:
					log.info("Executing LEAVE_PARTY (command={})", id);
					changeParty.accept(null);
					break;
				default:
					log.warn("Unknown command type: {} (command={})", type, id);
					break;
			}
		}
		catch (RuntimeException e)
		{
			log.error("Failed to execute command {} (type={}): {}", id, type, e.getMessage());
		}
	}

	private void acknowledgeCommand(String id, String bearerToken)
	{
		try
		{
			Request request = new Request.Builder()
				.url(ACK_URL_BASE + id + ACK_URL_SUFFIX)
				.header(AUTHORIZATION_HEADER, BEARER_PREFIX + bearerToken)
				.post(EMPTY_BODY)
				.build();

			try (Response response = httpClient.newCall(request).execute())
			{
				if (response.isSuccessful())
				{
					log.debug("Acknowledged command {}", id);
				}
				else
				{
					log.warn("ACK failed for command {} (HTTP {})", id, response.code());
				}
			}
		}
		catch (IOException e)
		{
			log.warn("ACK request failed for command {}: {}", id, e.getMessage());
		}
	}
}
