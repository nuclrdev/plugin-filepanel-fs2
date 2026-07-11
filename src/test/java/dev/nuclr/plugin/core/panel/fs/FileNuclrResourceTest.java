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
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

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
			assertEquals("txt", r.getMetadata().get("Extension"));
			assertEquals(FileUtils.byteCountToDisplaySize(data.length), r.getMetadata().get("Size"));
			assertEquals("File", r.getMetadata().get("Type"));
			assertNotNull(r.getMetadata().get("Modified"));
			assertNotNull(r.getMetadata().get("Created"));
			assertNotNull(r.getMetadata().get("Accessed"));
			assertNotNull(r.getMetadata().get("Attributes"));
			assertEquals(r.getFullPath(), r.getMetadata().get("Full Path"));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void directory_isFolderWithZeroLengthAndBlankSizeAndExtensionLabels(@TempDir Path dir) throws Exception {
		Path dotted = Files.createDirectory(dir.resolve("release.v1"));

		FileNuclrResource r = new FileNuclrResource(ctx, dotted);

		assertTrue(r.isFolder());
		assertEquals(0L, r.getLength());
		assertEquals("", r.getMetadata().get("Size"));
		assertEquals("", r.getMetadata().get("Extension"));
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
	void dateTimeMetadata_usesRequestedLocale() {
		LocalDateTime date = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

		String us = FileNuclrResource.formatDateTime(date, Locale.US);
		String germany = FileNuclrResource.formatDateTime(date, Locale.GERMANY);

		assertEquals(localized(date, Locale.US), us);
		assertEquals(localized(date, Locale.GERMANY), germany);
		assertFalse(us.equals(germany));
		assertEquals("-", FileNuclrResource.formatDateTime(null, Locale.GERMANY));
	}

	@Test
	void constructorFormatsDateMetadataWithContextLocale(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("localized.txt");
		Files.writeString(file, "content");

		FileNuclrResource r = new FileNuclrResource(new FakeContext(Locale.GERMANY), file);

		assertEquals(
				FileNuclrResource.formatDateTime(r.getLastModifiedDateTime(), Locale.GERMANY),
				r.getMetadata().get("Modified"));
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
	void columnNamesFor_returnsTheColumnsAvailableForTheResource() {
		assertEquals(java.util.List.of(
				"Name",
				"Extension",
				"Size",
				"Type",
				"Modified",
				"Created",
				"Accessed",
				"Attributes",
				"Full Path"), FileNuclrResource.ColumnNames);
		assertEquals(FileNuclrResource.ColumnNames, FileNuclrResource.columnNamesFor(null));
	}

	@Test
	void readableDefaultsTrue_isNotProbedAtConstruction(@TempDir Path dir) {
		// The class deliberately does NOT probe Files.isReadable; the flag stays true.
		FileNuclrResource r = new FileNuclrResource(ctx, dir);
		assertTrue(r.isReadable());
	}

	private static String localized(LocalDateTime date, Locale locale) {
		return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)
				.withLocale(locale)
				.format(date);
	}
}
