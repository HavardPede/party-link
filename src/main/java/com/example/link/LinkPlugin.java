package com.example.link;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.party.PartyService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import com.google.inject.Provides;
import okhttp3.OkHttpClient;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(name = "Link")
public class LinkPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private PartyService partyService;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private ClientThread clientThread;

	@Inject
	private LinkConfig config;

	private LinkPoller poller;
	private RsnDetector rsnDetector;

	@Override
	protected void startUp() throws Exception
	{
		rsnDetector = new RsnDetector(
			okHttpClient,
			config,
			() -> client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : null
		);
		CommandExecutor commandExecutor = new CommandExecutor(
			passphrase -> clientThread.invokeLater(() -> partyService.changeParty(passphrase)),
			okHttpClient,
			config,
			() -> rsnDetector != null ? rsnDetector.getDetectedName() : null
		);
		poller = new LinkPoller(okHttpClient, executorService, config, commandExecutor, rsnDetector);
		log.info("Link plugin started");

		if (client.getGameState() == GameState.LOGGED_IN
			&& config.enabled()
			&& !config.bearerToken().isEmpty())
		{
			poller.start();
		}

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			rsnDetector.onLoggedIn();
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		poller.stop();
		log.info("Link plugin stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOGGED_IN:
				if (config.enabled() && !config.bearerToken().isEmpty())
				{
					poller.start();
				}
				rsnDetector.onLoggedIn();
				break;
			case HOPPING:
			case LOGIN_SCREEN:
				poller.stop();
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		rsnDetector.onGameTick();
	}

	@Provides
	LinkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LinkConfig.class);
	}
}
