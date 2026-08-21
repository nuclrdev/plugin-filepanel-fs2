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

import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.KeyStroke;

/**
 * One entry of the F2 user-command list: a command line, a label for it, and the three
 * optional refinements the user can attach — where to run it, which key runs it from the
 * list, and whether to ask first.
 *
 * <p>Immutable, and deliberately free of Swing beyond {@link KeyStroke}, which is the
 * representation the capture field and the dialog's key bindings both already speak.
 *
 * <p>{@link #workingDirectory()} being empty is meaningful rather than missing: it means
 * "wherever the panel is", resolved at run time rather than stored.
 *
 * @param name                 label shown in the list; required
 * @param command              the command line to run; required
 * @param workingDirectory     folder to run it in, or empty for the active panel's folder
 * @param shortcut             key that runs it while the list is open, or {@code null}
 * @param confirmationRequired whether to ask before running
 */
public record UserCommand(
		String name,
		String command,
		String workingDirectory,
		KeyStroke shortcut,
		boolean confirmationRequired) {

	/** Persisted field names; also the JSON keys under the {@code userCommands} setting. */
	static final String NameKey = "name";
	static final String CommandKey = "command";
	static final String WorkingDirectoryKey = "workingDirectory";
	static final String ShortcutKey = "shortcut";
	static final String ConfirmationRequiredKey = "confirmationRequired";

	public UserCommand {
		name = name == null ? "" : name.trim();
		command = command == null ? "" : command.trim();
		workingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
	}

	/** An empty command, the starting point for the Insert dialog. */
	public static UserCommand empty() {
		return new UserCommand("", "", "", null, false);
	}

	/** Whether a working directory was configured, as opposed to deferring to the panel. */
	public boolean hasWorkingDirectory() {
		return !workingDirectory.isEmpty();
	}

	/** The shortcut as shown in the list's Key column — {@code "G"}, {@code "F5"}, or empty. */
	public String shortcutLabel() {
		return UserCommandShortcuts.label(shortcut);
	}

	/**
	 * Flatten to the JSON-friendly shape the settings store round-trips. The shortcut becomes
	 * its {@link KeyStroke#toString()} form, which {@link KeyStroke#getKeyStroke(String)} parses
	 * back exactly — no key-name table of our own to keep in step with Swing's.
	 *
	 * @return a mutable map of primitives and strings, never {@code null}
	 */
	public Map<String, Object> toMap() {
		var map = new LinkedHashMap<String, Object>();
		map.put(NameKey, name);
		map.put(CommandKey, command);
		map.put(WorkingDirectoryKey, workingDirectory);
		map.put(ShortcutKey, UserCommandShortcuts.store(shortcut));
		map.put(ConfirmationRequiredKey, confirmationRequired);
		return map;
	}

	/**
	 * Rebuild a command from its persisted map, tolerating anything the file may hold: a
	 * hand-edited settings file, or an entry written by a future version. Unreadable fields
	 * fall back to their empty value rather than dropping the whole entry.
	 *
	 * @param map one entry as read back from the settings store; may be {@code null}
	 * @return the command, or {@code null} if it carries neither a name nor a command line
	 */
	public static UserCommand fromMap(Map<?, ?> map) {
		if (map == null) {
			return null;
		}
		String name = text(map.get(NameKey));
		String command = text(map.get(CommandKey));
		if (name.isEmpty() && command.isEmpty()) {
			return null;
		}
		return new UserCommand(
				name,
				command,
				text(map.get(WorkingDirectoryKey)),
				UserCommandShortcuts.parse(text(map.get(ShortcutKey))),
				Boolean.TRUE.equals(map.get(ConfirmationRequiredKey))
						|| "true".equalsIgnoreCase(text(map.get(ConfirmationRequiredKey))));
	}

	private static String text(Object value) {
		return value == null ? "" : value.toString().trim();
	}
}
