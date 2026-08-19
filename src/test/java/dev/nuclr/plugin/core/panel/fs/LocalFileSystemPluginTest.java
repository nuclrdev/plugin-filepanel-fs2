/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.panel.fs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.MenuItem;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin.NuclrResourceData;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.support.FakeContext;
import dev.nuclr.plugin.core.panel.fs.support.RecordingFilePanelPlugin;
import dev.nuclr.plugin.core.panel.fs.support.RecordingLocalFileSystemPlugin;
import dev.nuclr.plugin.core.panel.fs.support.TestResource;

/** Integration tests for the {@link LocalFileSystemPlugin} façade. */
class LocalFileSystemPluginTest {

	private FakeContext ctx;

	private LocalFileSystemPlugin newPlugin() {
		ctx = new FakeContext();
		LocalFileSystemPlugin p = new LocalFileSystemPlugin();
		p.preinit(ctx);
		p.init();
		return p;
	}

	/** Collects the streaming output of the {@code openResource} EntrySink. */
	private static final class CollectingSink implements FilePanelNuclrPlugin.EntrySink {
		List<String> columns;
		int columnsCalls = 0;
		final List<NuclrResource> added = new ArrayList<>();

		@Override
		public void columns(List<String> columnNames) {
			columns = columnNames;
			columnsCalls++;
		}

		@Override
		public void add(NuclrResource entry) {
			added.add(entry);
		}
	}

	// ---------------------------------------------------------------- metadata

	@Test
	void identityConstant_isStable() {
		LocalFileSystemPlugin p = new LocalFileSystemPlugin();
		assertEquals("dev.nuclr.plugin.core.panel.fs", LocalFileSystemPlugin.PluginId);
		assertFalse(p.isMessageSupported("anything"));
	}

	/**
	 * Since SDK 4.0.0 the descriptive metadata lives in {@code plugin.json} rather
	 * than on the plugin class, so that manifest is what has to stay in step with
	 * the code.
	 */
	@Test
	void manifest_declaresThisPluginWithASemverVersion() throws Exception {
		String manifest;
		try (var stream = LocalFileSystemPlugin.class.getResourceAsStream("/plugin.json")) {
			assertNotNull(stream, "plugin.json should be on the classpath");
			manifest = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}

		assertTrue(manifest.contains('"' + LocalFileSystemPlugin.class.getName() + '"'),
			"plugin.json should list " + LocalFileSystemPlugin.class.getName());
		assertTrue(manifest.contains('"' + LocalFileSystemPlugin.PluginId + '"'),
			"plugin.json should declare id " + LocalFileSystemPlugin.PluginId);

		var version = java.util.regex.Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(manifest);
		assertTrue(version.find(), "plugin.json should declare a version");
		assertTrue(version.group(1).matches("\\d+\\.\\d+\\.\\d+.*"),
			"expected a semver-like version, was " + version.group(1));
	}

	@Test
	void uuid_isStablePerInstanceAndUniqueAcrossInstances() {
		LocalFileSystemPlugin a = new LocalFileSystemPlugin();
		LocalFileSystemPlugin b = new LocalFileSystemPlugin();
		assertEquals(a.uuid(), a.uuid());
		assertNotEquals(a.uuid(), b.uuid());
	}

	// ------------------------------------------------------------- lifecycle

	@Test
	void init_subscribesAndUnloadUnsubscribes() {
		LocalFileSystemPlugin p = newPlugin();
		assertEquals(1, ctx.eventBus.subscribeCount);
		assertTrue(ctx.eventBus.listeners.contains(p));

		p.unload();
		assertEquals(1, ctx.eventBus.unsubscribeCount);
		assertFalse(ctx.eventBus.listeners.contains(p));
	}

	@Test
	void preinit_seedsCurrentFolderWithTheRoot() {
		LocalFileSystemPlugin p = newPlugin();
		NuclrResource current = p.getCurrentResource();
		assertNotNull(current);
		assertNotNull(current.getPath());
		assertEquals(String.valueOf(current.getPath()), p.getCurrentLocationDisplayText());
		assertEquals(current.getPath().toAbsolutePath().toString(), p.getWindowTitle());
	}

	@Test
	void focus_flagTracksGainedAndLost() {
		LocalFileSystemPlugin p = newPlugin();
		assertFalse(p.isFocused());
		assertTrue(p.onFocusGained());
		assertTrue(p.isFocused());
		p.onFocusLost();
		assertFalse(p.isFocused());
	}

