/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyServiceTest {

	@Test
	void regularFilesFiltersFoldersMissingPathsNullsAndDuplicates(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("file.txt"), "content");
		Path folder = Files.createDirectory(dir.resolve("folder"));

		List<Path> result = CopyService.regularFiles(Arrays.asList(
				file,
				folder,
				dir.resolve("missing.txt"),
				null,
				file));

		assertEquals(List.of(file.toAbsolutePath().normalize()), result);
	}

	@Test
	void regularFilesHandlesAnEmptyClipboard() {
		assertEquals(List.of(), CopyService.regularFiles(null));
		assertEquals(List.of(), CopyService.regularFiles(List.of()));
	}
}
