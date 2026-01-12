package com.fauxbingo;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class FauxBingoPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(FauxBingoPlugin.class);
		RuneLite.main(args);
	}
}