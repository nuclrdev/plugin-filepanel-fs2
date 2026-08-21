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

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javax.swing.KeyStroke;

/**
 * What the Add/Edit dialog will and will not accept, kept apart from the dialog so the rules
 * can be read — and tested — without a display.
 *
 * <p>Each check returns the message the user should see, or nothing when the value is fine.
 * The working directory is the one field that can be wrong without being invalid: a folder
 * that does not exist yet is a warning to show, not a reason to refuse the command, because
 * the user may well be defining it before creating the folder.
 */
public final class UserCommandValidation {

	private UserCommandValidation() {
	}

	/**
	 * Check a command about to be saved against the list it is joining.
	 *
	 * @param candidate    the command as the dialog has it
	 * @param existing     the current list, in order
	 * @param editingIndex index of the entry being edited within {@code existing}, or {@code -1}
	 *                     when adding — so an edit does not report itself as its own duplicate
	 * @return the error to show, or empty when the command can be saved
	 */
	public static Optional<String> validate(UserCommand candidate, List<UserCommand> existing, int editingIndex) {

		if (candidate == null) {
			return Optional.of("There is nothing to save.");
		}
		if (candidate.name().isEmpty()) {
			return Optional.of("Enter a name for this command.");
		}
		if (candidate.command().isEmpty()) {
			return Optional.of("Enter the command to run.");
		}

		Optional<String> pathProblem = workingDirectoryError(candidate.workingDirectory());
		if (pathProblem.isPresent()) {
			return pathProblem;
		}

		return shortcutError(candidate.shortcut(), existing, editingIndex);
	}

	/**
	 * Check a key the user has just pressed in the capture field.
	 *
	 * @param shortcut     the captured key, or {@code null} to clear the shortcut
	 * @param existing     the current list, in order
	 * @param editingIndex index of the entry being edited, or {@code -1} when adding
	 * @return the error to show, or empty when the key can be assigned
	 */
	public static Optional<String> shortcutError(KeyStroke shortcut, List<UserCommand> existing, int editingIndex) {

		if (shortcut == null) {
			return Optional.empty();
		}
		if (UserCommandShortcuts.isReserved(shortcut)) {
			return Optional.of(UserCommandShortcuts.label(shortcut)
					+ " is used by the User Commands list itself. Choose another key — a letter or a digit works well.");
		}

		UserCommand owner = owningCommand(shortcut, existing, editingIndex);
		if (owner != null) {
			return Optional.of("The key " + UserCommandShortcuts.label(shortcut)
					+ " is already assigned to \"" + owner.name() + "\".");
		}
		return Optional.empty();
	}

	/**
	 * The command already holding {@code shortcut}, ignoring the entry being edited.
	 *
	 * @param shortcut     the key to look for; {@code null} matches nothing
	 * @param existing     the current list, in order
	 * @param editingIndex index of the entry being edited, or {@code -1} when adding
	 * @return the command owning the key, or {@code null} when it is free
	 */
	public static UserCommand owningCommand(KeyStroke shortcut, List<UserCommand> existing, int editingIndex) {

		if (shortcut == null || existing == null) {
			return null;
		}
		for (int index = 0; index < existing.size(); index++) {
			if (index == editingIndex) {
				continue;
			}
			UserCommand other = existing.get(index);
			if (other != null && shortcut.equals(other.shortcut())) {
				return other;
			}
		}
		return null;
	}

	/**
	 * Check a typed working directory. Empty is valid — it means the active panel's folder —
	 * and so is a folder that does not exist yet; only text that is not a usable path at all,
	 * or names an existing file, is refused.
	 *
	 * @param workingDirectory the text in the working-directory field
	 * @return the error to show, or empty when the value is acceptable
	 */
	public static Optional<String> workingDirectoryError(String workingDirectory) {

		if (workingDirectory == null || workingDirectory.isBlank()) {
			return Optional.empty();
		}
		try {
			Path path = Path.of(workingDirectory.trim());
			if (Files.exists(path) && !Files.isDirectory(path)) {
				return Optional.of("The working directory is a file, not a folder:\n" + path);
			}
			return Optional.empty();
		} catch (InvalidPathException e) {
			return Optional.of("The working directory is not a valid path:\n" + workingDirectory.trim());
		}
	}
}
