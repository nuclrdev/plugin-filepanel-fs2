package dev.nuclr.plugin.core.panel.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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
 *
 * <p>{@link #runCommand} feeds the same machinery a command line rather than a file: the
 * line is written to a throwaway script and that script is run exactly as any other
 * executable would be. Nothing here decides which shell a command line belongs to beyond
 * what launching a console already decided — {@code cmd} on Windows, {@code $SHELL} on
 * POSIX — so a user command inherits the console the platform already gives it.
 */
public final class ExternalConsole {

	/** Terminals to try on Linux/BSD, in descending order of "the user probably meant this". */
	private static final List<String[]> LINUX_TERMINALS = List.of(
		new String[] { "x-terminal-emulator", "-e" },
		new String[] { "gnome-terminal", "--" },
		new String[] { "konsole", "-e" },
		new String[] { "xfce4-terminal", "-e" },
		new String[] { "xterm", "-e" });

	/** Folder under the system temp directory holding the generated command scripts. */
	private static final String SCRIPT_FOLDER = "nuclr-user-commands";

	/** Scripts older than this are swept on the next run; the console owning them is long gone. */
	private static final Duration SCRIPT_RETENTION = Duration.ofHours(6);

	private ExternalConsole() {
	}

	/**
	 * Launch {@code executable} in a new console window, rooted at the executable's own folder.
	 *
	 * @param executable the local file to run
	 * @throws IOException if the file is not a runnable local file, or no console could be started
	 */
	public static void run(Path executable) throws IOException {
		run(executable, null);
	}

	/**
	 * Launch {@code executable} in a new console window rooted at {@code workingDirectory}.
	 *
	 * @param executable       the local file to run
	 * @param workingDirectory the folder to start the console in, or {@code null} for the
	 *                         executable's own folder
	 * @throws IOException if the file is not a runnable local file, or no console could be started
	 */
	public static void run(Path executable, Path workingDirectory) throws IOException {
		if (executable == null) {
			throw new IllegalArgumentException("executable must not be null");
		}

		Path target = executable.toAbsolutePath().normalize();
		if (!Files.isRegularFile(target)) {
			throw new IOException("Not a file: " + target);
		}

		Path cwd = workingDirectory != null
			? workingDirectory.toAbsolutePath().normalize()
			: target.getParent() != null ? target.getParent() : target;

		if (!Files.isDirectory(cwd)) {
			throw new IOException("Not a folder: " + cwd);
		}

		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

		if (os.contains("win")) {
			start(cwd, windowsCommand(target, cwd).toArray(String[]::new));
		} else if (os.contains("mac")) {
			start(cwd, macCommand(target).toArray(String[]::new));
		} else {
			runLinux(target, cwd);
		}
	}

	/**
	 * Run a command line in a new console window rooted at {@code workingDirectory}.
	 *
	 * <p>The line is written verbatim into a script for the platform's own console shell and
	 * that script is handed to {@link #run(Path, Path)}, so a command line goes through the
	 * one console launcher this plugin has rather than a second one of its own. Writing it to
	 * a script — instead of stuffing it into the launcher's argument list — is what keeps
	 * quoting, pipes and redirections in the user's line intact: the shell that reads the
	 * script is the only thing that ever parses it.
	 *
	 * <p>The console stays open on the shell afterwards, and because the script runs
	 * <em>in</em> that shell, a line such as {@code cd /nuclr/sources} leaves the window
	 * sitting in that folder.
	 *
	 * @param commandLine      the command line to run; must not be blank
	 * @param workingDirectory the folder to start the console in
	 * @throws IOException if the script could not be written or no console could be started
	 */
	public static void runCommand(String commandLine, Path workingDirectory) throws IOException {
		if (commandLine == null || commandLine.isBlank()) {
			throw new IllegalArgumentException("commandLine must not be blank");
		}
		if (workingDirectory == null) {
			throw new IllegalArgumentException("workingDirectory must not be null");
		}

		Path cwd = workingDirectory.toAbsolutePath().normalize();
		if (!Files.isDirectory(cwd)) {
			throw new IOException("Not a folder: " + cwd);
		}

		Path script = writeCommandScript(commandLine, cwd);
		run(script, cwd);
	}

	/**
	 * Materialise {@code commandLine} as a script the platform's console shell can run, in a
	 * swept scratch folder. The file cannot be deleted once the console has it — the window
	 * outlives the commander by design — so stale scripts are collected on the way in instead.
	 */
	static Path writeCommandScript(String commandLine, Path workingDirectory) throws IOException {

		Path folder = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), SCRIPT_FOLDER));
		sweepStaleScripts(folder);

		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		boolean windows = os.contains("win");
		String suffix = windows ? ".cmd" : os.contains("mac") ? ".command" : ".sh";

		Path script = Files.createTempFile(folder, "command-", suffix);
		Files.writeString(script, windows
			? windowsCommandScript(commandLine, workingDirectory)
			: posixCommandScript(commandLine, workingDirectory));

		if (!windows) {
			script.toFile().setExecutable(true, true);
		}
		return script;
	}

	/**
	 * A batch file, run by the {@code cmd /k} the console launcher already starts. {@code cd /d}
	 * is belt and braces next to {@code start /D}, and it also means the window is left in that
	 * folder rather than wherever a {@code cd} in the user's own line took it.
	 */
	static String windowsCommandScript(String commandLine, Path workingDirectory) {
		return "@echo off\r\n"
			+ "cd /d \"" + workingDirectory + "\"\r\n"
			+ commandLine + "\r\n";
	}

	/**
	 * A shell script that ends by replacing itself with an interactive shell, so the window
	 * stays up and — because the {@code cd} and the command ran in that same process — stays in
	 * whatever folder the command left it in.
	 */
	static String posixCommandScript(String commandLine, Path workingDirectory) {
		return "#!/bin/sh\n"
			+ "cd " + quote(workingDirectory.toString()) + " || exit 1\n"
			+ commandLine + "\n"
			+ "exec \"${SHELL:-/bin/sh}\"\n";
	}

	/** Drop scripts left behind by consoles that have long since been closed. */
	private static void sweepStaleScripts(Path folder) {
		Instant cutoff = Instant.now().minus(SCRIPT_RETENTION);
		try (var scripts = Files.list(folder)) {
			scripts.filter(Files::isRegularFile).forEach(script -> {
				try {
					FileTime modified = Files.getLastModifiedTime(script);
					if (modified.toInstant().isBefore(cutoff)) {
						Files.deleteIfExists(script);
					}
				} catch (IOException e) {
					// The file is in use or already gone; either way it is not ours to worry about.
				}
			});
		} catch (IOException e) {
			// Sweeping is housekeeping, never a reason to fail the command the user asked for.
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
