/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.usercommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a user command runs. The rule the feature hinges on: a configured working directory
 * wins, an empty one defers to the panel, and neither is invented when it does not exist.
 */
class UserCommandRunnerTest {

	private static UserCommand withWorkingDirectory(String workingDirectory) {
		return new UserCommand("Git Status", "git status", workingDirectory, null, false);
	}

	@Test
	void aConfiguredFolderWinsOverThePanel(@TempDir Path configured, @TempDir Path panel) {
		Optional<Path> resolved = UserCommandRunner.resolveWorkingDirectory(
				withWorkingDirectory(configured.toString()), panel);

		assertEquals(configured.toAbsolutePath().normalize(), resolved.orElseThrow());
	}

	@Test
	void anEmptyFolderFallsBackToThePanel(@TempDir Path panel) {
		Optional<Path> resolved = UserCommandRunner.resolveWorkingDirectory(withWorkingDirectory(""), panel);

		assertEquals(panel.toAbsolutePath().normalize(), resolved.orElseThrow());
	}

	@Test
	void aConfiguredFolderIsNormalisedRatherThanUsedVerbatim(@TempDir Path configured) throws Exception {
		Path nested = Files.createDirectory(configured.resolve("sources"));
		String roundabout = nested.resolve("..").resolve("sources").toString();

		assertEquals(nested.toAbsolutePath().normalize(),
				UserCommandRunner.resolveWorkingDirectory(withWorkingDirectory(roundabout), null).orElseThrow());
	}

	@Test
	void aConfiguredFolderThatIsGoneDoesNotSilentlyFallBackToThePanel(@TempDir Path panel) {
		// Falling back would run the command somewhere the user never asked for — for a line like
		// `git reset --hard HEAD` that is the difference between a no-op and losing work.
		Optional<Path> resolved = UserCommandRunner.resolveWorkingDirectory(
				withWorkingDirectory(panel.resolve("deleted").toString()), panel);

		assertTrue(resolved.isEmpty());
	}

	@Test
	void aConfiguredFolderThatIsAFileIsRefused(@TempDir Path dir) throws Exception {
		Path file = Files.createFile(dir.resolve("build.log"));

		assertTrue(UserCommandRunner.resolveWorkingDirectory(withWorkingDirectory(file.toString()), dir).isEmpty());
	}

	@Test
	void withNeitherAConfiguredFolderNorAPanelThereIsNowhereToRun() {
		assertTrue(UserCommandRunner.resolveWorkingDirectory(withWorkingDirectory(""), null).isEmpty());
	}

	@Test
	void aPanelFolderThatIsNoLongerAFolderIsRefused(@TempDir Path dir) {
		assertTrue(UserCommandRunner.resolveWorkingDirectory(withWorkingDirectory(""), dir.resolve("gone")).isEmpty());
	}

	@Test
	void noCommandResolvesToNoFolder(@TempDir Path panel) {
		assertEquals(panel.toAbsolutePath().normalize(),
				UserCommandRunner.resolveWorkingDirectory(null, panel).orElseThrow());
	}
}
