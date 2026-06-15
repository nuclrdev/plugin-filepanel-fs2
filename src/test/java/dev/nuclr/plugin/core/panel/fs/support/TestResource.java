/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs.support;

import java.nio.file.Path;

import dev.nuclr.platform.plugin.NuclrResource;

/**
 * A bare {@link NuclrResource} backed by an arbitrary (possibly {@code null})
 * {@link Path}. Unlike {@code FileNuclrResource} it performs no filesystem I/O in
 * its constructor, so it is the resource of choice for testing null-path and
 * non-existent-path branches. {@code uuid} defaults to the path string so the
 * inherited equals/hashCode (which key on uuid) never NPE.
 */
public final class TestResource extends NuclrResource {

	private static final long serialVersionUID = 1L;

	public TestResource(Path path) {
		super(path);
		String id = path != null ? path.toString() : "null-resource";
		setUuid(id);
		setName(path != null && path.getFileName() != null ? path.getFileName().toString() : id);
		if (path != null) {
			setFullPath(path.toString());
		}
	}

	public TestResource(Path path, String name, boolean folder) {
		this(path);
		setName(name);
		setFolder(folder);
	}
}
