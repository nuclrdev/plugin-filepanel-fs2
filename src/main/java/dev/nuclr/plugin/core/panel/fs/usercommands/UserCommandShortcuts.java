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

import java.awt.event.KeyEvent;
import java.util.Set;

import javax.swing.KeyStroke;

/**
 * The key half of a user command: capturing one from a keypress, showing it, storing it, and
 * saying which keys are not on offer.
 *
 * <p>User-command keys live only while the F2 list is open, so there is no application-wide
 * shortcut to collide with and a bare letter or digit is the natural choice. What they can
 * still collide with is the list's own keyboard: the eight keys it needs for running,
 * adding, editing, deleting, closing and moving the selection. Those are refused here rather
 * than silently shadowed, because a key that both runs a command and moves the cursor would
 * be a list the user cannot navigate.
 *
 * <p>{@link KeyStroke} is the stored representation throughout — it is what the dialog's
 * input maps take, and its {@code toString} round-trips through
 * {@link KeyStroke#getKeyStroke(String)}, so persistence needs no key-name table of its own.
 */
public final class UserCommandShortcuts {

	/**
	 * Keys the F2 list needs for itself. Enter/Insert/F4/Delete/Escape are its five commands;
	 * the arrows, paging and Home/End move the selection; Tab traverses; Backspace clears the
	 * capture field. Assigning any of them would take the list's own keyboard away.
	 */
	private static final Set<Integer> ReservedKeyCodes = Set.of(
			KeyEvent.VK_ENTER,
			KeyEvent.VK_ESCAPE,
			KeyEvent.VK_INSERT,
			KeyEvent.VK_DELETE,
			KeyEvent.VK_F4,
			KeyEvent.VK_TAB,
			KeyEvent.VK_BACK_SPACE,
			KeyEvent.VK_SPACE,
			KeyEvent.VK_UP,
			KeyEvent.VK_DOWN,
			KeyEvent.VK_LEFT,
			KeyEvent.VK_RIGHT,
			KeyEvent.VK_PAGE_UP,
			KeyEvent.VK_PAGE_DOWN,
			KeyEvent.VK_HOME,
			KeyEvent.VK_END);

	private UserCommandShortcuts() {
	}

	/**
	 * Turn a keypress into the shortcut it means, or {@code null} when it means nothing on its
	 * own — a modifier held down by itself, or an unidentifiable key.
	 *
	 * <p>Modifiers are dropped rather than recorded: these keys are matched inside one dialog
	 * where a plain key is unambiguous, and Ctrl/Alt combinations there would only invite
	 * confusion with the commander's own bindings.
	 *
	 * @param event the key-pressed event to interpret
	 * @return the shortcut, or {@code null} if this keypress cannot be one
	 */
	public static KeyStroke capture(KeyEvent event) {
		if (event == null) {
			return null;
		}
		int code = event.getKeyCode();
		if (code == KeyEvent.VK_UNDEFINED || isModifierOnly(code)) {
			return null;
		}
		return KeyStroke.getKeyStroke(code, 0);
	}

	/** Whether {@code code} is a modifier that carries no meaning pressed on its own. */
	public static boolean isModifierOnly(int code) {
		return code == KeyEvent.VK_SHIFT
				|| code == KeyEvent.VK_CONTROL
				|| code == KeyEvent.VK_ALT
				|| code == KeyEvent.VK_ALT_GRAPH
				|| code == KeyEvent.VK_META;
	}

	/**
	 * Whether this key is the F2 list's own and therefore cannot be handed to a command.
	 *
	 * @param shortcut the candidate key; {@code null} counts as free (no shortcut at all)
	 * @return {@code true} if the list reserves it
	 */
	public static boolean isReserved(KeyStroke shortcut) {
		return shortcut != null && ReservedKeyCodes.contains(shortcut.getKeyCode());
	}

	/**
	 * The label for a shortcut as the Key column and the capture field show it: {@code "G"},
	 * {@code "1"}, {@code "F5"}.
	 *
	 * @param shortcut the key, or {@code null}
	 * @return the label, or an empty string when there is no shortcut
	 */
	public static String label(KeyStroke shortcut) {
		return shortcut == null ? "" : KeyEvent.getKeyText(shortcut.getKeyCode());
	}

	/**
	 * The persisted form of a shortcut — Swing's own {@code "pressed G"} syntax, so
	 * {@link #parse} can hand it straight back to {@link KeyStroke#getKeyStroke(String)}.
	 *
	 * @param shortcut the key, or {@code null}
	 * @return the stored text, or an empty string when there is no shortcut
	 */
	public static String store(KeyStroke shortcut) {
		return shortcut == null ? "" : shortcut.toString();
	}

	/**
	 * Read a shortcut back from its persisted form, ignoring anything Swing cannot parse or
	 * that the list has since reserved.
	 *
	 * @param stored text written by {@link #store}; may be {@code null} or blank
	 * @return the shortcut, or {@code null} when there is none to restore
	 */
	public static KeyStroke parse(String stored) {
		if (stored == null || stored.isBlank()) {
			return null;
		}
		KeyStroke shortcut = KeyStroke.getKeyStroke(stored.trim());
		if (shortcut == null || shortcut.getKeyCode() == KeyEvent.VK_UNDEFINED) {
			return null;
		}
		// Normalise to a key-press with no modifiers: that is the only shape assigned here, and
		// a released/modified stroke read from a hand-edited file would never match anything.
		return KeyStroke.getKeyStroke(shortcut.getKeyCode(), 0);
	}
}
