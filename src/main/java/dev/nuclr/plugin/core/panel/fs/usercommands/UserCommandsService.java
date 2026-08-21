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

import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * Plugin-level entry point for the F2 User Commands feature: the one class the panel plugin
 * needs to know about.
 *
 * <p>It wires the three pieces that have no reason to know about each other — the
 * {@link UserCommandStore} behind the platform settings, the {@link UserCommandsDialog} the
 * user sees, and the {@link UserCommandRunner} that starts the console — and owns nothing
 * else. The list is read from the store each time the dialog opens rather than cached, so a
 * second commander window editing the same commands is picked up rather than overwritten.
 *
 * <p>The panel's folder is taken as a supplier rather than a value, because a command with no
 * working directory of its own should run wherever the panel is at the moment it runs.
 */
@Slf4j
public class UserCommandsService {

	private final NuclrPluginContext context;
	private final UserCommandStore store;
	private final Supplier<Path> panelDirectory;

	/**
	 * @param context        the plugin context, supplying settings and sounds
	 * @param panelDirectory supplies the active panel's current folder; may be {@code null}
	 */
	public UserCommandsService(NuclrPluginContext context, Supplier<Path> panelDirectory) {
		this(context, panelDirectory, new UserCommandStore(context == null ? null : context.getSettings()));
	}

	/**
	 * @param context        the plugin context, supplying sounds
	 * @param panelDirectory supplies the active panel's current folder; may be {@code null}
	 * @param store          the persistence layer to use
	 */
	public UserCommandsService(NuclrPluginContext context, Supplier<Path> panelDirectory, UserCommandStore store) {
		this.context = context;
		this.panelDirectory = panelDirectory;
		this.store = store;
	}

	/**
	 * Open the User Commands list. Returns immediately; the dialog itself is modal and is shown
	 * on the EDT, anchored to the active commander window.
	 */
	public void open() {

		List<UserCommand> commands = store.load();
		Path folder = panelDirectory == null ? null : panelDirectory.get();
		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();

		SoundEvents.popup(context);
		SwingUtilities.invokeLater(() -> new UserCommandsDialog(
				owner, commands, folder, store::save, this::run, context).setVisible(true));
	}

	/**
	 * Run one command outside the dialog — the path taken when the user picks it with Enter or
	 * with its own key, after the list has closed.
	 *
	 * @param command the command to run
	 */
	public void run(UserCommand command) {
		new UserCommandRunner(context, panelDirectory).run(command);
	}
}
