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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyServiceTest {

	@Test
	void refreshPayloadUsesSharedKeyAndPreservesDistinctDirectories() {
		Map<String, Object> data = new HashMap<>();
		Path first = Path.of("first");
		Path second = Path.of("second");

		CopyService.putRefreshPaths(data, List.of(first, first, second));

		assertEquals(List.of(first, second),
				data.get(dev.nuclr.plugin.core.panel.fs.FilePanelPayloadKeys.RESULT_REFRESH_PATHS));
	}

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
