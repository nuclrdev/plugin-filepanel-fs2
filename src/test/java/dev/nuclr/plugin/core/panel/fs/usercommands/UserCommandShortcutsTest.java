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

import java.awt.Component;
import java.awt.event.KeyEvent;

import javax.swing.JPanel;
import javax.swing.KeyStroke;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Capturing, labelling, storing and reserving user-command shortcut keys. */
class UserCommandShortcutsTest {

	/** A source component for the synthetic key events; never shown, so headless is fine. */
	private final Component source = new JPanel();

	private KeyEvent press(int keyCode, char keyChar, int modifiers) {
		return new KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, keyChar);
	}

	@Test
	void capturesALetterAsAPlainKeyStroke() {
		KeyStroke captured = UserCommandShortcuts.capture(press(KeyEvent.VK_G, 'g', 0));

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), captured);
		assertEquals("G", UserCommandShortcuts.label(captured));
	}

	@Test
	void capturesADigitAndAFunctionKey() {
		assertEquals("1", UserCommandShortcuts.label(UserCommandShortcuts.capture(press(KeyEvent.VK_1, '1', 0))));
		assertEquals("F5", UserCommandShortcuts.label(
				UserCommandShortcuts.capture(press(KeyEvent.VK_F5, KeyEvent.CHAR_UNDEFINED, 0))));
	}

	@ParameterizedTest
	@ValueSource(ints = { KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META,
			KeyEvent.VK_ALT_GRAPH })
	void refusesAModifierPressedOnItsOwn(int modifierKeyCode) {
		assertNull(UserCommandShortcuts.capture(press(modifierKeyCode, KeyEvent.CHAR_UNDEFINED, 0)),
				"a modifier alone is not a shortcut");
		assertTrue(UserCommandShortcuts.isModifierOnly(modifierKeyCode));
	}

	@Test
	void dropsHeldModifiersRatherThanRecordingThem() {
		// Shift+G is stored as G: these keys are matched inside one dialog, where a plain key is
		// unambiguous, and a modified stroke would never match the plain press that follows.
		KeyStroke captured = UserCommandShortcuts.capture(press(KeyEvent.VK_G, 'G', KeyEvent.SHIFT_DOWN_MASK));

		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), captured);
	}

	@Test
	void refusesAnUnidentifiableKeyAndANullEvent() {
		assertNull(UserCommandShortcuts.capture(null));
		assertNull(UserCommandShortcuts.capture(press(KeyEvent.VK_UNDEFINED, 'x', 0)));
	}

	@ParameterizedTest
	@ValueSource(ints = { KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_INSERT, KeyEvent.VK_DELETE,
			KeyEvent.VK_F4, KeyEvent.VK_TAB, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_SPACE, KeyEvent.VK_UP,
			KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_PAGE_UP, KeyEvent.VK_PAGE_DOWN,
			KeyEvent.VK_HOME, KeyEvent.VK_END })
	void reservesTheKeysTheListNeedsForItself(int keyCode) {
		assertTrue(UserCommandShortcuts.isReserved(KeyStroke.getKeyStroke(keyCode, 0)),
				KeyEvent.getKeyText(keyCode) + " belongs to the User Commands list");
	}

	@ParameterizedTest
	@ValueSource(ints = { KeyEvent.VK_G, KeyEvent.VK_1, KeyEvent.VK_F5, KeyEvent.VK_F2, KeyEvent.VK_R })
	void leavesOrdinaryKeysFree(int keyCode) {
		assertFalse(UserCommandShortcuts.isReserved(KeyStroke.getKeyStroke(keyCode, 0)));
	}

	@Test
	void noShortcutIsNeverReservedAndHasNoLabel() {
		assertFalse(UserCommandShortcuts.isReserved(null));
		assertEquals("", UserCommandShortcuts.label(null));
		assertEquals("", UserCommandShortcuts.store(null));
	}

	@Test
	void storedFormRoundTripsThroughSwing() {
		KeyStroke original = KeyStroke.getKeyStroke(KeyEvent.VK_G, 0);

		assertEquals(original, UserCommandShortcuts.parse(UserCommandShortcuts.store(original)));
	}

	@Test
	void parseIgnoresBlankAndUnparseableText() {
		assertNull(UserCommandShortcuts.parse(null));
		assertNull(UserCommandShortcuts.parse("   "));
		assertNull(UserCommandShortcuts.parse("definitely not a keystroke"));
	}

	@Test
	void parseNormalisesAHandEditedStrokeToAPlainPress() {
		// A released or modified stroke in the settings file would never match a plain press;
		// normalising means a hand-edited entry still works rather than silently doing nothing.
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), UserCommandShortcuts.parse("released G"));
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), UserCommandShortcuts.parse("ctrl pressed G"));
	}
}
