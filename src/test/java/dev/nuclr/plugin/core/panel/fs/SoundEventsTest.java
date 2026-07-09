/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.nuclr.plugin.core.panel.fs.support.FakeContext;

class SoundEventsTest {

	@Test
	void emitsSoundEventsThroughThePluginEventBus() {
		FakeContext context = new FakeContext();

		SoundEvents.popup(context);
		SoundEvents.cancel(context);
		SoundEvents.confirmation(context);
		SoundEvents.error(context);
		SoundEvents.processComplete(context);
		SoundEvents.warning(context);

		assertEquals(1, context.eventBus.emissionsOfType("PopupSound").size());
		assertEquals(1, context.eventBus.emissionsOfType("CancelSound").size());
		assertEquals(1, context.eventBus.emissionsOfType("ConfirmationSound").size());
		assertEquals(1, context.eventBus.emissionsOfType("ErrorSound").size());
		assertEquals(1, context.eventBus.emissionsOfType("ProcessCompleteSound").size());
		assertEquals(1, context.eventBus.emissionsOfType("WarningSound").size());
	}

	@Test
	void ignoresMissingContext() {
		assertDoesNotThrow(() -> SoundEvents.popup(null));
	}
}
