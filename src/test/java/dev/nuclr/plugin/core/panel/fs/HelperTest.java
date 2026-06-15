/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.FakeContext;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/** Unit tests for the package-private {@link Helper} utility. */
class HelperTest {

	private final FakeContext ctx = new FakeContext();

	@Test
	void copy_deepClonesResourcePreservingIdentity() {
		TestResource original = new TestResource(null);
		original.setUuid("uuid-123");
		original.setName("thing");
		original.getMetadata().put("k", "v");

		NuclrResource clone = Helper.copy(original);

		assertNotSame(original, clone, "copy must be a distinct instance");
		assertEquals(original, clone, "equality keys on uuid, so the clone is equal");
		assertEquals("thing", clone.getName());
		assertEquals("v", clone.getMetadata().get("k"));
		// Deep clone: mutating the original's metadata must not leak into the clone.
		original.getMetadata().put("k", "changed");
		assertEquals("v", clone.getMetadata().get("k"));
	}

	@Test
	void build_createsFileResourceForDirectory(@TempDir Path dir) {
		NuclrResource r = Helper.build(ctx, dir);

		assertTrue(r instanceof FileNuclrResource);
		assertTrue(r.isFolder());
		assertEquals(dir.toAbsolutePath().normalize().toString(), r.getFullPath());
	}

	@Test
	void revealInFileManager_rejectsNullPath() {
		assertThrows(NullPointerException.class, () -> Helper.revealInFileManager(null));
	}
}
