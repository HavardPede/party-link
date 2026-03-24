package com.example.link;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import org.junit.Test;

public class LinkPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LinkPlugin.class);
		RuneLite.main(args);
	}

	@Test
	public void testStartUp() throws Exception
	{
		ExternalPluginManager.loadBuiltin(LinkPlugin.class);
		RuneLite.main(new String[]{"--developer-mode", "--debug"});
	}
}
