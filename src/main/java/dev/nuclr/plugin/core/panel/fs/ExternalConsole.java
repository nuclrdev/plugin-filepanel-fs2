package dev.nuclr.plugin.core.panel.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs a local executable in a console window of its own, outside the commander.
 *
 * <p>This is the Shift+Enter counterpart to the host's embedded console: plain Enter runs a
 * program in a terminal that replaces the file listing, while this launches a detached
 * window that survives the commander and can be left running.
 *
 * <p>The console is kept open after the program exits ({@code cmd /k}, {@code $SHELL} on
 * POSIX), because the point of asking for a window is usually to read what the program
 * printed. The working directory is the executable's own folder, matching the embedded
 * console and what a script invoking relative paths expects.
 */
public final class ExternalConsole {

	/** Terminals to try on Linux/BSD, in descending order of "the user probably meant this". */
	private static final List<String[]> LINUX_TERMINALS = List.of(
		new String[] { "x-terminal-emulator", "-e" },
		new String[] { "gnome-terminal", "--" },
		new String[] { "konsole", "-e" },
		new String[] { "xfce4-terminal", "-e" },
		new String[] { "xterm", "-e" });

	private ExternalConsole() {
	}

	/**
	 * Launch {@code executable} in a new console window.
	 *
	 * @param executable the local file to run
	 * @throws IOException if the file is not a runnable local file, or no console could be started
	 */
	public static void run(Path executable) throws IOException {
		if (executable == null) {
			throw new IllegalArgumentException("executable must not be null");
		}

		Path target = executable.toAbsolutePath().normalize();
		if (!Files.isRegularFile(target)) {
			throw new IOException("Not a file: " + target);
		}

		Path workingDirectory = target.getParent() != null ? target.getParent() : target;
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (os.contains("win")) {
			start(workingDirectory, windowsCommand(target, workingDirectory).toArray(String[]::new));
		} else if (os.contains("mac")) {
			start(workingDirectory, macCommand(target).toArray(String[]::new));
		} else {
			runLinux(target, workingDirectory);
		}
	}

	/**
	 * {@code start} is a {@code cmd} builtin, so it needs a {@code cmd /c} to host it. Its first
	 * quoted argument is taken as the window title, which is why the empty title is passed
	 * explicitly — omit it and a quoted path would be swallowed as the title and nothing would
	 * run. The inner {@code cmd /k} is what keeps the window open once the script finishes.
	 */
	static List<String> windowsCommand(Path target, Path workingDirectory) {
		return List.of("cmd.exe", "/c", "start", "", "/D", workingDirectory.toString(),
			"cmd.exe", "/k", target.toString());
	}

	/**
	 * Terminal.app takes the file to run as a document: it opens a window, runs it, and leaves
	 * the session there, so no explicit "keep open" flag is needed.
	 */
	static List<String> macCommand(Path target) {
		return List.of("open", "-a", "Terminal", target.toString());
	}

	private static void runLinux(Path target, Path workingDirectory) throws IOException {
		if (!Files.isExecutable(target)) {
			throw new IOException("File is not executable: " + target);
		}

		String keepOpen = posixKeepOpenScript(target);

		IOException lastFailure = null;
		for (String[] terminal : LINUX_TERMINALS) {
			List<String> command = new ArrayList<>(List.of(terminal));
			command.add("/bin/sh");
			command.add("-c");
			command.add(keepOpen);
			try {
				start(workingDirectory, command.toArray(String[]::new));
				return;
			} catch (IOException e) {
				// This terminal is not installed; try the next one.
				lastFailure = e;
			}
		}
		throw new IOException("No terminal emulator found to run " + target, lastFailure);
	}

	/**
	 * Hand the program to an interactive shell rather than exec'ing it directly, so the window
	 * stays up afterwards instead of closing the instant the program exits.
	 */
	static String posixKeepOpenScript(Path target) {
		String shell = System.getenv("SHELL");
		return quote(target.toString()) + "; exec " + (shell == null || shell.isBlank() ? "/bin/sh" : shell);
	}

	private static void start(Path workingDirectory, String... command) throws IOException {
		new ProcessBuilder(command)
			.directory(workingDirectory.toFile())
			.redirectErrorStream(true)
			.start();
	}

	/** Single-quote for a POSIX shell, closing and reopening around any embedded quote. */
	private static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}
}
