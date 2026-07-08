package dev.nuclr.plugin.core.panel.fs;

import java.awt.KeyboardFocusManager;
import java.awt.Window;
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
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

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
import dev.nuclr.plugin.core.panel.fs.find.FindFileContext;
import dev.nuclr.plugin.core.panel.fs.find.FindFileDialog;
import dev.nuclr.plugin.core.panel.fs.find.FindFileRequest;
import dev.nuclr.plugin.core.panel.fs.find.FindFileService;
import dev.nuclr.plugin.core.panel.fs.find.FindResultsWindow;
import dev.nuclr.plugin.core.panel.fs.find.LocalResourceNavigator;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import dev.nuclr.plugin.core.panel.fs.service.ClipboardService;
import dev.nuclr.plugin.core.panel.fs.service.CopyService;
import dev.nuclr.plugin.core.panel.fs.service.DeleteService;
import dev.nuclr.plugin.core.panel.fs.service.MakeNewFolderService;
import dev.nuclr.plugin.core.panel.fs.service.move.MoveService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileSystemPlugin implements NuclrEventListener, FilePanelNuclrPlugin {

	private static final String AcceptCopy = "accept.copy";
	private static final String AcceptMove = "accept.move";
	private static final String ClipboardCopy = "clipboard.copy";
	private static final String ClipboardCopyFiles = "clipboard.copy.files";
	private static final String ClipboardCopyFullPaths = "clipboard.copy.fullPaths";

	private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");

	private static final String GO_TO_PATH_SHORTCUT = IS_MAC ? "Shift+Cmd+G" : "Ctrl+Shift+G";

	protected String uuid = java.util.UUID.randomUUID().toString();

	public static final String PluginId = "dev.nuclr.plugin.core.panel.fs";
	protected static final String PluginName = "Local Filesystem Panel";
	protected static final String PluginVersion = loadVersion();
	protected static final String PluginDescription = "Provides local filesystem roots (drives/mount points) to the file panel.";
	protected static final String PluginAuthor = "Nuclr Development Team";
	protected static final String PluginLicense = "Apache-2.0";
	protected static final String PluginWebsite = "https://nuclr.dev";
	protected static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-fs.html";
	protected static final String PluginDocUrl = PluginPageUrl;

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

	private static String loadVersion() {
		try (var stream = LocalFileSystemPlugin.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) return "unknown";
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (java.io.IOException e) {
			return "unknown";
		}
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
			// Windows: return a virtual "This PC" root â€” use null-root path
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
		addSortMenuItems(items);
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
		return openResource(folder, cancelled, null);
	}

	/**
	 * Streaming listing: each entry is published to {@code sink} as soon as it is
	 * built, so the panel paints the folder incrementally, while the returned
	 * {@link NuclrResourceData} still carries the complete listing for the
	 * commander's final sort and cursor placement.
	 */
	@Override
	public NuclrResourceData openResource(NuclrResource folder, AtomicBoolean cancelled, EntrySink sink) {

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
		var columnNames = FileNuclrResource.columnNamesFor(folder);
		entries.setColumnNames(columnNames);
		if (sink != null) {
			sink.columns(columnNames);
		}

		// Add the parent directory entry if not at the root level
		if (folder.getPath().getParent() != null) {
			var parentCopy = Helper.build(context, path.getParent());
			parentCopy.setName("..");
			entries.getEntries().add(parentCopy);
			if (sink != null) {
				sink.add(parentCopy);
			}
		}

		try (var stream = list(path)) {
			var iterator = stream.iterator();
			while (iterator.hasNext()) {
				if (cancelled != null && cancelled.get()) {
					break;
				}
				var entry = Helper.build(context, iterator.next());
				entries.getEntries().add(entry);
				if (sink != null) {
					sink.add(entry);
				}
			}
		} catch (IOException e) {
			log.error("Failed to list directory: " + path, e);
		}

		return entries;

	}

	protected Stream<Path> list(Path path) throws IOException {
		return Files.list(path);
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
		items.add(menu("Drive Information", "Ctrl+L", "show.drive.information"));
	}

	/**
	 * Sorts this panel exposes, shown on the function-key bar under Ctrl. Column-backed sort labels
	 * intentionally match the exposed table columns. Owner/description/type/attributes/full-path are
	 * omitted because the commander does not provide comparators for those plugin-specific values.
	 */
	private static void addSortMenuItems(List<NuclrMenuResource> items) {
		items.add(sortByColumn("Name", "Ctrl+F3", "name"));
		items.add(sortByColumn("Extension", "Ctrl+F4", "ext"));
		items.add(sortByColumn("Modified", "Ctrl+F5", "modified"));
		items.add(sortByColumn("Size", "Ctrl+F6", "size"));
		items.add(menu("Unsort", "Ctrl+F7", "filepanel.sort:unsorted"));
		items.add(sortByColumn("Created", "Ctrl+F8", "created"));
		items.add(sortByColumn("Accessed", "Ctrl+F9", "access"));
		items.add(menu("Sort", "Ctrl+F12", "filepanel.sort:dialog"));
	}

	private static NuclrMenuResource sortByColumn(String columnName, String functionKey, String criterion) {
		return menu(columnName, functionKey, "filepanel.sort:" + criterion + ":" + columnName);
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
			// Single level only â€” list direct children, no descent.
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
			Alerts.showError("Directory is not readable", "<html>The directory <b>\"" + effective.toAbsolutePath() + "\"</b> cannot be opened because it is not readable.<br/>Please check the permissions and try again.</html>");
			return false;
		}

		return Files.exists(effective) && Files.isDirectory(effective) && Files.isReadable(effective);

	}

	/**
	 * Resolve a Windows junction / symbolic link to its real target.
	 *
	 * <p>Some system reparse points â€” notably {@code C:\Documents and Settings} â€”
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
			// Unresolvable or unreadable â€” browse the original path.
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
		
		if ("find".equals(actionType)) {
			openFindFileDialog(other, selectedResources);
			return;
		}

		if ("show.drive.information".equals(actionType)) {
			Path folderPath = getCurrentFolderPath();
			if (folderPath != null) {
				DriveInfoDialog.show(folderPath);
			}
			return;
		}

		// Resolve a local filesystem path (e.g. the directory an embedded shell ended in) into a
		// navigable resource so the host can move the panel there. Returns it via the data map.
		if ("filepanel.navigate.to.path".equals(actionType)) {
			if (data.get("path") instanceof String pathText && !pathText.isBlank()) {
				try {
					Path path = Path.of(pathText);
					if (Files.isDirectory(path)) {
						data.put("result.navigate.resource", Helper.build(context, path));
					}
				} catch (RuntimeException e) {
					log.warn("Ignoring invalid navigate-to-path '{}': {}", pathText, e.getMessage());
				}
			}
			return;
		}

		if ("filepanel.path.opened".equals(actionType)) {
 			log.warn("Open action: " +  getSelectedResourcesForEvent(selectedResources, focusedResource).get(0));
 			try {
				SystemOpen.open(getSelectedResourcesForEvent(selectedResources, focusedResource).get(0).getPath());
			} catch (IOException e) {
			}
			return;
		}
		
		if ("filepanel.delete".equals(actionType) || "filepanel.deletePermanent".equals(actionType)) {
			handleDelete(data, getSelectedResourcesForEvent(selectedResources, focusedResource), "filepanel.deletePermanent".equals(actionType), callback);
			return;
		}

		if ("filepanel.makeFolder".equals(actionType)) {
			handleMakeNewFolder(data, callback);
			return;
		}

		if (ClipboardCopy.equals(actionType)) {
			ClipboardService.showClipboardMenu(
					getSelectedResourcesForEvent(selectedResources, focusedResource), this.currentFolder);
			return;
		}

		if (ClipboardCopyFiles.equals(actionType)) {
			ClipboardService.copyFiles(getSelectedResourcesForEvent(selectedResources, focusedResource));
			return;
		}

		if (ClipboardCopyFullPaths.equals(actionType)) {
			ClipboardService.copyFullPaths(
					getSelectedResourcesForEvent(selectedResources, focusedResource), this.currentFolder);
			return;
		}
		
		if ("filepanel.path.open.in.explorer".equals(actionType)) {
			handleRevealInFileManager(data, getSelectedResourcesForEvent(selectedResources, focusedResource), callback);
			return;
		}
		
		if ("filepanel.view".equals(actionType)) {

			/** Emit an event that will be consumed by MainWindow to show a full screen viewer for the selected/focused resource. */
			this.context.getEventBus().emit(
				"mainpanel.view",
				Map.of("resource", focusedResource),
				null);

			return;
		}

		if ("filepanel.edit".equals(actionType)) {

			/** Emit an event that will be consumed by MainWindow to show a full screen editor for the selected/focused resource. */
			this.context.getEventBus().emit(
				"mainpanel.edit",
				Map.of("resource", focusedResource),
				null);

			return;
		}
		
		// If the other plugin is not filepanel-fs, just pass the event to it.
		// If the other plugin doesn't exist, copy to itself.
		if ("filepanel.copy".equals(actionType)) {

			log.warn("Copy action: " + getSelectedResourcesForEvent(selectedResources, focusedResource));

			if (other == null || other.uuid().equals(this.uuid())) {
				log.warn("Copy to itself");
				this.act(null, AcceptCopy, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other != null && other.id().equals(LocalFileSystemPlugin.PluginId)) {
				log.warn("Copy to another instance of FS plugin");
				other.act(null, AcceptCopy, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other!=null && other.is(BaseNuclrPlugin.Type.QuickView)) {
				log.warn("Copy to itself");
				this.act(null, AcceptCopy, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other != null) {
				log.warn("Copy to another plugin: " + other.name());
				other.act(null, AcceptCopy, selectedResources, focusedResource, data, callback);
				return;
			}


		}
		
		// Accept copy action from other plugins, but only if the source is not this plugin (to avoid loops) and the payload contains resources.
		if (AcceptCopy.equals(actionType)) {
			new CopyService().copy(this.currentFolder, selectedResources, focusedResource, data, callback);
			this.context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", this.uuid()), null);
			return;
		}
		
		
		// Process MOVE event
		if ("filepanel.move".equals(actionType)) {

			log.warn("Move action: " + getSelectedResourcesForEvent(selectedResources, focusedResource));

			if (other == null || other.uuid().equals(this.uuid())) {
				log.warn("Move to itself (rename)");
				this.act(null, AcceptMove, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other != null && other.id().equals(LocalFileSystemPlugin.PluginId)) {
				log.warn("Move to another instance of FS plugin");
				other.act(null, AcceptMove, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other!=null && other.is(BaseNuclrPlugin.Type.QuickView)) {
				log.warn("Move to itself (rename)");
				this.act(null, AcceptMove, selectedResources, focusedResource, data, callback);
				return;
			}

			if (other != null) {
				log.warn("Move to another plugin: " + other.name());
				other.act(null, AcceptMove, selectedResources, focusedResource, data, callback);
				return;
			}

		}
		
		// Accept move action from other plugins, but only if the source is not this plugin (to avoid loops) and the payload contains resources.
		if (AcceptMove.equals(actionType)) {
			new MoveService().move(this.currentFolder, selectedResources, focusedResource, data, callback, this.context);
			this.context.getEventBus().emit("refresh.plugin.file.panel", Map.of("plugin.uuid", this.uuid()), null);
			return;
		}
		

	}

	private void handleRevealInFileManager(Map<String, Object> data, List<NuclrResource> selectedResourcesForEvent,
			NuclrPluginCallback callback) {
		
		if (false == selectedResourcesForEvent.isEmpty()) {
			
			selectedResourcesForEvent.stream()
				.filter(r -> r.getPath() != null)
				.forEach(r -> {
					try {
						Helper.revealInFileManager(r.getPath());
					} catch (IOException e) {
						log.error("Failed to reveal {} in file manager: {}", r.getFullPath(), e.getMessage(), e);
						Alerts.showError("Failed to reveal in file manager", "<html>Could not reveal <b>\"" + r.getFullPath() + "\"</b> in the file manager.<br/>Error: " + e.getMessage() + "</html>");
					}
				});
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

	/**
	 * Open the Alt+F7 Find File dialog for this panel. Builds the plugin-agnostic
	 * {@link FindFileContext} (current/other folder, marked items, volumes, and the local
	 * navigator) and hands the resulting {@link FindFileRequest} to {@link FindFileService}.
	 * The dialog is non-modal and anchored to the active window.
	 */
	private void openFindFileDialog(BaseNuclrPlugin other, List<NuclrResource> selectedResources) {

		NuclrResource otherFolder = other != null ? other.getCurrentResource() : null;
		List<NuclrResource> marked = selectedResources != null ? selectedResources : List.of();

		LocalResourceNavigator navigator = new LocalResourceNavigator(context, Helper::build);

		FindFileContext findContext = FindFileContext.builder()
				.currentFolder(this.currentFolder)
				.otherPanelFolder(otherFolder)
				.markedItems(marked)
				.volumesSupplier(this::volumeResources)
				.browser(navigator)
				.pathParser(navigator)
				.onSubmit(this::startFindSearch)
				.build();

		Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
		SwingUtilities.invokeLater(() -> new FindFileDialog(owner, findContext).setVisible(true));
	}

	/** Resolve the local volumes (drive/mount-point roots) as resources for the Volumes scope. */
	private List<NuclrResource> volumeResources() {
		var roots = new ArrayList<NuclrResource>();
		FileSystems.getDefault().getRootDirectories().forEach(p -> roots.add(Helper.build(context, p)));
		return roots;
	}

	/**
	 * Run a Find File search and stream the matches into a results window (the interim
	 * presentation until the dedicated results panel lands). Owns a per-search
	 * {@link FindFileService} that is released on completion.
	 */
	private void startFindSearch(FindFileRequest request) {

		log.info("Find File: {}", request);

		// Anchor to the main commander frame, not the (about-to-be-disposed) Find dialog,
		// otherwise disposing the dialog would dispose the results window with it.
		FindResultsWindow results = new FindResultsWindow(mainApplicationFrame(), request, this::navigateToResult,
				hits -> openResultsInTempPanel(request, hits));

		FindFileService service = new FindFileService(this);
		FindFileService.SearchHandle handle = service.search(request, results);
		results.bind(service, handle);
		results.setVisible(true);
	}

	/** The top-level commander frame, used to anchor Find windows independently of transient dialogs. */
	private static Window mainApplicationFrame() {
		for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
			if (frame.isShowing()) {
				return frame;
			}
		}
		return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
	}

	/**
	 * Open the whole Find result set in a temporary panel. Builds the synthetic temp-panel
	 * root (results + title + origin folder carried in its metadata) and navigates the
	 * focused panel to it via {@code filepanel.path.opened}; the host selects
	 * {@link TempFilePanelPlugin} for that resource.
	 */
	private void openResultsInTempPanel(FindFileRequest request, List<NuclrResource> results) {
		NuclrResource tempRoot = TempFilePanelPlugin.tempPanelResource(
				results, "Find: " + request.getNamePattern(), this.currentFolder);

		var payload = new java.util.HashMap<String, Object>();
		payload.put("resource", tempRoot);
		context.getEventBus().emit(this, "filepanel.path.opened", payload);
	}

	/**
	 * Navigate the focused panel to a Find result and put the cursor on it. Opens the
	 * resource's parent folder and selects the entry via the platform's
	 * {@code filepanel.path.opened} event (the {@code selectChild} payload tells the panel
	 * which child to focus after navigating).
	 */
	private void navigateToResult(NuclrResource resource) {
		if (resource == null || resource.getPath() == null) {
			return;
		}
		Path path = resource.getPath();
		Path parent = path.getParent();
		NuclrResource folder = parent != null ? Helper.build(context, parent) : resource;

		var payload = new java.util.HashMap<String, Object>();
		payload.put("resource", folder);
		payload.put("selectChild", resource);
		context.getEventBus().emit(this, "filepanel.path.opened", payload);
	}

	@Override
	public List<NuclrContextMenuItem> contextMenuItems(NuclrResource focusedResource, List<NuclrResource> selectedResources) {

		return List.of(
			NuclrContextMenuItem.builder().label("Open").actionType("filepanel.path.opened").build(),
			NuclrContextMenuItem.builder().label("Reveal in File Manager").actionType("filepanel.path.open.in.explorer").build(),
			NuclrContextMenuItem.builder().separator(true).build(),
			NuclrContextMenuItem.builder().label("Copy file(s)").actionType(ClipboardCopyFiles).build(),
			NuclrContextMenuItem.builder().label("Copy full path(s)").actionType(ClipboardCopyFullPaths).build(),
			NuclrContextMenuItem.builder().separator(true).build(),
			NuclrContextMenuItem.builder().label("Delete").actionType("filepanel.delete").build()
		);
	}

}