	// ------------------------------------------------------------------ menus

	@Test
	void menuItems_useMoveForDirectoriesAndRenameMoveForFiles(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Path file = dir.resolve("a.txt");
		Files.writeString(file, "x");

		// Built the way the panel builds its entries: menuItems() runs on the EDT (the sort
		// header renderer asks for it during layout), so it reads the attributes the entry
		// already carries instead of stat-ing the path. A bare path-only stub would not
		// describe a real listing entry.
		List<NuclrMenuResource> forDir = p.menuItems(Helper.build(ctx, dir));
		List<NuclrMenuResource> forFile = p.menuItems(Helper.build(ctx, file));

		assertEquals("Move", labelFor(forDir, "F6"));
		assertEquals("Rename/Move", labelFor(forFile, "F6"));
		// Core function keys are always present with their event types.
		assertEquals("filepanel.view", eventFor(forDir, "F3"));
		assertEquals("filepanel.copy", eventFor(forDir, "F5"));
		assertEquals("filepanel.makeFolder", eventFor(forDir, "F7"));
		assertEquals("filepanel.delete", eventFor(forDir, "F8"));
		// Alt + Shift extras.
		assertTrue(forDir.stream().anyMatch(m -> "Alt+F7".equals(m.getFunctionKey())));
		assertTrue(forDir.stream().anyMatch(m -> "Shift+F8".equals(m.getFunctionKey())
				&& "deletePermanent".equals(m.getEventType())));
	}

	@Test
	void genericDeleteActionUsesTheSameDeleteRouteAsF8() {
		assertTrue(LocalFileSystemPlugin.isDeleteAction(PluginActions.DELETE));
		assertTrue(LocalFileSystemPlugin.isDeleteAction("filepanel.delete"));
		assertFalse(LocalFileSystemPlugin.isPermanentDeleteAction(PluginActions.DELETE));
		assertFalse(LocalFileSystemPlugin.isDeleteAction("delete.unsupported"));
	}

	@Test
	void genericPermanentDeleteAndShiftF8UseThePermanentDeleteRoute() {
		assertTrue(LocalFileSystemPlugin.isDeleteAction(PluginActions.DELETE_PERMANENT));
		assertTrue(LocalFileSystemPlugin.isPermanentDeleteAction(PluginActions.DELETE_PERMANENT));
		assertTrue(LocalFileSystemPlugin.isPermanentDeleteAction("deletePermanent"));
		assertTrue(LocalFileSystemPlugin.isPermanentDeleteAction("filepanel.deletePermanent"));
	}

	@Test
	void contextMenuItems_areOpenRevealCopySeparatorDelete() {
		LocalFileSystemPlugin p = newPlugin();
		List<NuclrContextMenuItem> items = p.contextMenuItems(null, List.of());

		assertEquals(7, items.size());
		assertEquals("filepanel.path.opened", items.get(0).getActionType());
		assertEquals("filepanel.path.open.in.explorer", items.get(1).getActionType());
		assertTrue(items.get(2).isSeparator());
		assertEquals("Copy file(s)", items.get(3).getLabel());
		assertEquals("clipboard.copy.files", items.get(3).getActionType());
		assertEquals("Copy full path(s)", items.get(4).getLabel());
		assertEquals("clipboard.copy.fullPaths", items.get(4).getActionType());
		assertTrue(items.get(5).isSeparator());
		assertEquals("filepanel.delete", items.get(6).getActionType());
	}

	@Test
	void sortMenuColumnBackedOptionsMatchExposedColumns(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		List<NuclrMenuResource> items = p.menuItems(new TestResource(dir));

		assertEquals("Modified", labelFor(items, "Ctrl+F5"));
		assertEquals("Created", labelFor(items, "Ctrl+F8"));
		assertEquals("Accessed", labelFor(items, "Ctrl+F9"));

		for (NuclrMenuResource item : items) {
			String eventType = item.getEventType();
			if (eventType == null || !eventType.startsWith("filepanel.sort:")) {
				continue;
			}

			String sort = eventType.substring("filepanel.sort:".length());
			if ("unsorted".equals(sort) || "dialog".equals(sort)) {
				continue;
			}

			String[] parts = sort.split(":", 2);
			assertEquals(2, parts.length, "column-backed sort events must name their column: " + eventType);
			assertTrue(FileNuclrResource.ColumnNames.contains(parts[1]), "sort column is not exposed: " + eventType);
			assertEquals(parts[1], item.getName(), "sort label should match its exposed column");
		}
	}

