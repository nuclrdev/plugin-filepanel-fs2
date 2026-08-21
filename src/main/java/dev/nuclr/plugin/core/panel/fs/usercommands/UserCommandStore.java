/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs.usercommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.nuclr.platform.NuclrSettings;
import lombok.extern.slf4j.Slf4j;

/**
 * Reads and writes the user-command list through the platform's own settings store, under
 * this plugin's namespace — the same mechanism every other persisted plugin preference uses,
 * so the list lands in {@code config/dev.nuclr.plugin.core.panel.fs/settings.json} next to
 * them rather than in a file of its own.
 *
 * <p>The whole list is one setting value: a list of flat maps (see {@link UserCommand#toMap}).
 * Order is the user's, and it is the order the list shows, so the list is stored and replaced
 * wholesale rather than keyed per command.
 *
 * <p>Reads never throw. A settings file that is missing, empty, or holds something other than
 * a list of commands yields an empty list — a corrupt preference should leave the user with
 * an empty F2 list to rebuild, not a panel that will not open.
 */
@Slf4j
public class UserCommandStore {

	/** Settings namespace; matches the plugin id so the file sits with the plugin's own config. */
	static final String Namespace = "dev.nuclr.plugin.core.panel.fs";

	/** Key holding the whole ordered list within {@link #Namespace}. */
	static final String Key = "userCommands";

	private final NuclrSettings settings;

	/**
	 * @param settings the platform settings store, from {@code NuclrPluginContext.getSettings()};
	 *                 may be {@code null}, in which case the list is simply not persisted
	 */
	public UserCommandStore(NuclrSettings settings) {
		this.settings = settings;
	}

	/**
	 * Load the stored commands in the user's order.
	 *
	 * @return the commands, never {@code null}; empty when nothing is stored or it is unreadable
	 */
	public List<UserCommand> load() {

		if (settings == null) {
			return new ArrayList<>();
		}

		Object stored;
		try {
			stored = settings.get(Namespace, Key);
		} catch (RuntimeException e) {
			log.warn("Could not read user commands: {}", e.getMessage(), e);
			return new ArrayList<>();
		}

		var commands = new ArrayList<UserCommand>();
		if (!(stored instanceof Iterable<?> entries)) {
			return commands;
		}
		for (Object entry : entries) {
			if (entry instanceof Map<?, ?> map) {
				UserCommand command = UserCommand.fromMap(map);
				if (command != null) {
					commands.add(command);
				}
			}
		}
		return commands;
	}

	/**
	 * Replace the stored list with {@code commands}.
	 *
	 * @param commands the commands to persist, in the order the list shows them
	 * @return {@code true} if they were written, {@code false} if the store refused or is absent
	 */
	public boolean save(List<UserCommand> commands) {

		if (settings == null) {
			log.debug("No settings store available; {} user command(s) not persisted",
					commands == null ? 0 : commands.size());
			return false;
		}

		var maps = new ArrayList<Map<String, Object>>();
		if (commands != null) {
			for (UserCommand command : commands) {
				maps.add(command.toMap());
			}
		}

		try {
			settings.set(Namespace, Key, maps);
			return true;
		} catch (RuntimeException e) {
			log.warn("Could not save user commands: {}", e.getMessage(), e);
			return false;
		}
	}
}
