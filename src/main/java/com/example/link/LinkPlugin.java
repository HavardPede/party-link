package com.example.link;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
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

	@Override
	protected void startUp() throws Exception
	{
		CommandExecutor commandExecutor = new CommandExecutor(
			passphrase -> clientThread.invokeLater(() -> partyService.changeParty(passphrase)),
			okHttpClient
		);
		poller = new LinkPoller(okHttpClient, executorService, config, commandExecutor);
		log.info("Link plugin started");
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
				break;
			case HOPPING:
			case LOGIN_SCREEN:
				poller.stop();
				break;
		}
	}

	@Provides
	LinkConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LinkConfig.class);
	}
}
