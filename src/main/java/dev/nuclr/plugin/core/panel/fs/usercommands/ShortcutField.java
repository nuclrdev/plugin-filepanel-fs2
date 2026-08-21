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
import java.util.function.Consumer;

import javax.swing.KeyStroke;

import com.formdev.flatlaf.extras.components.FlatTextField;

/**
 * The Shortcut field: a text field that is written by pressing a key rather than by typing a
 * key's name. While it has focus, one keypress becomes the shortcut and the field shows that
 * key's label — {@code G}, {@code 1}, {@code F5}.
 *
 * <p>Backspace and Delete clear it, which is why {@link UserCommandShortcuts} refuses to
 * assign those two: here they mean "no shortcut", so they could never be captured anyway.
 * Modifiers pressed alone are ignored rather than cleared, so holding Shift while reaching
 * for a key does not wipe the field.
 *
 * <p>Three keys are handed straight on rather than captured, so the dialog around the field
 * keeps behaving like a dialog: Tab traverses out of it, Enter accepts it, Escape cancels it.
 * All three are reserved from assignment anyway, so nothing is lost by never offering them.
 */
public class ShortcutField extends FlatTextField {

	private static final long serialVersionUID = 1L;

	/** Keys that clear the field instead of being captured into it. */
	private static final Set<Integer> ClearingKeys = Set.of(KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE);

	/**
	 * Keys that must reach the dialog rather than the field: OK, Cancel and traversal. (Tab is
	 * normally consumed by the focus system before the field ever sees it; it is listed for the
	 * case where it is not.)
	 */
	private static final Set<Integer> PassThroughKeys =
			Set.of(KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE, KeyEvent.VK_ENTER);

	private transient KeyStroke shortcut;

	/** Notified after every accepted change, with the new value or {@code null} once cleared. */
	private final transient Consumer<KeyStroke> onChange;

	/**
	 * Vetoes a capture: given the key just pressed, returns {@code true} to take it. Lets the
	 * dialog reject a duplicate or reserved key and explain why, before the field shows it.
	 */
	private final transient java.util.function.Predicate<KeyStroke> accept;

	/**
	 * @param accept   consulted before a captured key is shown; may be {@code null} to take any
	 * @param onChange notified after each accepted change; may be {@code null}
	 */
	public ShortcutField(java.util.function.Predicate<KeyStroke> accept, Consumer<KeyStroke> onChange) {
		this.accept = accept;
		this.onChange = onChange;

		setEditable(false);
		setColumns(6);
		setPlaceholderText("Press a key");
		setToolTipText("Press a key to assign it. Backspace or Delete clears it.");
	}

	/**
	 * The captured shortcut.
	 *
	 * @return the key, or {@code null} when none is assigned
	 */
	public KeyStroke getShortcut() {
		return shortcut;
	}

	/**
	 * Set the shortcut programmatically — used to seed the field when editing an existing
	 * command. Does not consult {@code accept} and does not fire {@code onChange}.
	 *
	 * @param shortcut the key to show, or {@code null} for none
	 */
	public void setShortcut(KeyStroke shortcut) {
		this.shortcut = shortcut;
		setText(UserCommandShortcuts.label(shortcut));
	}

	@Override
	protected void processKeyEvent(KeyEvent event) {

		int code = event.getKeyCode();

		if (PassThroughKeys.contains(code)) {
			super.processKeyEvent(event);
			return;
		}

		// Only act on the press; the matching release and typed events are swallowed so the
		// character never lands in the field's document.
		if (event.getID() != KeyEvent.KEY_PRESSED) {
			event.consume();
			return;
		}

		if (ClearingKeys.contains(code)) {
			apply(null);
			event.consume();
			return;
		}

		KeyStroke captured = UserCommandShortcuts.capture(event);
		if (captured == null) {
			// A modifier on its own: nothing to capture, and nothing to clear either.
			event.consume();
			return;
		}

		if (accept == null || accept.test(captured)) {
			apply(captured);
		}
		event.consume();
	}

	private void apply(KeyStroke captured) {
		setShortcut(captured);
		if (onChange != null) {
			onChange.accept(captured);
		}
	}
}
