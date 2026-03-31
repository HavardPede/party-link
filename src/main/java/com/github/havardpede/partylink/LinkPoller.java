package com.github.havardpede.partylink;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class LinkPoller {
	static final int BASE_INTERVAL_SECONDS = 5;
	static final int MAX_INTERVAL_SECONDS = 60;

	private final LinkApiClient apiClient;
	private final ScheduledExecutorService executor;
	private final LinkConfig config;
	private final CommandExecutor commandExecutor;
	private final RsnDetector rsnDetector;

	private ScheduledFuture<?> pollTask;
	private int currentIntervalSeconds = BASE_INTERVAL_SECONDS;

	LinkPoller(
			LinkApiClient apiClient,
			ScheduledExecutorService executor,
			LinkConfig config,
			CommandExecutor commandExecutor,
			RsnDetector rsnDetector) {
		this.apiClient = apiClient;
		this.executor = executor;
		this.config = config;
		this.commandExecutor = commandExecutor;
		this.rsnDetector = rsnDetector;
	}

	void start() {
		if (pollTask != null && !pollTask.isDone()) {
			return;
		}
		currentIntervalSeconds = BASE_INTERVAL_SECONDS;
		schedulePoll(BASE_INTERVAL_SECONDS);
		log.info("Polling started (interval: {}s)", BASE_INTERVAL_SECONDS);
	}

	void stop() {
		if (pollTask != null) {
			pollTask.cancel(false);
			pollTask = null;
		}
		currentIntervalSeconds = BASE_INTERVAL_SECONDS;
	}

	int getCurrentIntervalSeconds() {
		return currentIntervalSeconds;
	}

	private void schedulePoll(int delaySeconds) {
		pollTask = executor.schedule(this::poll, delaySeconds, TimeUnit.SECONDS);
	}

	private void poll() {
		if (!apiClient.hasToken()) {
			log.warn("Bearer token is empty, skipping poll");
			schedulePoll(currentIntervalSeconds);
			return;
		}

		try {
			String playerName = rsnDetector.getDetectedName();
			String responseBody = apiClient.fetchCommands(playerName);
			commandExecutor.executeCommands(responseBody);
			currentIntervalSeconds = BASE_INTERVAL_SECONDS;
		} catch (IOException e) {
			log.warn("Poll failed: {}", e.getMessage());
			currentIntervalSeconds = Math.min(currentIntervalSeconds * 2, MAX_INTERVAL_SECONDS);
		}

		schedulePoll(currentIntervalSeconds);
	}
}
