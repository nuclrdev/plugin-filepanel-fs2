package dev.nuclr.plugin.core.panel.fs;

import java.awt.Event;
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
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrContextMenuItem;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import dev.nuclr.plugin.core.panel.fs.service.DeleteService;
import dev.nuclr.plugin.core.panel.fs.service.MakeNewFolderService;
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

	}

	private Path getRootPath() {

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

		var rootPath = getRootPath();
		log.info("Default drive path: " + rootPath);
		this.currentFolder = Helper.build(context, rootPath);

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
			res.setPath(Helper.build(context, p));
			res.setText(p.toString());
			res.setUuid(id() + ":" + p.toString());
			resources.add(res);
		});

		pluginRoots.setMenuItems(resources);
		pluginRoots.setTitle("Local Filesystem");

		return pluginRoots;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource folder, AtomicBoolean cancelled) {

		if (cancelled != null && cancelled.get()) {
			return null;
		}

		if (folder == null) {
			return null;
		}

		Path path = folder.getPath();

		if (path == null) {
			return null;
		}

		// Follow Windows junctions / symlinks (e.g. C:\Documents and Settings ->
		// C:\Users) to their real target so the contents can be listed and the panel
		// reflects the resolved location.
		var effective = resolveReparseTarget(path);
		if (!effective.equals(path)) {
			folder = Helper.build(context, effective);
			path = effective;
		}

		if (!Files.isDirectory(path)) {
			return null;
		}

		this.currentFolder = folder;

		var entries = new NuclrResourceData();
		entries.setColumnNames(FileNuclrResource.ColumnNames);

		// Add the parent directory entry if not at the root level
		if (folder.getPath().getParent() != null) {
			var parentCopy = Helper.build(context, path.getParent());
			parentCopy.setName("..");
			entries.getEntries().add(parentCopy);
		}

		try (var stream = Files.list(path)) {
			stream.forEach(p -> {
				if (cancelled != null && cancelled.get()) {
					return;
				}
				entries.getEntries().add(Helper.build(context, p));
			});
		} catch (IOException e) {
			log.error("Failed to list directory: " + path, e);
		}
		
		return entries;

	}

	@Override
	public boolean isMessageSupported(String type) {
		return false;
	}

	private static void addDefaultMenuItems(List<NuclrMenuResource> items, boolean isDirectory) {
		items.add(menu("View", "F3", "filepanel.view"));
		items.add(menu("Edit", "F4", "filepanel.edit"));
		items.add(menu("Copy", "F5", "filepanel.copy"));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "filepanel.move"));
		items.add(menu("Make Folder", "F7", "filepanel.makeFolder"));
		items.add(menu("Delete", "F8", "filepanel.delete"));
	}

	private static void addAltMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Find", "Alt+F7", "find"));
		items.add(menu("Tree", "Alt+F10", "tree"));
		items.add(menu("Folder History", "Alt+F12", "folderHistory"));
	}

	private static void addCtrlMenuItems(List<NuclrMenuResource> items) {
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
	public void walkDescendants(NuclrResource folder, Consumer<NuclrResource> visitor, AtomicBoolean cancelled,
			boolean recursive) throws IOException {

		Path root = folder.getPath();

		if (!recursive) {
			// Single level only — list direct children, no descent.
			try (var stream = Files.newDirectoryStream(root)) {
				for (var child : stream) {
					if (isCancelled(cancelled))
						return;
					visitor.accept(Helper.build(context, child));
				}
			} catch (IOException e) {
				log.error("Error listing {}: {}", folder.getFullPath(), e.getMessage(), e);
				throw e;
			}
			return;
		}

		try {

			Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
					new SimpleFileVisitor<>() {
						@Override
						public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
							if (isCancelled(cancelled))
								return FileVisitResult.TERMINATE;
							if (!dir.equals(root)) {
								visitor.accept(Helper.build(context, dir));
							}
							return FileVisitResult.CONTINUE;
						}

						@Override
						public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
							if (isCancelled(cancelled))
								return FileVisitResult.TERMINATE;
							visitor.accept(Helper.build(context, file));
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

	/**
	 * This plugin supports resources that have a File path metadata which exists
	 * and is a directory.
	 */
	@Override
	public boolean supports(NuclrResource resource) {

		var path = resource.getPath();
		
		if (path == null) {
			return false;
		}

		var effective = resolveReparseTarget(path);
		
		// If a director and not readable, show a warning message, don't open the folder
		if (Files.isDirectory(effective) && false == Files.isReadable(effective)) {
			log.warn("Directory {} is not readable", effective);
			Alerts.showError("Directory is not readable", "The directory " + effective.toAbsolutePath() + " cannot be opened because it is not readable. Please check the permissions and try again.");
			return false;
		}

		return Files.exists(effective) && Files.isDirectory(effective) && Files.isReadable(effective);

	}

	/**
	 * Resolve a Windows junction / symbolic link to its real target.
	 *
	 * <p>Some system reparse points — notably {@code C:\Documents and Settings} —
	 * carry a deny ACL on the link itself, so they cannot be enumerated even though
	 * their target ({@code C:\Users}) is perfectly readable. Following the link lets
	 * us browse the target instead of refusing the entry, the way Far Commander does.
	 *
	 * @return the resolved real path when {@code path} is a reparse point that points
	 *         to a readable directory; otherwise {@code path} unchanged.
	 */
	private static Path resolveReparseTarget(Path path) {

		if (path == null) {
			return path;
		}

		// Fast path: a directly-browsable directory needs no resolution. This also
		// leaves ordinary readable symlinks untouched, so only the broken deny-ACL
		// reparse points are redirected.
		if (Files.isDirectory(path) && Files.isReadable(path)) {
			return path;
		}

		try {
			// toRealPath follows Windows junctions and symlinks to the real location,
			// even when the reparse point itself carries a deny ACL (the JDK's
			// readSymbolicLink throws NotLinkException for mount-point junctions, so it
			// cannot be used here). For a non-reparse path it just returns the same
			// canonical location, so the !equals guard keeps such paths untouched.
			var real = path.toRealPath();

			if (!real.equals(path) && Files.isDirectory(real) && Files.isReadable(real)) {
				return real;
			}
		} catch (IOException e) {
			// Unresolvable or unreadable — browse the original path.
		}

		return path;
	}

	@Override
	public String getWindowTitle() {
		return this.currentFolder != null ? this.currentFolder.getPath().toAbsolutePath().toString() : null;
	}

	/** Helper to determine the list of resources to act on for a file panel event, based on the current selection and focus state. */
	private List<NuclrResource> getSelectedResourcesForEvent(
		List<NuclrResource> selectedResources,
		NuclrResource focusedResource) {
		
		var list = new ArrayList<NuclrResource>();
		
		// If there are selected resources, use them. Otherwise, if there's a focused resource, use it as a single-item list.
		if (selectedResources != null && !selectedResources.isEmpty()) {
			list.addAll(selectedResources);
		} else if (focusedResource != null) {
			list.add(focusedResource);
		}
		
		return list;
		
	}

	@Override
	public void act(
			BaseNuclrPlugin other, 
			String actionType, 
			List<NuclrResource> selectedResources,
			NuclrResource focusedResource,
			Map<String, Object> data, 
			NuclrPluginCallback callback) {
		
		if ("filepanel.delete".equals(actionType) || "filepanel.deletePermanent".equals(actionType)) {
			handleDelete(data, getSelectedResourcesForEvent(selectedResources, focusedResource), "filepanel.deletePermanent".equals(actionType), callback);
			return;
		}

		if ("filepanel.makeFolder".equals(actionType)) {
			handleMakeNewFolder(data, callback);
			return;
		}
		
		if ("filepanel.path.open.in.explorer".equals(actionType)) {
			handleRevealInFileManager(data, getSelectedResourcesForEvent(selectedResources, focusedResource), callback);
			return;
		}
		
	}

	
	private void handleRevealInFileManager(Map<String, Object> data, List<NuclrResource> selectedResourcesForEvent,
			NuclrPluginCallback callback) {
		
		
		if (false == selectedResourcesForEvent.isEmpty()) {
			// If there are selected resources, reveal the first one.
			var resource = selectedResourcesForEvent.get(0);
			if (resource.getPath() != null) {
				try {
					Helper.revealInFileManager(resource.getPath());
				} catch (IOException e) {
					log.error("Failed to reveal {} in file manager: {}", resource.getFullPath(), e.getMessage(), e);
				}
				return;
			}
		}
		
	}

	/** 
	 * 	Handle the "Make Folder" action by prompting the user for a new folder name, creating the folder in the current directory, 
	 *  and adding the created resource to the event data for selection. 
	 * */
	private void handleMakeNewFolder(Map<String, Object> data, NuclrPluginCallback callback) {

		var createdPath = MakeNewFolderService.makeNewFolder(currentFolder, callback);
		if (createdPath == null) {
			return;
		}
		try {
			data.put("result.refresh", true);
			data.put("result.refresh.selected.resource", Helper.build(context, createdPath));
		} catch (UnsupportedOperationException ignored) {
			log.debug("Make-folder event payload is immutable; created resource will not be selected.");
		}

		
	}

	@SuppressWarnings("unchecked")
	private void handleDelete(Map<String, Object> data, List<NuclrResource> sources, boolean permanent, NuclrPluginCallback callback) {

		// Plugin-rendered confirmation listing the full paths to be deleted.
		if (!DeleteDialogs.confirmDelete(sources)) {
			return;
		}

		DeleteService.delete(sources, permanent, callback, (item, e) -> DeleteDialogs.error(item.getName(), e));
		
		data.put("result.refresh", true);
		
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource, List<NuclrResource> selectedResources) {

		return List.of(
			NuclrContextMenuItem.builder().label("Open").actionType("filepanel.path.opened").build(),
			NuclrContextMenuItem.builder().label("Reveal in File Manager").actionType("filepanel.path.open.in.explorer").build(),
			NuclrContextMenuItem.builder().separator(true).build(),
			NuclrContextMenuItem.builder().label("Delete").actionType("filepanel.delete").build()
		);
	}

	

	

}