	@Test
	void getPluginMenuItems_listsFilesystemRoots() {
		LocalFileSystemPlugin p = newPlugin();
		var holder = p.getPluginMenuItems();

		assertEquals("Local Filesystem", holder.getTitle());
		assertFalse(holder.getMenuItems().isEmpty());
		for (MenuItem item : holder.getMenuItems()) {
			assertNotNull(item.getPath());
			assertEquals(LocalFileSystemPlugin.PluginId + ":" + item.getText(), item.getUuid());
		}
	}

	// --------------------------------------------------------------- supports

	@Test
	void supports_acceptsReadableDirectoriesAndRejectsTheRest(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Path file = dir.resolve("f.txt");
		Files.writeString(file, "x");

		assertTrue(p.supports(new TestResource(dir)));
		assertFalse(p.supports(new TestResource(file)));
		assertFalse(p.supports(new TestResource(null)));
		assertFalse(p.supports(new TestResource(dir.resolve("missing"))));
	}

	// ----------------------------------------------------------- openResource

	@Test
	void openResource_listsChildrenWithParentEntryAndColumns(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Files.writeString(dir.resolve("a.txt"), "a");
		Files.writeString(dir.resolve("b.txt"), "b");
		Files.createDirectory(dir.resolve("sub"));

		NuclrResourceData data = p.openResource(new TestResource(dir), new AtomicBoolean(false));

		assertNotNull(data);
		assertEquals(FileNuclrResource.columnNamesFor(new TestResource(dir)), data.getColumnNames());
		List<String> names = data.getEntries().stream().map(NuclrResource::getName).toList();
		assertTrue(names.contains(".."), "a non-root folder gets a parent entry");
		assertTrue(names.contains("a.txt"));
		assertTrue(names.contains("b.txt"));
		assertTrue(names.contains("sub"));
		assertEquals(4, data.getEntries().size());
		assertSame(dir, p.getCurrentResource().getPath(), "current folder follows the opened resource");
	}

	@Test
	void openResource_streamsEveryEntryToTheSink(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Files.writeString(dir.resolve("x.txt"), "x");
		Files.writeString(dir.resolve("y.txt"), "y");
		CollectingSink sink = new CollectingSink();

		NuclrResourceData data = p.openResource(new TestResource(dir), new AtomicBoolean(false), sink);

		assertEquals(1, sink.columnsCalls, "columns declared exactly once");
		assertEquals(FileNuclrResource.columnNamesFor(new TestResource(dir)), sink.columns);
		assertEquals(data.getEntries().size(), sink.added.size(), "sink sees every entry");
	}

	@Test
	void openResource_returnsNullForNullCancelledAndNonDirectory(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Path file = dir.resolve("f.txt");
		Files.writeString(file, "x");

		assertEquals(null, p.openResource(null, new AtomicBoolean(false)));
		assertEquals(null, p.openResource(new TestResource(dir), new AtomicBoolean(true)));
		assertEquals(null, p.openResource(new TestResource(null), new AtomicBoolean(false)));
		assertEquals(null, p.openResource(new TestResource(file), new AtomicBoolean(false)));
	}

	// ----------------------------------------------------- selection summary

	@Test
	void selectionSummary_emptyReturnsLocation() {
		LocalFileSystemPlugin p = newPlugin();
		assertEquals(p.getCurrentLocationDisplayText(), p.getSelectionSummaryText(List.of()));
		assertEquals(p.getCurrentLocationDisplayText(), p.getSelectionSummaryText(null));
	}

	@Test
	void selectionSummary_singleFileShowsNameAndSize(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Path file = dir.resolve("doc.txt");
		Files.write(file, new byte[] { 1, 2, 3, 4, 5 });

		// As with menuItems(), the summary reads the entry's own attributes rather than
		// re-stat-ing it, so the resource has to be a real listing entry.
		String summary = p.getSelectionSummaryText(List.of(Helper.build(ctx, file)));
		assertEquals("doc.txt  |  " + FileUtils.byteCountToDisplaySize(5), summary);
	}

	@Test
	void selectionSummary_singleDirectoryShowsFolder(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		assertEquals(dir.getFileName().toString() + "  |  Folder",
				p.getSelectionSummaryText(List.of(new TestResource(dir, dir.getFileName().toString(), true))));
	}

