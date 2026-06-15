/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Alerts}. Run headless (see surefire config) so that
 * {@code showError} resolves its dialog to a {@code HeadlessException} that the
 * helper swallows, rather than blocking the build on a modal window.
 */
class AlertsTest {

	@Test
	void runOnEdtAndWait_executesRunnableOnTheEdt() throws Exception {
		assertFalse(SwingUtilities.isEventDispatchThread(), "precondition: test runs off the EDT");
		AtomicBoolean ran = new AtomicBoolean(false);
		AtomicBoolean wasEdt = new AtomicBoolean(false);

		Alerts.runOnEdtAndWait(() -> {
			ran.set(true);
			wasEdt.set(SwingUtilities.isEventDispatchThread());
		});

		assertTrue(ran.get(), "runnable must have executed");
		assertTrue(wasEdt.get(), "runnable must execute on the EDT");
	}

	@Test
	void runOnEdtAndWait_runsInlineWhenAlreadyOnEdt() throws Exception {
		AtomicBoolean ranInline = new AtomicBoolean(false);

		SwingUtilities.invokeAndWait(() -> {
			Thread before = Thread.currentThread();
			Alerts.runOnEdtAndWait(() -> ranInline.set(Thread.currentThread() == before));
		});

		assertTrue(ranInline.get(), "when already on the EDT the runnable runs on the same thread");
	}

	@Test
	void showError_doesNotThrowWhenHeadless() {
		// HeadlessException is raised inside the dialog and caught/logged by the helper.
		assertDoesNotThrow(() -> Alerts.showError("Title", "Message"));
	}
}
