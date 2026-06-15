/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.plugin.core.panel.fs.support.FakeContext;

/**
 * Unit tests for {@link FileNuclrResource}, the per-entry resource whose constructor
 * is the single source of truth for name/size/folder/timestamp/metadata population.
 */
class FileNuclrResourceTest {

	private final FakeContext ctx = new FakeContext();

	@Test
	void regularFile_populatesNameSizePathAndMetadata() throws Exception {
		Path file = Files.createTempFile("nuclr", ".txt");
		try {
			byte[] data = "hello world".getBytes(StandardCharsets.UTF_8); // 11 bytes
			Files.write(file, data);

			FileNuclrResource r = new FileNuclrResource(ctx, file);

			assertEquals(file.getFileName().toString(), r.getName());
			assertEquals(file.toAbsolutePath().normalize().toString(), r.getFullPath());
			assertEquals(file.toAbsolutePath().toString(), r.getUuid());
			assertFalse(r.isFolder());
			assertEquals(data.length, r.getLength());
			assertEquals(r.getName(), r.getMetadata().get("Name"));
			assertEquals(FileUtils.byteCountToDisplaySize(data.length), r.getMetadata().get("Size"));
			assertNotNull(r.getMetadata().get("Date"));
			assertNotNull(r.getMetadata().get("Time"));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void directory_isFolderWithZeroLengthAndFolderSizeLabel(@TempDir Path dir) {
		FileNuclrResource r = new FileNuclrResource(ctx, dir);

		assertTrue(r.isFolder());
		assertEquals(0L, r.getLength());
		assertEquals("Folder", r.getMetadata().get("Size"));
	}

	@Test
	void timestamps_arePopulatedFromTheFilesystem(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("f.bin");
		Files.write(file, new byte[] { 1, 2, 3 });

		FileNuclrResource r = new FileNuclrResource(ctx, file);

		LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
		assertNotNull(r.getLastModifiedDateTime());
		assertNotNull(r.getCreatedDateTime());
		assertNotNull(r.getLastAccessDateTime());
		// A freshly written file is well after the epoch sentinel used for unreadable entries.
		assertTrue(r.getLastModifiedDateTime().isAfter(epoch));
	}

	@Test
	void nonExistentPath_fallsBackToSafeDefaults(@TempDir Path dir) {
		Path missing = dir.resolve("does-not-exist");

		FileNuclrResource r = new FileNuclrResource(ctx, missing);

		// attrs == null branch: never dropped from the listing, just safe defaults.
		assertFalse(r.isFolder());
		assertEquals(0L, r.getLength());
		assertFalse(r.isLink());
		assertFalse(r.isSystem());
		assertEquals(LocalDateTime.of(1970, 1, 1, 0, 0), r.getLastModifiedDateTime());
		assertEquals("does-not-exist", r.getName());
	}

	@Test
	void setName_keepsNameMetadataInSync(@TempDir Path dir) {
		FileNuclrResource r = new FileNuclrResource(ctx, dir);

		r.setName("renamed");

		assertEquals("renamed", r.getName());
		assertEquals("renamed", r.getMetadata().get("Name"));
	}

	@Test
	void openInputStream_readsFileContent(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("content.txt");
		Files.writeString(file, "payload");

		FileNuclrResource r = new FileNuclrResource(ctx, file);

		try (InputStream in = r.openInputStream()) {
			assertEquals("payload", new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@Test
	void columnNames_areTheFourDisplayedColumns() {
		assertEquals(java.util.List.of("Name", "Size", "Date", "Time"), FileNuclrResource.ColumnNames);
	}

	@Test
	void readableDefaultsTrue_isNotProbedAtConstruction(@TempDir Path dir) {
		// The class deliberately does NOT probe Files.isReadable; the flag stays true.
		FileNuclrResource r = new FileNuclrResource(ctx, dir);
		assertTrue(r.isReadable());
	}
}
