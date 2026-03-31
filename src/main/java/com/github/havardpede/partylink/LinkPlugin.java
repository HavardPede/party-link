package com.github.havardpede.partylink;

import com.google.inject.Provides;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
		name = "Party Link",
		description =
				"Connects RuneLite to the Party Link website for remote party management. "
						+ "Pair with your account to sync parties and receive commands from the web. "
						+ "This plugin requires a Party Link account to function.")
public class LinkPlugin extends Plugin {
	@Inject private Client client;
	@Inject private PartyService partyService;
	@Inject private OkHttpClient okHttpClient;
	@Inject private ScheduledExecutorService executorService;
	@Inject private ClientThread clientThread;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private ConfigManager configManager;
	@Inject private LinkConfig config;

	private LinkPoller poller;
	private RsnDetector rsnDetector;

	@Override
	protected void startUp() {
		if (config.serverUrl() == null || config.serverUrl().isEmpty()) {
			log.warn("Link plugin not started: server URL is not configured");
			return;
		}

		LinkApiClient apiClient =
				new LinkApiClient(okHttpClient, config.serverUrl(), this::getStoredToken);
		rsnDetector = new RsnDetector(apiClient, this::getPlayerName, executorService);
		CommandExecutor commandExecutor =
				new CommandExecutor(
						this::changeParty,
						this::sendChatMessage,
						apiClient,
						rsnDetector::getDetectedName);
		poller = new LinkPoller(apiClient, executorService, config, commandExecutor, rsnDetector);
		log.info("Link plugin started");

		if (client.getGameState() == GameState.LOGGED_IN) {
			if (config.enabled() && !getStoredToken().isEmpty()) {
				poller.start();
			}
			rsnDetector.onLoggedIn();
		}
	}

	@Override
	protected void shutDown() {
		if (poller != null) {
			poller.stop();
		}
		log.info("Link plugin stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		switch (event.getGameState()) {
			case LOGGED_IN:
				if (config.enabled() && !getStoredToken().isEmpty()) {
					poller.start();
				}
				rsnDetector.onLoggedIn();
				break;
			case LOGIN_SCREEN:
				poller.stop();
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		rsnDetector.onGameTick();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		if (!LinkConfig.CONFIG_GROUP.equals(event.getGroup())) {
			return;
		}
		if ("pairingKey".equals(event.getKey())) {
			handlePairingKeyChange(event.getNewValue());
		} else if (LinkConfig.BEARER_TOKEN_KEY.equals(event.getKey())
				|| "enabled".equals(event.getKey())
				|| "serverUrl".equals(event.getKey())) {
			restart();
		}
	}

	@Provides
	LinkConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(LinkConfig.class);
	}

	private String getPlayerName() {
		return client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null;
	}

	private void changeParty(String passphrase) {
		clientThread.invokeLater(() -> partyService.changeParty(passphrase));
	}

	private String getStoredToken() {
		String token =
				configManager.getConfiguration(
						LinkConfig.CONFIG_GROUP, LinkConfig.BEARER_TOKEN_KEY);
		return token != null ? token : "";
	}

	private void restart() {
		shutDown();
		startUp();
	}

	private void handlePairingKeyChange(String pairingKey) {
		if (pairingKey == null || pairingKey.isEmpty()) {
			configManager.unsetConfiguration(LinkConfig.CONFIG_GROUP, LinkConfig.BEARER_TOKEN_KEY);
			sendChatMessage("Party Link: Unpaired.");
			return;
		}
		String serverUrl = config.serverUrl();
		if (serverUrl == null || serverUrl.isEmpty()) {
			sendChatMessage("Party Link: Set the server URL before pairing.");
			return;
		}
		executorService.submit(() -> exchangePairingKey(serverUrl, pairingKey));
	}

	private void exchangePairingKey(String serverUrl, String pairingKey) {
		try {
			String token = LinkApiClient.pair(okHttpClient, serverUrl, pairingKey);
			configManager.setConfiguration(
					LinkConfig.CONFIG_GROUP, LinkConfig.BEARER_TOKEN_KEY, token);
			sendChatMessage("Party Link: Paired successfully!");
			restart();
		} catch (IOException e) {
			log.warn("Pairing failed: {}", e.getMessage());
			sendChatMessage("Party Link: Pairing failed - " + e.getMessage());
		}
	}

	private void sendChatMessage(String message) {
		chatMessageManager.queue(
				QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(message)
						.build());
	}
}