	@Test
	void selectionSummary_multipleAggregatesBytesFilesFolders(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		Path f1 = dir.resolve("a");
		Path f2 = dir.resolve("b");
		Path sub = dir.resolve("sub");
		Files.write(f1, new byte[] { 1, 2, 3 });
		Files.write(f2, new byte[] { 1, 2, 3, 4 });
		Files.createDirectory(sub);

		String summary = p.getSelectionSummaryText(
				List.of(Helper.build(ctx, f1), Helper.build(ctx, f2), Helper.build(ctx, sub)));

		assertEquals("Bytes: " + FileUtils.byteCountToDisplaySize(7) + ",  files: 2,  folders: 1", summary);
	}

	// ------------------------------------------------------- walkDescendants

	@Test
	void walkDescendants_nonRecursiveVisitsDirectChildrenOnly(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		buildTree(dir);

		List<String> visited = new ArrayList<>();
		p.walkDescendants(new TestResource(dir), r -> visited.add(r.getName()), new AtomicBoolean(false), false);

		assertEquals(3, visited.size(), visited.toString());
		assertTrue(visited.contains("a.txt"));
		assertTrue(visited.contains("sub"));
		assertFalse(visited.contains("c.txt"), "must not descend into sub");
	}

	@Test
	void walkDescendants_recursiveVisitsAllDescendantsButNotRoot(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		buildTree(dir);

		List<String> visited = new ArrayList<>();
		p.walkDescendants(new TestResource(dir), r -> visited.add(r.getName()), new AtomicBoolean(false), true);

		assertEquals(4, visited.size(), visited.toString());
		assertTrue(visited.contains("c.txt"), "descends into sub");
		assertFalse(visited.contains(dir.getFileName().toString()), "root itself is not visited");
	}

	@Test
	void walkDescendants_honoursPreSetCancellation(@TempDir Path dir) throws IOException {
		LocalFileSystemPlugin p = newPlugin();
		buildTree(dir);

		List<String> visited = new ArrayList<>();
		p.walkDescendants(new TestResource(dir), r -> visited.add(r.getName()), new AtomicBoolean(true), true);

		assertTrue(visited.isEmpty(), "already-cancelled walk visits nothing");
	}

	// ------------------------------------------------------------------- act

	@Test
	void act_viewEmitsMainPanelViewEvent(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		TestResource focused = new TestResource(dir);

		p.act(null, "filepanel.view", List.of(), focused, new HashMap<>(), null);

		var emitted = ctx.eventBus.emissionsOfType("mainpanel.view");
		assertEquals(1, emitted.size());
		assertSame(focused, emitted.get(0).event.get("resource"));
	}

	@Test
	void act_copyDelegatesToADifferentPlugin(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		RecordingFilePanelPlugin other = new RecordingFilePanelPlugin("other-uuid");
		TestResource res = new TestResource(dir);

		p.act(other, "filepanel.copy", List.of(res), res, new HashMap<>(), null);

		// A copy to a foreign plugin is forwarded as the "accept.copy" handshake, with the
		// source passed as null (the receiver is the copy destination, not a back-reference).
		assertEquals(1, other.actCalls.size());
		assertEquals("accept.copy", other.actCalls.get(0).actionType);
		assertNull(other.actCalls.get(0).other, "the copy is forwarded with no source back-reference");
		assertSame(res, other.actCalls.get(0).focused);
	}

	@Test
	void act_copyToAnotherFsInstanceDelegatesToThatInstance(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		TestResource res = new TestResource(dir);
		Map<String, Object> data = new HashMap<>();

		// Another FS panel instance with a different uuid => the peer FS instance is the
		// copy destination, so the "accept.copy" handshake is forwarded to it (source =
		// null) and the receiving pane copies into its own folder.
		RecordingLocalFileSystemPlugin fsTwin = new RecordingLocalFileSystemPlugin("twin");
		p.act(fsTwin, "filepanel.copy", List.of(res), res, data, null);
		assertEquals(1, fsTwin.actCalls.size());
		assertEquals("accept.copy", fsTwin.actCalls.get(0).actionType);
		assertNull(fsTwin.actCalls.get(0).other, "the copy is forwarded with no source back-reference");
		assertSame(res, fsTwin.actCalls.get(0).focused);

		// Same uuid => copy to itself: handled in-process, never delegated to the peer.
		RecordingFilePanelPlugin self = new RecordingFilePanelPlugin(p.uuid());
		p.act(self, "filepanel.copy", List.of(res), res, data, null);
		assertTrue(self.actCalls.isEmpty());

		// Null other: no peer to delegate to, no exception.
		assertDoesNotThrow(() -> p.act(null, "filepanel.copy", List.of(res), res, data, null));
	}

