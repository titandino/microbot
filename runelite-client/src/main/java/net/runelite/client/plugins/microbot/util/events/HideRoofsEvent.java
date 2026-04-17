package net.runelite.client.plugins.microbot.util.events;

import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.BlockingEvent;
import net.runelite.client.plugins.microbot.BlockingEventPriority;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.MicrobotConfig;
import net.runelite.client.plugins.microbot.util.settings.Rs2Settings;

public class HideRoofsEvent implements BlockingEvent
{
	@Override
	public boolean validate()
	{
		return isConfigEnabled() && Microbot.isLoggedIn() && !Rs2Settings.isHideRoofsEnabled();
	}

	@Override
	public boolean execute()
	{
		if (!isConfigEnabled())
		{
			return true;
		}
		return Rs2Settings.hideRoofs();
	}

	private boolean isConfigEnabled()
	{
		ConfigManager configManager = Microbot.getConfigManager();
		if (configManager == null)
		{
			return true;
		}

		MicrobotConfig config = configManager.getConfig(MicrobotConfig.class);
		return config == null || config.hideRoofs();
	}

	@Override
	public BlockingEventPriority priority()
	{
		return BlockingEventPriority.HIGH;
	}
}
