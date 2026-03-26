package com.example.link;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LinkPluginTest {
	public static void main(String[] args) throws Exception {
		ExternalPluginManager.loadBuiltin(LinkPlugin.class);
		RuneLite.main(args);
	}
}
