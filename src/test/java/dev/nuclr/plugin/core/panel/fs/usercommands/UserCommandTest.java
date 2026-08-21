/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.usercommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;

import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;

/** The user-command record: normalisation and its round trip through the persisted map. */
class UserCommandTest {

	@Test
	void trimsTextAndTreatsNullAsEmpty() {
		var command = new UserCommand("  Git Status  ", "  git status  ", null, null, false);

		assertEquals("Git Status", command.name());
		assertEquals("git status", command.command());
		assertEquals("", command.workingDirectory());
		assertFalse(command.hasWorkingDirectory(), "an empty working directory means 'use the panel's folder'");
	}

	@Test
	void survivesTheRoundTripThroughItsPersistedMap() {
		var original = new UserCommand("Hard Reset", "git reset --hard HEAD", "C:/nuclr/sources",
				KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), true);

		UserCommand restored = UserCommand.fromMap(original.toMap());

		assertEquals(original, restored);
		assertEquals("R", restored.shortcutLabel());
		assertTrue(restored.confirmationRequired());
	}

	@Test
	void roundTripsAFunctionKeyShortcut() {
		var original = new UserCommand("Build", "mvn clean install", "",
				KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), false);

		UserCommand restored = UserCommand.fromMap(original.toMap());

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), restored.shortcut());
		assertEquals("F5", restored.shortcutLabel());
	}

	@Test
	void aCommandWithoutAShortcutStoresAndRestoresNone() {
		var original = new UserCommand("Maven Build", "mvn clean install", "", null, false);

		assertEquals("", original.toMap().get(UserCommand.ShortcutKey));
		assertNull(UserCommand.fromMap(original.toMap()).shortcut());
		assertEquals("", original.shortcutLabel());
	}

	@Test
	void readsAHandEditedEntryWithMissingFields() {
		// Only the command line is present: everything else must fall back rather than blow up.
		UserCommand restored = UserCommand.fromMap(Map.of(UserCommand.CommandKey, "git status"));

		assertEquals("git status", restored.command());
		assertEquals("", restored.name());
		assertEquals("", restored.workingDirectory());
		assertNull(restored.shortcut());
		assertFalse(restored.confirmationRequired());
	}

	@Test
	void acceptsConfirmationWrittenAsTextRatherThanABoolean() {
		var map = new HashMap<String, Object>();
		map.put(UserCommand.CommandKey, "git status");
		map.put(UserCommand.ConfirmationRequiredKey, "true");

		assertTrue(UserCommand.fromMap(map).confirmationRequired());
	}

	@Test
	void ignoresAnEntryThatNamesNothingAndRunsNothing() {
		assertNull(UserCommand.fromMap(null));
		assertNull(UserCommand.fromMap(Map.of()));
		assertNull(UserCommand.fromMap(Map.of(UserCommand.WorkingDirectoryKey, "C:/tmp")));
	}

	@Test
	void anUnparseableStoredShortcutIsDroppedRatherThanFailingTheEntry() {
		var map = new HashMap<String, Object>();
		map.put(UserCommand.NameKey, "Odd");
		map.put(UserCommand.CommandKey, "echo hi");
		map.put(UserCommand.ShortcutKey, "not a key");

		UserCommand restored = UserCommand.fromMap(map);

		assertEquals("Odd", restored.name());
		assertNull(restored.shortcut());
	}
}
