package com.github.havardpede.partylink;

import java.util.concurrent.Executor;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class RsnDetector {
	private final LinkApiClient apiClient;
	private final Supplier<String> playerNameSupplier;
	private final Executor executor;

	private boolean pending;
	private String detectedName;

	RsnDetector(LinkApiClient apiClient, Supplier<String> playerNameSupplier, Executor executor) {
		this.apiClient = apiClient;
		this.playerNameSupplier = playerNameSupplier;
		this.executor = executor;
	}

	void onLoggedIn() {
		pending = true;
		tryDetect(true);
	}

	void onGameTick() {
		if (!pending) {
			return;
		}
		tryDetect(false);
	}

	String getDetectedName() {
		return detectedName;
	}

	private void tryDetect(boolean canRetry) {
		String name = playerNameSupplier.get();
		if (name != null && !name.isEmpty()) {
			boolean changed = !name.equals(detectedName);
			detectedName = name;
			pending = false;
			if (changed) {
				executor.execute(() -> apiClient.postRsn(name));
			}
		} else if (!canRetry) {
			log.warn("Could not detect RSN after login");
			pending = false;
		}
	}
}
