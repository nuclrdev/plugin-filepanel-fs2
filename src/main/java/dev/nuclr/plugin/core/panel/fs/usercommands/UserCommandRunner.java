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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.plugin.core.panel.fs.ExternalConsole;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import lombok.extern.slf4j.Slf4j;

/**
 * Runs a {@link UserCommand}: works out where it should run, asks first if the user said to,
 * and hands the line to the plugin's console launcher.
 *
 * <p>The console is {@link ExternalConsole} — the same one Shift+Enter opens for an
 * executable — so a user command lands in exactly the terminal this plugin already knows how
 * to start on each platform, and nothing here has an opinion about which shell that is.
 *
 * <p>Where it runs is decided in one place, {@link #resolveWorkingDirectory}: the configured
 * folder when there is one, otherwise wherever the panel is now. That is read at run time, so
 * a command with no working directory follows the panel from folder to folder.
 */
@Slf4j
public class UserCommandRunner {

	private static final String DialogTitle = "User Commands";

	private final NuclrPluginContext context;

	/** Where the active panel is, consulted only when a command configures no folder of its own. */
	private final Supplier<Path> panelDirectory;

	/**
	 * @param context        plugin context, used for sounds and error popups; may be {@code null}
	 * @param panelDirectory supplies the active panel's current folder; may be {@code null}
	 */
	public UserCommandRunner(NuclrPluginContext context, Supplier<Path> panelDirectory) {
		this.context = context;
		this.panelDirectory = panelDirectory;
	}

	/**
	 * Run {@code command}, confirming first when it asks for it. Safe to call from the EDT: the
	 * confirmation is modal, but starting the console happens on a virtual thread, because
	 * finding a terminal can mean trying several of them.
	 *
	 * @param command the command to run
	 * @return {@code true} if the console was asked to start it, {@code false} if the user
	 *         cancelled or the command could not run
	 */
	public boolean run(UserCommand command) {

		if (command == null || command.command().isEmpty()) {
			return false;
		}

		Optional<Path> resolved = resolveWorkingDirectory(command, panelDirectory == null ? null : panelDirectory.get());
		if (resolved.isEmpty()) {
			Alerts.showError(context, DialogTitle, workingDirectoryProblem(command));
			return false;
		}

		if (command.confirmationRequired() && !UserCommandDialogs.confirmRun(command, resolved.get(), context)) {
			return false;
		}

		Path workingDirectory = resolved.get();
		Thread.ofVirtual().name("user-command").start(() -> {
			try {
				ExternalConsole.runCommand(command.command(), workingDirectory);
				SoundEvents.confirmation(context);
			} catch (IOException | RuntimeException e) {
				log.warn("Could not run user command [{}] in {}: {}", command.command(), workingDirectory,
						e.getMessage(), e);
				Alerts.showError(context, DialogTitle,
						"Could not run the command:\n\n" + command.command() + "\n\n"
								+ (e.getMessage() != null ? e.getMessage() : e.toString()));
			}
		});
		return true;
	}

	/**
	 * Where {@code command} should run: its configured folder when it has one, otherwise
	 * {@code panelFolder}. Returns nothing when neither yields a usable folder — a configured
	 * path that has since been deleted, or no panel folder to fall back on.
	 *
	 * @param command     the command being run
	 * @param panelFolder the active panel's folder, or {@code null} if unknown
	 * @return the folder to run in, or empty when there is none
	 */
	public static Optional<Path> resolveWorkingDirectory(UserCommand command, Path panelFolder) {

		if (command != null && command.hasWorkingDirectory()) {
			try {
				Path configured = Path.of(command.workingDirectory()).toAbsolutePath().normalize();
				return Files.isDirectory(configured) ? Optional.of(configured) : Optional.empty();
			} catch (InvalidPathException e) {
				log.warn("User command [{}] has an unusable working directory [{}]: {}",
						command.name(), command.workingDirectory(), e.getMessage());
				return Optional.empty();
			}
		}

		if (panelFolder == null) {
			return Optional.empty();
		}
		Path folder = panelFolder.toAbsolutePath().normalize();
		return Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
	}

	/** The message explaining which of the two folders could not be used. */
	private String workingDirectoryProblem(UserCommand command) {
		if (command.hasWorkingDirectory()) {
			return "The working directory for \"" + command.name() + "\" is not a folder:\n\n"
					+ command.workingDirectory();
		}
		return "\"" + command.name() + "\" has no working directory of its own, "
				+ "and the current panel folder could not be used.";
	}
}
