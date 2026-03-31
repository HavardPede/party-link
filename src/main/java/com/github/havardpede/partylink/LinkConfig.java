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
			keyName = "serverUrl",
			name = "Server URL",
			description = "Base URL of the Party Link server (e.g. http://localhost:3000)",
			position = 0,
			section = connectionSection)
	default String serverUrl() {
		return "";
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
