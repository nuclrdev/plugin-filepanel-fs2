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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;

/**
 * The Shortcut field's key handling, exercised by dispatching key events straight at it. No
 * window is ever shown, so this runs under the build's headless surefire alongside everything
 * else — only the robot-driven dialog tests need a real desktop.
 */
class ShortcutFieldTest {

	private final List<KeyStroke> changes = new ArrayList<>();

	private ShortcutField field(Predicate<KeyStroke> accept) {
		return new ShortcutField(accept, changes::add);
	}

	/**
	 * Hand a key press straight to the field. {@code dispatchEvent} would go through the focus
	 * manager first, which drops key events aimed at a component that is not the focus owner —
	 * and nothing here is shown, let alone focused. This test lives in the field's own package
	 * precisely so it can reach the protected hook the field overrides.
	 */
	private static void press(ShortcutField field, int keyCode, char keyChar) {
		field.processKeyEvent(
				new KeyEvent(field, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, keyChar));
	}

	/** The key-typed event Swing sends after a printable press; it must never reach the document. */
	private static void type(ShortcutField field, char keyChar) {
		field.processKeyEvent(new KeyEvent(field, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0,
				KeyEvent.VK_UNDEFINED, keyChar));
	}

	@Test
	void aPressedKeyBecomesTheShortcutAndTheFieldShowsItsLabel() {
		ShortcutField field = field(key -> true);

		press(field, KeyEvent.VK_G, 'g');
		type(field, 'g');

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), field.getShortcut());
		assertEquals("G", field.getText(), "the field shows the key's label, not the character typed into it");
		assertEquals(List.of(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0)), changes);
	}

	@Test
	void aFunctionKeyIsCapturedTheSameWay() {
		ShortcutField field = field(key -> true);

		press(field, KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED);

		assertEquals("F5", field.getText());
	}

	@Test
	void aSecondKeyReplacesTheFirst() {
		ShortcutField field = field(key -> true);

		press(field, KeyEvent.VK_G, 'g');
		press(field, KeyEvent.VK_1, '1');

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), field.getShortcut());
		assertEquals("1", field.getText());
	}

	@Test
	void backspaceAndDeleteClearTheShortcut() {
		for (int clearingKey : new int[] { KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE }) {
			changes.clear();
			ShortcutField field = field(key -> true);
			field.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0));

			press(field, clearingKey, KeyEvent.CHAR_UNDEFINED);

			assertNull(field.getShortcut(), KeyEvent.getKeyText(clearingKey) + " should clear the shortcut");
			assertEquals("", field.getText());
			assertEquals(1, changes.size());
			assertNull(changes.get(0));
		}
	}

	@Test
	void aModifierPressedAloneNeitherCapturesNorClears() {
		ShortcutField field = field(key -> true);
		field.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0));

		press(field, KeyEvent.VK_SHIFT, KeyEvent.CHAR_UNDEFINED);
		press(field, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED);

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), field.getShortcut(),
				"reaching for Shift must not wipe what the field already holds");
		assertTrue(changes.isEmpty());
	}

	@Test
	void aVetoedKeyLeavesTheFieldAsItWas() {
		// This is how the dialog refuses a key another command already owns, at the moment the
		// user presses it rather than at save time.
		ShortcutField field = field(key -> false);
		field.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0));

		press(field, KeyEvent.VK_G, 'g');

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), field.getShortcut());
		assertEquals("R", field.getText());
		assertTrue(changes.isEmpty());
	}

	@Test
	void tabEnterAndEscapeAreLeftForTheDialogRatherThanCaptured() {
		ShortcutField field = field(key -> true);

		press(field, KeyEvent.VK_TAB, '\t');
		press(field, KeyEvent.VK_ENTER, '\n');
		press(field, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);

		assertNull(field.getShortcut(),
				"traversing out of the field, accepting the dialog and cancelling it must all keep working");
		assertTrue(changes.isEmpty());
	}

	@Test
	void theFieldIsNotTypedIntoDirectly() {
		ShortcutField field = field(key -> true);

		assertFalse(field.isEditable(), "the value is written by pressing a key, never by typing its name");
	}

	@Test
	void seedingAnExistingShortcutDoesNotCountAsAChange() {
		ShortcutField field = field(key -> true);

		field.setShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));

		assertEquals("F5", field.getText());
		assertTrue(changes.isEmpty(), "opening the Edit dialog is not the user changing the key");
	}
}
