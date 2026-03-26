package com.example.link;

import com.google.inject.Provides;
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
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(name = "Party Link")
public class LinkPlugin extends Plugin {
	@Inject private Client client;
	@Inject private PartyService partyService;
	@Inject private OkHttpClient okHttpClient;
	@Inject private ScheduledExecutorService executorService;
	@Inject private ClientThread clientThread;
	@Inject private ChatMessageManager chatMessageManager;
	@Inject private LinkConfig config;

	private LinkPoller poller;
	private RsnDetector rsnDetector;

	@Override
	protected void startUp() {
		LinkApiClient apiClient = new LinkApiClient(okHttpClient, config);
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
			if (config.enabled() && !config.bearerToken().isEmpty()) {
				poller.start();
			}
			rsnDetector.onLoggedIn();
		}
	}

	@Override
	protected void shutDown() {
		poller.stop();
		log.info("Link plugin stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		switch (event.getGameState()) {
			case LOGGED_IN:
				if (config.enabled() && !config.bearerToken().isEmpty()) {
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

	private void sendChatMessage(String message) {
		chatMessageManager.queue(
				QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(message)
						.build());
	}
}
