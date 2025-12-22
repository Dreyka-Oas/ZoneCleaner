/*
 *	MCreator note: This file will be REGENERATED on each build.
 */
package oas.work.zone_cleaner.init;

import oas.work.zone_cleaner.command.RemoveCommand;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class ZoneCleanerModCommands {
	public static void load() {
		CommandRegistrationCallback.EVENT.register((dispatcher, commandBuildContext, environment) -> {
			RemoveCommand.register(dispatcher, commandBuildContext, environment);
		});
	}
}