	@Test
	void act_copyEmitsRefreshEventForTheHandlingPanel(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		TestResource res = new TestResource(dir);

		// other == null => copy handled in-process. Once the copy runs, the plugin asks the
		// commander to reload the pane that owns it (identified by uuid), because that is the
		// folder whose contents changed — not necessarily the pane that initiated the action.
		p.act(null, "filepanel.copy", List.of(res), res, new HashMap<>(), null);

		var refreshes = ctx.eventBus.emissionsOfType("refresh.plugin.file.panel");
		assertEquals(1, refreshes.size());
		assertEquals(p.uuid(), refreshes.get(0).event.get("plugin.uuid"));
	}

	@Test
	void act_openOnMissingPathSwallowsIoErrors(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		TestResource missing = new TestResource(dir.resolve("nope.txt"));

		assertDoesNotThrow(
				() -> p.act(null, "filepanel.path.opened", null, missing, new HashMap<>(), null));
	}

	@Test
	void act_revealSkipsResourcesWithoutAPath() {
		LocalFileSystemPlugin p = newPlugin();
		// A null-path resource is filtered out, so nothing is launched and nothing throws.
		assertDoesNotThrow(() -> p.act(null, "filepanel.path.open.in.explorer",
				List.of(new TestResource(null)), null, new HashMap<>(), null));
	}

	@Test
	void clipboardPasteText_opensAnExistingFolder(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();
		Path folder = dir.resolve("pasted folder");
		assertDoesNotThrow(() -> Files.createDirectory(folder));

		p.processClipboardPasteText("  \"" + folder + "\"  ");

		var opened = ctx.eventBus.emissionsOfType("filepanel.path.opened");
		assertEquals(1, opened.size());
		assertSame(p, opened.get(0).source);
		assertEquals(folder.toAbsolutePath().normalize(),
				((NuclrResource) opened.get(0).event.get("resource")).getPath());
	}

	@Test
	void clipboardPasteText_copiesAnExistingFile(@TempDir Path dir) throws IOException {
		class RecordingPastePlugin extends LocalFileSystemPlugin {
			private List<Path> pastedFiles = List.of();

			@Override
			void processClipboardPasteFiles(List<Path> clipboardFiles) {
				pastedFiles = List.copyOf(clipboardFiles);
			}
		}

		ctx = new FakeContext();
		RecordingPastePlugin p = new RecordingPastePlugin();
		p.preinit(ctx);
		p.init();
		Path file = Files.writeString(dir.resolve("file.txt"), "clipboard file");

		p.processClipboardPasteText("  \"" + file + "\"  ");

		assertEquals(List.of(file), p.pastedFiles);
		assertTrue(ctx.eventBus.emissionsOfType("filepanel.path.opened").isEmpty());
	}

	@Test
	void clipboardPasteText_ignoresMissingPathsAndInvalidText(@TempDir Path dir) {
		LocalFileSystemPlugin p = newPlugin();

		p.processClipboardPasteText(dir.resolve("missing").toString());
		p.processClipboardPasteText("\0invalid");
		p.processClipboardPasteText("   ");
		p.processClipboardPasteText(null);

		assertTrue(ctx.eventBus.emissionsOfType("filepanel.path.opened").isEmpty());
	}

	@Test
	void handleMessage_isInert() {
		LocalFileSystemPlugin p = newPlugin();
		assertDoesNotThrow(() -> p.handleMessage(null, "whatever", new HashMap<>(), null));
	}

	// --------------------------------------------------------------- helpers

	private static void buildTree(Path dir) throws IOException {
		Files.writeString(dir.resolve("a.txt"), "a");
		Files.writeString(dir.resolve("b.txt"), "b");
		Path sub = dir.resolve("sub");
		Files.createDirectory(sub);
		Files.writeString(sub.resolve("c.txt"), "c");
	}

	private static String labelFor(List<NuclrMenuResource> items, String functionKey) {
		return items.stream().filter(m -> functionKey.equals(m.getFunctionKey())).map(NuclrMenuResource::getName)
				.findFirst().orElse(null);
	}

	private static String eventFor(List<NuclrMenuResource> items, String functionKey) {
		return items.stream().filter(m -> functionKey.equals(m.getFunctionKey()))
				.map(NuclrMenuResource::getEventType).findFirst().orElse(null);
	}
}
