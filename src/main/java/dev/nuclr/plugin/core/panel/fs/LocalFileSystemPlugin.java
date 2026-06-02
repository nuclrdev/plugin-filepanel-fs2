package dev.nuclr.plugin.core.panel.fs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileSystemPlugin implements NuclrEventListener, FilePanelNuclrPlugin {

	private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

	private static final String GO_TO_PATH_SHORTCUT = IS_MAC ? "Shift+Cmd+G" : "Ctrl+Shift+G";

	private String uuid = java.util.UUID.randomUUID().toString();

	public static final String PluginId = "dev.nuclr.plugin.core.panel.fs";
	private static final String PluginName = "Local Filesystem Panel";
	private static final String PluginVersion = "1.0.0";
	private static final String PluginDescription = "Provides local filesystem roots (drives/mount points) to the file panel.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-fs.html";
	private static final String PluginDocUrl = PluginPageUrl;

	static final List<String> ColumnNames = List.of("Name", "Size", "Date", "Time");

	private NuclrPluginContext context;

	private boolean focused = false;

	private NuclrResource currentFolder;

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String version() {
		return PluginVersion;
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	@Override
	public String author() {
		return PluginAuthor;
	}

	@Override
	public String license() {
		return PluginLicense;
	}

	@Override
	public String website() {
		return PluginWebsite;
	}

	@Override
	public String pageUrl() {
		return PluginPageUrl;
	}

	@Override
	public String docUrl() {
		return PluginDocUrl;
	}

	public LocalFileSystemPlugin() {

		var defaultPath = getDefaultDrivePath();

		log.info("Default drive path: " + defaultPath);
		this.currentFolder = Helper.build(null, defaultPath);

	}

	private Path getDefaultDrivePath() {
		
		if (SystemUtils.IS_OS_WINDOWS) {
			// Windows: return a virtual "This PC" root — use null-root path
			// FileSystems.getDefault().getRootDirectories() gives C:\, D:\, etc.
			// But "This PC" itself has no real Path equivalent; conventionally use the
			// first root
			// or a sentinel. Here we return the user's home drive root.
			Path home = Path.of(System.getProperty("user.home"));
			return home.getRoot(); // e.g. C:\
		}

		// Unix / macOS / Linux
		return Path.of("/");
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {

		var items = new ArrayList<NuclrMenuResource>();
		
		boolean isDirectory = source != null && source.getPath() != null && Files.isDirectory(source.getPath());
		
		addDefaultMenuItems(items, isDirectory);
		addAltMenuItems(items);
		addCtrlMenuItems(items);
		addShiftMenuItems(items, isDirectory);
		
		return items;
		
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		log.info("Local filesystem panel plugin loaded");
	}

	@Override
	public void init() {
		context.getEventBus().subscribe(this);
		log.info("Local filesystem panel plugin inited");
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		log.info("Local filesystem panel plugin unloaded");
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {

		var pluginRoots = new MenuItemsHolder();

		var resources = new ArrayList<MenuItem>();

		FileSystems.getDefault().getRootDirectories().forEach(p -> {
			var res = new MenuItem();
			res.setPath(Helper.build(null, p));
			res.setText(p.toString());
			res.setUuid(id() + ":" + p.toString());
			resources.add(res);
		});

		pluginRoots.setMenuItems(resources);
		pluginRoots.setTitle("Local Filesystem");

		return pluginRoots;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource parentFolder, NuclrResource folder, AtomicBoolean cancelled) {

		if (cancelled != null && cancelled.get()) {
			return null;
		}

		if (folder == null) {
			return null;
		}
		
		Path path = folder.getPath();

		if (path == null || !Files.isDirectory(path)) {
			return null;
		}

		this.currentFolder = folder;

		var entries = new NuclrResourceData();
		entries.setColumnNames(ColumnNames);

		// Add the parent directory entry if not at the root level
		if (parentFolder != null) {

			var parentCopy = Helper.build(parentFolder, path.getParent());
			parentCopy.setParent(true);
			parentCopy.setName("..");
			parentCopy.setFullPath("..");
			parentCopy.getColumnValues().set(0, "..");
			entries.getEntries().add(parentCopy);
		}

		try (var stream = Files.list(path)) {
			stream.forEach(p -> {
				if (cancelled != null && cancelled.get()) {
					return;
				}
				entries.getEntries().add(Helper.build(folder, p));
			});
		} catch (IOException e) {
			log.error("Failed to list directory: " + path, e);
		}

		return entries;

	}

	@Override
	public boolean isMessageSupported(String type) {
		return true;
	}

	private static void addDefaultMenuItems(List<NuclrMenuResource> items, boolean isDirectory) {
		items.add(menu("View", "F3", "view"));
		items.add(menu("Edit", "F4", "edit"));
		items.add(menu("Copy", "F5", "copy"));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "move"));
		items.add(menu("Make Folder", "F7", "makeFolder"));
		items.add(menu("Delete", "F8", "delete"));
		items.add(menu("Quit", "F10", "quit"));
		items.add(menu("Plugins", "F11", "plugins"));
		items.add(menu("Screen", "F12", "screen"));
	}

	private static void addAltMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Left Panel", "Alt+F1", "left"));
		items.add(menu("Right Panel", "Alt+F2", "right"));
		items.add(menu("Find", "Alt+F7", "find"));
		items.add(menu("History", "Alt+F8", "history"));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen"));
		items.add(menu("Tree", "Alt+F10", "tree"));
		items.add(menu("View History", "Alt+F11", "viewHistory"));
		items.add(menu("Folder History", "Alt+F12", "folderHistory"));
	}

	private static void addCtrlMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft"));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight"));
		items.add(menu("Sort by name", "Ctrl+F3", "sortByName"));
		items.add(menu("Sort by extension", "Ctrl+F4", "sortByExtension"));
		items.add(menu("Sort by modified", "Ctrl+F5", "sortByModifiedDate"));
		items.add(menu("Sort by size", "Ctrl+F6", "sortBySize"));
		items.add(menu("Unsort", "Ctrl+F7", "unsort"));
		items.add(menu("Sort by create", "Ctrl+F8", "sortByCreateDate"));
		items.add(menu("Sort by access", "Ctrl+F9", "sortByAccessTime"));
		items.add(menu("Sort menu", "Ctrl+F12", "sortMenu"));
	}

	private static void addShiftMenuItems(List<NuclrMenuResource> items, boolean isDirectory) {
		items.add(menu("Create file", "Shift+F4", "createFile"));
		items.add(menu("Go to path", GO_TO_PATH_SHORTCUT, "goToPath"));
		items.add(menu("Delete Permanently", "Shift+F8", "deletePermanent"));
	}

	private static NuclrMenuResource menu(String name, String functionKey, String eventType) {
		return new NuclrMenuResource(name, functionKey, eventType);
	}

	@Override
	public boolean onFocusGained() {
		setPluginFocused(true);
		return true;
	}

	@Override
	public void onFocusLost() {
		setPluginFocused(false);
	}

	private void setPluginFocused(boolean flag) {
		this.focused = flag;
	}

	@Override
	public boolean singleton() {
		return false;
	}

	@Override
	public boolean isFocused() {
		return this.focused;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return this.currentFolder;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void closeResource() {
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		return String.valueOf(getCurrentFolderPath());
	}
	
	private Path getCurrentFolderPath() {
		return this.currentFolder != null ? this.currentFolder.getPath() : null;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			var resource = selectedResources.get(0);
			Path path = resource.getPath();
			boolean directory = path != null && Files.isDirectory(path);
			boolean link = isLink(path);
			String type = link ? "Link" : (directory ? "Folder" : humanReadableSize(sizeBytes(resource, directory)));
			String name = resource.getName() != null && !resource.getName().isBlank() ? resource.getName()
					: path == null ? "" : path.getFileName() == null ? path.toString() : path.getFileName().toString();
			return name + "  |  " + type;
		}
		long totalBytes = 0L;
		int fileCount = 0;
		int folderCount = 0;
		for (var resource : selectedResources) {
			Path path = resource.getPath();
			if (path != null && Files.isDirectory(path)) {
				folderCount++;
			} else {
				fileCount++;
				totalBytes += sizeBytes(resource, false);
			}
		}
		return "Bytes: " + humanReadableSize(totalBytes) + ",  files: " + fileCount + ",  folders: " + folderCount;
	}

	private static long sizeBytes(NuclrResource resource, boolean directory) {
		if (resource == null || directory) {
			return 0L;
		}
		Path path = resource.getPath();
		if (path != null) {
			try {
				return Files.size(path);
			} catch (IOException ignored) {
				// Fall back to the resource payload below.
			}
		}
		
		return 0L;
	}

	private static boolean isLink(Path path) {
		return path != null && Files.isSymbolicLink(path);
	}

	private static String humanReadableSize(long sizeBytes) {
		return FileUtils.byteCountToDisplaySize(sizeBytes);
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData, NuclrPluginCallback callback) {

	}

	@Override
	public void walkDescendants(
			NuclrResource folder, 
			Consumer<NuclrResource> visitor, 
			AtomicBoolean cancelled, 
			boolean recursive) throws IOException {

		Path root = folder.getPath();

		if (!recursive) {
			// Single level only — list direct children, no descent.
			try (var stream = Files.newDirectoryStream(root)) {
				for (var child : stream) {
					if (isCancelled(cancelled))
						return;
					visitor.accept(Helper.build(folder, child));
				}
			} catch (IOException e) {
				log.error("Error listing {}: {}", folder.getFullPath(), e.getMessage(), e);
				throw e;
			}
			return;
		}

		try {

			Files.walkFileTree(
					root, 
					EnumSet.noneOf(FileVisitOption.class), 
					Integer.MAX_VALUE,
					new SimpleFileVisitor<>() {
						@Override
						public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
							if (isCancelled(cancelled))
								return FileVisitResult.TERMINATE;
							if (!dir.equals(root)) {
								visitor.accept(Helper.build(folder, dir));
							}
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
							if (isCancelled(cancelled))
								return FileVisitResult.TERMINATE;
							visitor.accept(Helper.build(folder, file));
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFileFailed(Path file, IOException exc) {
							log.warn("Skipping inaccessible path {}: {}", file, exc.getMessage());
							return FileVisitResult.CONTINUE;
						}
					});
		} catch (IOException e) {
			log.error("Error walking file tree for resource {}: {}", folder.getFullPath(), e.getMessage(), e);
			throw e;
		}
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return (cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted();
	}

	/** This plugin supports resources that have a File path metadata which exists and is a directory. */
	@Override
	public boolean supports(NuclrResource resource) {

		Path path = resource.getPath();

		if (path != null) {
			return path != null && Files.exists(path) && Files.isDirectory(path) && Files.isReadable(path);
		}

		return false;
	}

}
