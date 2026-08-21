/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.usercommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What the Add/Edit dialog accepts, and the message it gives when it does not. */
class UserCommandValidationTest {

	private static final KeyStroke G = KeyStroke.getKeyStroke(KeyEvent.VK_G, 0);
	private static final KeyStroke R = KeyStroke.getKeyStroke(KeyEvent.VK_R, 0);

	private final UserCommand gitStatus = new UserCommand("Git Status", "git status", "", G, false);
	private final UserCommand hardReset = new UserCommand("Hard Reset", "git reset --hard HEAD", "", R, true);
	private final List<UserCommand> existing = List.of(gitStatus, hardReset);

	private static String errorOf(Optional<String> problem) {
		assertTrue(problem.isPresent(), "expected a validation error");
		return problem.get();
	}

	@Test
	void acceptsACompleteCommand() {
		var candidate = new UserCommand("Maven Build", "mvn clean install", "", null, false);

		assertTrue(UserCommandValidation.validate(candidate, existing, -1).isEmpty());
	}

	@Test
	void requiresAName() {
		var candidate = new UserCommand("  ", "git status", "", null, false);

		assertTrue(errorOf(UserCommandValidation.validate(candidate, existing, -1)).contains("name"));
	}

	@Test
	void requiresACommandLine() {
		var candidate = new UserCommand("Nameless", "", "", null, false);

		assertTrue(errorOf(UserCommandValidation.validate(candidate, existing, -1)).contains("command"));
	}

	@Test
	void refusesAKeyAnotherCommandAlreadyOwns() {
		var candidate = new UserCommand("Grep", "grep -r todo .", "", G, false);

		String message = errorOf(UserCommandValidation.validate(candidate, existing, -1));

		assertTrue(message.contains("G"), message);
		assertTrue(message.contains("Git Status"), "the message must name the command that owns the key: " + message);
	}

	@Test
	void anEditKeepingItsOwnKeyIsNotADuplicateOfItself() {
		var renamed = new UserCommand("Status", "git status", "", G, false);

		assertTrue(UserCommandValidation.validate(renamed, existing, 0).isEmpty());
	}

	@Test
	void anEditMayNotTakeAKeyItsNeighbourOwns() {
		var candidate = new UserCommand("Git Status", "git status", "", R, false);

		assertTrue(errorOf(UserCommandValidation.validate(candidate, existing, 0)).contains("Hard Reset"));
	}

	@Test
	void refusesAKeyTheListItselfNeeds() {
		var candidate = new UserCommand("Enter", "echo hi", "", KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), false);

		String message = errorOf(UserCommandValidation.validate(candidate, existing, -1));

		assertTrue(message.contains("User Commands list"), message);
	}

	@Test
	void noShortcutIsAlwaysFine() {
		assertTrue(UserCommandValidation.shortcutError(null, existing, -1).isEmpty());
		assertNull(UserCommandValidation.owningCommand(null, existing, -1));
	}

	@Test
	void owningCommandFindsTheHolderOfAKey() {
		assertSame(hardReset, UserCommandValidation.owningCommand(R, existing, -1));
		assertNull(UserCommandValidation.owningCommand(R, existing, 1), "the entry being edited is skipped");
	}

	@Test
	void anEmptyWorkingDirectoryIsValidBecauseItMeansThePanelsFolder() {
		assertTrue(UserCommandValidation.workingDirectoryError("").isEmpty());
		assertTrue(UserCommandValidation.workingDirectoryError("   ").isEmpty());
		assertTrue(UserCommandValidation.workingDirectoryError(null).isEmpty());
	}

	@Test
	void aFolderThatDoesNotExistYetIsStillAcceptable(@TempDir Path dir) {
		// The user may well be defining the command before creating the folder.
		assertTrue(UserCommandValidation.workingDirectoryError(dir.resolve("not-created-yet").toString()).isEmpty());
	}

	@Test
	void refusesAWorkingDirectoryThatIsAFile(@TempDir Path dir) throws Exception {
		Path file = Files.createFile(dir.resolve("build.log"));

		String message = errorOf(UserCommandValidation.workingDirectoryError(file.toString()));

		assertTrue(message.contains("file, not a folder"), message);
	}

	@Test
	void refusesNothingAtAllToSave() {
		assertEquals("There is nothing to save.", errorOf(UserCommandValidation.validate(null, existing, -1)));
	}
}
