/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CreateFileServiceTest {

	@Test
	void createsAZeroByteFile(@TempDir Path directory) throws Exception {
		Path created = CreateFileService.create(directory, " README.md ", null);

		assertEquals(directory.resolve("README.md"), created);
		assertTrue(Files.isRegularFile(created));
		assertEquals(0L, Files.size(created));
	}

	@Test
	void rejectsTraversalAndExistingEntries(@TempDir Path directory) throws Exception {
		Files.createFile(directory.resolve("exists.txt"));

		assertThrows(IllegalArgumentException.class,
				() -> CreateFileService.create(directory, "../escape.txt", null));
		assertThrows(IllegalArgumentException.class,
				() -> CreateFileService.create(directory, "exists.txt", null));
		assertFalse(Files.exists(directory.getParent().resolve("escape.txt")));
	}

	@Test
	void validatesSingleFileNames() {
		assertTrue(CreateFileService.isInvalidSingleFileName("."));
		assertTrue(CreateFileService.isInvalidSingleFileName(".."));
		assertTrue(CreateFileService.isInvalidSingleFileName("a/b"));
		assertTrue(CreateFileService.isInvalidSingleFileName("a\\b"));
		assertFalse(CreateFileService.isInvalidSingleFileName("README.md"));
	}
}
