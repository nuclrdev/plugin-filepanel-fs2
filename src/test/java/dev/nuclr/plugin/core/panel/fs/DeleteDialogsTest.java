/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

class DeleteDialogsTest {

	private static final List<NuclrResource> FILE = List.of(new TestResource(Path.of("example.txt")));

	@Test
	void safeConfirmationExplainsThatDeletionIsRecoverable() {
		var content = DeleteDialogs.confirmationContent(FILE, false);

		assertEquals("Move to Trash", content.title());
		assertEquals("Move to Trash", content.proceedText());
		assertTrue(content.message().contains("Trash or Recycle Bin"));
		assertTrue(content.message().contains("restore"));
		assertFalse(content.message().contains("cannot be undone"));
	}

	@Test
	void permanentConfirmationWarnsThatDeletionCannotBeUndone() {
		var content = DeleteDialogs.confirmationContent(FILE, true);

		assertEquals("Delete Permanently", content.title());
		assertEquals("Delete Permanently", content.proceedText());
		assertTrue(content.message().contains("Permanently delete"));
		assertTrue(content.message().contains("cannot be undone"));
		assertFalse(content.message().contains("restore"));
	}
}
