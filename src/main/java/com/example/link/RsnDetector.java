package com.example.link;

import java.io.IOException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class RsnDetector
{
	static final String RSN_PATH = "/api/plugin/rsn";
	static final int[] BACKOFF_TICKS = {1, 2, 4, 8};
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";
	private static final MediaType JSON = MediaType.parse("application/json");

	private final OkHttpClient httpClient;
	private final LinkConfig config;
	private final Supplier<String> playerNameSupplier;

	private int tickCount;
	private int nextCheckIndex;
	private boolean pending;
	private String detectedName;

	public RsnDetector(OkHttpClient httpClient, LinkConfig config, Supplier<String> playerNameSupplier)
	{
		this.httpClient = httpClient;
		this.config = config;
		this.playerNameSupplier = playerNameSupplier;
		this.pending = false;
		this.nextCheckIndex = 0;
		this.tickCount = 0;
	}

	/** Called by LinkPlugin when GameStateChanged(LOGGED_IN) fires. */
	public void onLoggedIn()
	{
		tickCount = 0;
		nextCheckIndex = 0;
		pending = true;
		detectedName = null;
	}

	/** Called by LinkPlugin on every GameTick. */
	public void onGameTick()
	{
		if (!pending || nextCheckIndex >= BACKOFF_TICKS.length)
		{
			return;
		}

		tickCount++;

		if (tickCount < BACKOFF_TICKS[nextCheckIndex])
		{
			return;
		}

		nextCheckIndex++;
		String name = playerNameSupplier.get();

		if (name != null && !name.isEmpty())
		{
			detectedName = name;
			pending = false;
			postRsn(name);
		}
		else if (nextCheckIndex >= BACKOFF_TICKS.length)
		{
			log.warn("Failed to detect RSN after {} tick attempts", BACKOFF_TICKS.length);
			pending = false;
		}
	}

	/** Returns the last detected RSN, or null if none detected yet. */
	public String getDetectedName()
	{
		return detectedName;
	}

	void postRsn(String playerName)
	{
		String token = config.bearerToken();
		if (token == null || token.isEmpty())
		{
			log.warn("Cannot post RSN: bearer token is empty");
			return;
		}

		String body = "{\"playerName\":\"" + playerName.replace("\"", "\\\"") + "\"}";
		Request request = new Request.Builder()
			.url(config.serverUrl() + RSN_PATH)
			.header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
			.post(RequestBody.create(JSON, body))
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (response.isSuccessful())
			{
				log.info("RSN registered: {}", playerName);
			}
			else
			{
				log.warn("RSN registration failed (HTTP {})", response.code());
			}
		}
		catch (IOException e)
		{
			log.warn("RSN registration request failed: {}", e.getMessage());
		}
	}
}
