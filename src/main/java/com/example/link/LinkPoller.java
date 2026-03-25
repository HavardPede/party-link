package com.example.link;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
public class LinkPoller
{
	static final int BASE_INTERVAL_SECONDS = 5;
	static final int MAX_INTERVAL_SECONDS = 60;
	static final String COMMANDS_URL = "https://osrs-party-finder.vercel.app/api/plugin/commands";
	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final OkHttpClient httpClient;
	private final ScheduledExecutorService executor;
	private final LinkConfig config;
	private final CommandExecutor commandExecutor;

	private ScheduledFuture<?> pollTask;
	private int currentIntervalSeconds = BASE_INTERVAL_SECONDS;

	public LinkPoller(OkHttpClient httpClient, ScheduledExecutorService executor, LinkConfig config,
		CommandExecutor commandExecutor)
	{
		this.httpClient = httpClient;
		this.executor = executor;
		this.config = config;
		this.commandExecutor = commandExecutor;
	}

	public void start()
	{
		if (pollTask != null && !pollTask.isDone())
		{
			return;
		}
		currentIntervalSeconds = BASE_INTERVAL_SECONDS;
		schedulePoll(BASE_INTERVAL_SECONDS);
		log.info("Polling started (interval: {}s)", BASE_INTERVAL_SECONDS);
	}

	public void stop()
	{
		if (pollTask != null)
		{
			pollTask.cancel(false);
			pollTask = null;
		}
		currentIntervalSeconds = BASE_INTERVAL_SECONDS;
		log.info("Polling stopped");
	}

	int getCurrentIntervalSeconds()
	{
		return currentIntervalSeconds;
	}

	private void schedulePoll(int delaySeconds)
	{
		pollTask = executor.schedule(this::poll, delaySeconds, TimeUnit.SECONDS);
	}

	private void poll()
	{
		String token = config.bearerToken();
		if (token == null || token.isEmpty())
		{
			log.warn("Bearer token is empty, skipping poll");
			schedulePoll(currentIntervalSeconds);
			return;
		}

		try
		{
			String responseBody = fetchCommands(token);
			commandExecutor.executeCommands(responseBody, token);
			currentIntervalSeconds = BASE_INTERVAL_SECONDS;
		}
		catch (IOException e)
		{
			log.warn("Poll failed: {}", e.getMessage());
			currentIntervalSeconds = Math.min(currentIntervalSeconds * 2, MAX_INTERVAL_SECONDS);
		}

		schedulePoll(currentIntervalSeconds);
	}

	String fetchCommands(String token) throws IOException
	{
		Request request = new Request.Builder()
			.url(COMMANDS_URL)
			.header(AUTHORIZATION_HEADER, BEARER_PREFIX + token)
			.build();

		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful())
			{
				throw new IOException("HTTP " + response.code());
			}

			if (response.body() == null)
			{
				return "{}";
			}

			return response.body().string();
		}
	}
}
