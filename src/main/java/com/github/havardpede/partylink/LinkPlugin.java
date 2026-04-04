package com.github.havardpede.partylink;

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
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
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

	private WebSocketManager webSocketManager;
	private RsnDetector rsnDetector;

	@Override
	protected void startUp() {
		String wsUrl = config.websocketUrl();
		if (wsUrl == null || wsUrl.isEmpty()) {
			log.warn("Link plugin not started: WebSocket URL is not configured");
			return;
		}

		CommandExecutor commandExecutor =
				new CommandExecutor(this::changeParty, this::sendChatMessage);
		webSocketManager =
				new WebSocketManager(
						okHttpClient,
						wsUrl,
						this::getStoredToken,
						this::getPairingCode,
						this::onPairResult,
						commandExecutor,
						executorService);
		rsnDetector = new RsnDetector(webSocketManager::sendIdentify, this::getPlayerName);
		log.info("Link plugin started: gameState={}, enabled={}, hasToken={}, hasPairingCode={}",
				client.getGameState(), config.enabled(), hasToken(), hasPairingCode());

		if (client.getGameState() == GameState.LOGGED_IN) {
			if (config.enabled() && (hasToken() || hasPairingCode())) {
				webSocketManager.connect();
			}
			rsnDetector.onLoggedIn();
		}
	}

	@Override
	protected void shutDown() {
		if (webSocketManager != null) {
			webSocketManager.disconnect();
		}
		log.info("Link plugin stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		switch (event.getGameState()) {
			case LOGGED_IN:
				if (config.enabled() && (hasToken() || hasPairingCode())) {
					webSocketManager.connect();
				}
				rsnDetector.onLoggedIn();
				break;
			case LOGIN_SCREEN:
				webSocketManager.disconnect();
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		rsnDetector.onGameTick();
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event) {
		if (webSocketManager == null) {
			return;
		}
		String passphrase = event.getPassphrase();
		if (passphrase != null) {
			webSocketManager.sendPartyState("JOINED", passphrase);
		} else {
			webSocketManager.sendPartyState("LEFT", null);
		}
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
				|| "websocketUrl".equals(event.getKey())) {
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

	private boolean hasToken() {
		return !getStoredToken().isEmpty();
	}

	private String getPairingCode() {
		String code = config.pairingKey();
		return code != null ? code : "";
	}

	private boolean hasPairingCode() {
		return !getPairingCode().isEmpty();
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
		restart();
	}

	private void onPairResult(String token) {
		clearPairingCode();
		if (token == null) {
			sendChatMessage("Party Link: Pairing failed. Check your code and try again.");
		} else {
			configManager.setConfiguration(
					LinkConfig.CONFIG_GROUP, LinkConfig.BEARER_TOKEN_KEY, token);
			sendChatMessage("Party Link: Paired successfully!");
		}
	}

	private void clearPairingCode() {
		configManager.unsetConfiguration(LinkConfig.CONFIG_GROUP, "pairingKey");
	}

	private void sendChatMessage(String message) {
		chatMessageManager.queue(
				QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(message)
						.build());
	}
}
