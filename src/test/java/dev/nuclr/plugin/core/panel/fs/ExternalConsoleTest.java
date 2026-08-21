package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalConsoleTest {

	@Test
	void windowsRunsTheScriptThroughStartAndKeepsTheWindowOpen(@TempDir Path dir) {
		Path bat = dir.resolve("build.bat");
		List<String> command = ExternalConsole.windowsCommand(bat, dir);

		// "start" is a cmd builtin, so it has to be hosted by a cmd /c.
		assertEquals(List.of("cmd.exe", "/c", "start"), command.subList(0, 3));

		// The empty window title must survive: drop it and cmd takes the quoted path as the
		// title, opening a bare console instead of running anything.
		assertEquals("", command.get(3));

		// The script runs in its own folder, so relative paths inside it resolve.
		assertEquals(List.of("/D", dir.toString()), command.subList(4, 6));

		// /k rather than /c, so the window survives the script and its output stays readable.
		assertEquals(List.of("cmd.exe", "/k", bat.toString()), command.subList(6, 9));
	}

	@Test
	void macOpensTheScriptWithTerminal(@TempDir Path dir) {
		Path script = dir.resolve("build.sh");
		assertEquals(List.of("open", "-a", "Terminal", script.toString()),
				ExternalConsole.macCommand(script));
	}

	@Test
	void posixKeepsTheShellAliveAfterTheProgramExits(@TempDir Path dir) {
		Path script = dir.resolve("build.sh");
		String command = ExternalConsole.posixKeepOpenScript(script);

		assertTrue(command.startsWith("'" + script + "'"),
				"the program path should be single-quoted, was: " + command);
		assertTrue(command.contains("; exec "),
				"the shell should be exec'd afterwards so the window stays open, was: " + command);
	}

	@Test
	void posixQuotingSurvivesAnApostropheInThePath(@TempDir Path dir) throws Exception {
		Path script = dir.resolve("sergio's build.sh");
		String command = ExternalConsole.posixKeepOpenScript(script);

		// A naive single-quote would end the quoted string early and split the path into
		// separate words; the close/escape/reopen form keeps it one argument.
		assertTrue(command.contains("'\\''"), "apostrophe should be escaped, was: " + command);
		assertTrue(command.startsWith("'"), command);
	}

	@Test
	void rejectsAnythingThatIsNotARegularFile(@TempDir Path dir) {
		assertThrows(IllegalArgumentException.class, () -> ExternalConsole.run(null));
		assertThrows(IOException.class, () -> ExternalConsole.run(dir.resolve("missing.bat")));
		assertThrows(IOException.class, () -> ExternalConsole.run(dir));
	}

	@Test
	void runsFromTheExecutablesOwnFolderEvenWhenInvokedByRelativePath(@TempDir Path dir) throws Exception {
		Path bat = Files.createFile(dir.resolve("build.bat"));
		List<String> command = ExternalConsole.windowsCommand(bat.toAbsolutePath().normalize(), dir);

		assertEquals(dir.toString(), command.get(5));
		assertTrue(command.get(8).endsWith("build.bat"), command.get(8));
	}

	@Test
	void aCommandLineBecomesAScriptForThePlatformsOwnConsoleShell(@TempDir Path dir) throws Exception {
		Path script = ExternalConsole.writeCommandScript("git status", dir);

		assertTrue(Files.isRegularFile(script), "the command must be materialised as a runnable script");
		assertTrue(Files.readString(script).contains("git status"),
				"the user's line goes into the script verbatim so only the shell ever parses it");
	}

	@Test
	void theWindowsScriptEntersTheWorkingDirectoryAndRunsTheLine(@TempDir Path dir) {
		String script = ExternalConsole.windowsCommandScript("git reset --hard HEAD", dir);

		// /d so a working directory on another drive is honoured, not just another folder.
		assertTrue(script.contains("cd /d \"" + dir + "\""), script);
		assertTrue(script.contains("git reset --hard HEAD"), script);
		assertTrue(script.startsWith("@echo off"), script);
	}

	@Test
	void thePosixScriptEndsByExecingTheShellSoTheWindowStaysAndKeepsTheDirectory(@TempDir Path dir) {
		String script = ExternalConsole.posixCommandScript("cd /nuclr/sources", dir);

		assertTrue(script.startsWith("#!/bin/sh"), script);
		assertTrue(script.contains("cd '" + dir + "' || exit 1"), script);
		// exec (rather than a nested shell) is what leaves the window in whatever directory the
		// user's own cd took it to, which is the point of a command like `cd /nuclr/sources`.
		assertTrue(script.trim().endsWith("exec \"${SHELL:-/bin/sh}\""), script);
	}

	@Test
	void thePosixScriptQuotingSurvivesAnApostropheInTheWorkingDirectory(@TempDir Path dir) {
		Path awkward = dir.resolve("sergio's sources");
		String script = ExternalConsole.posixCommandScript("git status", awkward);

		assertTrue(script.contains("'\\''"), "apostrophe should be escaped, was: " + script);
	}

	@Test
	void runCommandRejectsABlankLineOrAMissingFolder(@TempDir Path dir) {
		assertThrows(IllegalArgumentException.class, () -> ExternalConsole.runCommand("  ", dir));
		assertThrows(IllegalArgumentException.class, () -> ExternalConsole.runCommand("git status", null));
		assertThrows(IOException.class, () -> ExternalConsole.runCommand("git status", dir.resolve("missing")));
	}

	@Test
	void runRejectsAWorkingDirectoryThatIsNotAFolder(@TempDir Path dir) throws Exception {
		Path bat = Files.createFile(dir.resolve("build.bat"));

		assertThrows(IOException.class, () -> ExternalConsole.run(bat, dir.resolve("missing")));
	}
}
