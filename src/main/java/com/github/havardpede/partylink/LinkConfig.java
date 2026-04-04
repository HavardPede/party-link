package com.github.havardpede.partylink;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("link")
public interface LinkConfig extends Config {
	String CONFIG_GROUP = "link";
	String BEARER_TOKEN_KEY = "bearerToken";

	@ConfigSection(
			name = "Connection",
			description = "Connect this plugin to your Party Link account",
			position = 0)
	String connectionSection = "connection";

	@ConfigItem(
			keyName = "websocketUrl",
			name = "WebSocket URL",
			description =
					"WebSocket URL for real-time command delivery " + "(e.g. wss://example.com)",
			position = 0,
			section = connectionSection)
	default String websocketUrl() {
		return "wss://osrs-party-finder-relay.fly.dev";
	}

	@ConfigItem(
			keyName = "pairingKey",
			name = "Pairing Code",
			description =
					"Paste your pairing code from the Party Link website. "
							+ "Clearing this field will unpair your account.",
			secret = true,
			position = 1,
			section = connectionSection)
	default String pairingKey() {
		return "";
	}

	@ConfigItem(
			keyName = "enabled",
			name = "Enable Sync",
			description =
					"Enable automatic party sync. When enabled, the plugin polls the server "
							+ "for commands and executes them in your game client.",
			position = 2,
			section = connectionSection)
	default boolean enabled() {
		return true;
	}
}
