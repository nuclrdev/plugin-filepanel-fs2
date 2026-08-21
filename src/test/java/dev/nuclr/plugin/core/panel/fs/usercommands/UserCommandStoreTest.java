/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.usercommands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.NuclrSettings;

/** Persisting the user-command list through the platform settings store. */
class UserCommandStoreTest {

	/**
	 * In-memory {@link NuclrSettings} standing in for the commander's JSON-backed one. Values
	 * are kept as handed over, which is what matters here: the store must write something the
	 * settings layer can serialise, and read back whatever shape it returns.
	 */
	private static final class FakeSettings implements NuclrSettings {

		private final Map<String, Object> values = new HashMap<>();
		private RuntimeException failure;

		@Override
		public void set(String namespace, String key, Object value) {
			if (failure != null) {
				throw failure;
			}
			values.put(namespace + "/" + key, value);
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T get(String namespace, String key) {
			if (failure != null) {
				throw failure;
			}
			return (T) values.get(namespace + "/" + key);
		}

		@Override
		public <T> T getOrDefault(String namespace, String key, T defaultValue) {
			T value = get(namespace, key);
			return value == null ? defaultValue : value;
		}

		@Override
		public boolean isDeveloperModeOn() {
			return false;
		}
	}

	private final FakeSettings settings = new FakeSettings();
	private final UserCommandStore store = new UserCommandStore(settings);

	private static UserCommand command(String name, String line, KeyStroke shortcut) {
		return new UserCommand(name, line, "", shortcut, false);
	}

	@Test
	void savesAndLoadsTheListInOrder() {
		var commands = List.of(
				command("Sources", "cd /nuclr/sources", KeyStroke.getKeyStroke(KeyEvent.VK_1, 0)),
				command("Git Status", "git status", KeyStroke.getKeyStroke(KeyEvent.VK_G, 0)),
				command("Maven Build", "mvn clean install", null));

		assertTrue(store.save(commands));

		assertEquals(commands, store.load(), "the list round-trips unchanged, order included");
	}

	@Test
	void writesUnderThePluginsOwnSettingsNamespace() {
		store.save(List.of(command("Git Status", "git status", null)));

		Object stored = settings.get(UserCommandStore.Namespace, UserCommandStore.Key);

		assertNotNull(stored, "the list must land in the plugin's namespace, not somewhere of its own");
		assertTrue(stored instanceof List<?>, "stored as a list of flat maps, so JSON can hold it");
	}

	@Test
	void anEmptyStoreYieldsAnEmptyList() {
		assertTrue(store.load().isEmpty());
	}

	@Test
	void savingAnEmptyListClearsTheStoredCommands() {
		store.save(List.of(command("Git Status", "git status", null)));

		assertTrue(store.save(List.of()));
		assertTrue(store.load().isEmpty());
	}

	@Test
	void loadsWhateverShapeTheSettingsLayerHandsBack() {
		// The commander deserialises JSON into plain maps, not into UserCommand instances.
		var raw = new ArrayList<Map<String, Object>>();
		var entry = new HashMap<String, Object>();
		entry.put(UserCommand.NameKey, "Git Status");
		entry.put(UserCommand.CommandKey, "git status");
		entry.put(UserCommand.ShortcutKey, "pressed G");
		entry.put(UserCommand.ConfirmationRequiredKey, Boolean.TRUE);
		raw.add(entry);
		settings.set(UserCommandStore.Namespace, UserCommandStore.Key, raw);

		List<UserCommand> loaded = store.load();

		assertEquals(1, loaded.size());
		assertEquals("Git Status", loaded.get(0).name());
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), loaded.get(0).shortcut());
		assertTrue(loaded.get(0).confirmationRequired());
	}

	@Test
	void skipsUnreadableEntriesRatherThanTheWholeList() {
		var raw = new ArrayList<Object>();
		raw.add("not a command");
		raw.add(Map.of(UserCommand.CommandKey, "git status"));
		raw.add(Map.of());
		settings.set(UserCommandStore.Namespace, UserCommandStore.Key, raw);

		List<UserCommand> loaded = store.load();

		assertEquals(1, loaded.size(), "the one readable entry survives its unreadable neighbours");
		assertEquals("git status", loaded.get(0).command());
	}

	@Test
	void aStoredValueOfTheWrongShapeReadsAsAnEmptyList() {
		settings.set(UserCommandStore.Namespace, UserCommandStore.Key, "someone hand-edited this");

		assertTrue(store.load().isEmpty(), "a corrupt preference must not stop the list opening");
	}

	@Test
	void aFailingSettingsStoreIsReportedRatherThanThrown() {
		settings.failure = new IllegalStateException("disk is full");

		assertFalse(store.save(List.of(command("Git Status", "git status", null))));
		assertTrue(store.load().isEmpty());
	}

	@Test
	void withoutASettingsStoreTheListSimplyIsNotPersisted() {
		var detached = new UserCommandStore(null);

		assertFalse(detached.save(List.of(command("Git Status", "git status", null))));
		assertTrue(detached.load().isEmpty());
	}
}
