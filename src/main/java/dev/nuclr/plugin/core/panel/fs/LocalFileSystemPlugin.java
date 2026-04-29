package dev.nuclr.plugin.core.panel.fs;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.JComponent;
import javax.swing.JOptionPane;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrPluginRole;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.core.panel.fs.plugin.LocalFilePanel;
import dev.nuclr.plugin.core.panel.fs.plugin.LocalMenuActionEvent;
import dev.nuclr.plugin.core.panel.fs.plugin.LocalMenuResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LocalFileSystemPlugin implements NuclrPlugin, NuclrEventListener {
	private static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
	private static final String GO_TO_PATH_SHORTCUT = IS_MAC ? "Shift+Cmd+G" : "Ctrl+Shift+G";

	private String uuid = java.util.UUID.randomUUID().toString();
	
	private static final String MENU_ACTION_EVENT_TYPE = "dev.nuclr.plugin.core.panel.fs.menuAction";
	private static final String THEME_UPDATED_EVENT_TYPE = "dev.nuclr.platform.theme.updated";
	private static final String OPEN_RESOURCE_EVENT_TYPE = "dev.nuclr.platform.resource.open";
	private static final String COPY_RESOURCES_EVENT_TYPE = "dev.nuclr.platform.resources.copy";
	private static final String MOVE_RESOURCES_EVENT_TYPE = "dev.nuclr.platform.resources.move";
	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.panel.fs";
	private static final String PLUGIN_NAME = "Local Filesystem Panel";
	private static final String PLUGIN_VERSION = "1.0.0";
	private static final String PLUGIN_DESCRIPTION = "Provides local filesystem roots (drives/mount points) to the file panel.";
	private static final String PLUGIN_AUTHOR = "Nuclr Development Team";
	private static final String PLUGIN_LICENSE = "Apache-2.0";
	private static final String PLUGIN_WEBSITE = "https://nuclr.dev";
	private static final String PLUGIN_PAGE_URL = "https://nuclr.dev/plugins/core/filepanel-fs.html";
	private static final String PLUGIN_DOC_URL = PLUGIN_PAGE_URL;

	private final CopyService copyService = new CopyService();
	private final MoveService moveService = new MoveService();

	private NuclrPluginContext context;
	private LocalFilePanel panel;

	@Override
	public String id() {
		return PLUGIN_ID;
	}

	@Override
	public String name() {
		return PLUGIN_NAME;
	}

	@Override
	public String version() {
		return PLUGIN_VERSION;
	}

	@Override
	public String description() {
		return PLUGIN_DESCRIPTION;
	}

	@Override
	public String author() {
		return PLUGIN_AUTHOR;
	}

	@Override
	public String license() {
		return PLUGIN_LICENSE;
	}

	@Override
	public String website() {
		return PLUGIN_WEBSITE;
	}

	@Override
	public String pageUrl() {
		return PLUGIN_PAGE_URL;
	}

	@Override
	public String docUrl() {
		return PLUGIN_DOC_URL;
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new LocalFilePanel(this, this::openDocumentation);
			panel.setEventBus(this.context.getEventBus());
			panel.setThemeScheme(this.context.getTheme());
		}
		return panel;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		List<NuclrMenuResource> items = new ArrayList<>();
		boolean isDirectory = source != null && source.getPath() != null && Files.isDirectory(source.getPath());
		addDefaultMenuItems(items, source, isDirectory);
		addAltMenuItems(items, source);
		addCtrlMenuItems(items, source);
		addShiftMenuItems(items, source, isDirectory);
		return items;
	}

	@Override
	public void load(NuclrPluginContext context, boolean template) {
		this.context = context;
		if (false == template) {
			context.getEventBus().subscribe(this);
		}
		log.info("Local filesystem panel plugin loaded");
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		log.info("Local filesystem panel plugin unloaded");
	}

	@Override
	public List<NuclrResourcePath> getChangeDriveResources() {
		var resources = new ArrayList<NuclrResourcePath>();
		FileSystems.getDefault().getRootDirectories().forEach(p -> {
			var res = new NuclrResourcePath();
			res.setPath(p);
			res.setName(p.toString());
			resources.add(res);
		});
		return resources;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (cancelled != null && cancelled.get()) {
			return false;
		}
		if (resource == null) {
			return false;
		}
		LocalFilePanel view = (LocalFilePanel) panel();
		Path path = resource.getPath();
		if (path != null && Files.isDirectory(path)) {
			view.showDirectory(path, selectionPath(resource));
			return true;
		}
		return false;
	}

	private static Path selectionPath(NuclrResourcePath resource) {
		if (resource == null || resource.getMetadata() == null) {
			return null;
		}
		String raw = resource.getMetadata().get("selectedPath");
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Path.of(raw);
		} catch (InvalidPathException ex) {
			return null;
		}
	}

	public boolean requestOpen(Path path) {
		if (context == null || path == null) {
			return false;
		}
		NuclrResourcePath resource = new NuclrResourcePath();
		resource.setPath(path);
		resource.setName(path.getFileName() != null ? path.getFileName().toString() : path.toString());
		Map<String, Object> event = new HashMap<>();
		event.put("sourceProvider", this);
		event.put("resource", resource);
		context.getEventBus().emit(this.id(), OPEN_RESOURCE_EVENT_TYPE, event);
		return false;
	}

	public void copyIntoCurrentPanel(List<NuclrResourcePath> paths) {
		if (panel != null && panel.isShowing()) {
			copyService.copy(panel, paths, panel.getCurrentDirectory());
		}
	}

	public void moveIntoCurrentPanel(List<NuclrResourcePath> paths) {
		if (panel != null && panel.isShowing()) {
			Runnable refresh = () -> panel.showDirectory(panel.getCurrentDirectory());
			moveService.move(panel, paths, panel.getCurrentDirectory(), refresh);
		}
	}

	private static final long TEXT_PASTE_WARN_BYTES = 1024L * 1024; // 1 MB

	public void pasteFromClipboard() {
		if (panel == null || !panel.isShowing() || panel.getCurrentDirectory() == null) return;

		Transferable contents;
		try {
			contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
		} catch (Exception e) {
			log.warn("Could not access clipboard: {}", e.getMessage());
			return;
		}
		if (contents == null) return;

		// Priority 1: file list
		if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			try {
				@SuppressWarnings("unchecked")
				List<File> files = (List<File>) contents.getTransferData(DataFlavor.javaFileListFlavor);
				List<NuclrResourcePath> paths = files.stream()
						.filter(f -> f != null)
						.map(f -> new NuclrResourcePath(f.toPath()))
						.collect(Collectors.toList());
				if (!paths.isEmpty()) {
					copyIntoCurrentPanel(paths);
				}
			} catch (Exception e) {
				log.warn("Could not paste files from clipboard: {}", e.getMessage());
			}
			return;
		}

		// Priority 2: text
		if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
			try {
				String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
				pasteTextAsFile(text);
			} catch (Exception e) {
				log.warn("Could not paste text from clipboard: {}", e.getMessage());
			}
		}
	}

	private void pasteTextAsFile(String text) {
		long approxBytes = (long) text.length() * 2;
		if (approxBytes > TEXT_PASTE_WARN_BYTES) {
			int choice = JOptionPane.showConfirmDialog(
					panel,
					String.format("Clipboard text is approximately %.1f MB. Write to file anyway?",
							approxBytes / (1024.0 * 1024.0)),
					"Large Clipboard Content",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION) return;
		}

		String fileName = (String) JOptionPane.showInputDialog(
				panel,
				"Save clipboard text as:",
				"Paste as File",
				JOptionPane.PLAIN_MESSAGE,
				null, null,
				"clipboard.txt");

		if (fileName == null || fileName.isBlank()) return;

		Path target = panel.getCurrentDirectory().resolve(fileName.strip());
		if (Files.exists(target)) {
			int choice = JOptionPane.showConfirmDialog(
					panel,
					"\"" + target.getFileName() + "\" already exists. Overwrite?",
					"File Exists",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION) return;
		}

		try {
			Files.writeString(target, text);
			panel.showDirectory(panel.getCurrentDirectory());
		} catch (IOException e) {
			log.error("Could not write clipboard text to file: {}", e.getMessage());
			JOptionPane.showMessageDialog(panel,
					"Could not create file: " + e.getMessage(),
					"Error", JOptionPane.ERROR_MESSAGE);
		}
	}

	private void emitCopyOrCopyIntoCurrentPanel(List<NuclrResourcePath> paths) {
		AtomicBoolean accepted = new AtomicBoolean(false);
		Map<String, Object> payload = new HashMap<>();
		payload.put("paths", paths);
		payload.put("accepted", accepted);
		context.getEventBus().emit(this, "fs.copy", payload);
		if (!accepted.get()) {
			copyIntoCurrentPanel(paths);
		}
	}

	private static void markAccepted(Map<String, Object> event) {
		if (event != null && event.get("accepted") instanceof AtomicBoolean accepted) {
			accepted.set(true);
		}
	}

	@Override
	public boolean isMessageSupported(String type) {
		return true;
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> event) {

		// Ignore its own events
		if (source == this || source == panel) {
			return;
		}

		log.info("Received message - Source: {}, Type: {}, Event: {}", source, type, event);

		if ("fs.copy".equals(type)) {
			if (panel != null && panel.isShowing()) {
				@SuppressWarnings("unchecked")
				List<NuclrResourcePath> paths = (List<NuclrResourcePath>) event.get("paths");
				markAccepted(event);
				copyIntoCurrentPanel(paths);
			}
			return;
		}

		if ("fs.move".equals(type)) {
			if (panel != null && panel.isShowing()) {
				@SuppressWarnings("unchecked")
				List<NuclrResourcePath> paths = (List<NuclrResourcePath>) event.get("paths");
				markAccepted(event);
				Runnable refreshSource = buildSourceRefresh(source, event);
				moveService.move(panel, paths, panel.getCurrentDirectory(), refreshSource);
			}
			return;
		}

		if (THEME_UPDATED_EVENT_TYPE.equals(type) && panel != null) {
			panel.repaint();
			return;
		}
		if (!MENU_ACTION_EVENT_TYPE.equals(type) || !isFocused()) {
			return;
		}
		LocalMenuActionEvent actionEvent = toMenuActionEvent(event);
		if (actionEvent == null) {
			return;
		}
			if ("makeFolder".equals(actionEvent.getActionId())) {
			Path sourcePath = actionEvent.getSource() != null ? actionEvent.getSource().getPath() : null;
			((LocalFilePanel) panel()).createNewFolder(sourcePath);
			return;
		}
		if ("delete".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).deleteSelection(false);
			return;
		}
		if ("deletePermanent".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).deleteSelection(true);
			return;
		}
		if ("copy".equals(actionEvent.getActionId())) {
			emitCopyOrCopyIntoCurrentPanel(((LocalFilePanel) panel()).getSelectedResources());
			return;
		}
		if ("move".equals(actionEvent.getActionId())) {
			Map<String, Object> payload = new HashMap<>();
			payload.put("sourceProvider", this);
			payload.put("resources", ((LocalFilePanel) panel()).getSelectedResources());
			context.getEventBus().emit(this.id(), MOVE_RESOURCES_EVENT_TYPE, payload);
			return;
		}
		if ("sortByName".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortByName();
			return;
		}
		if ("sortByExtension".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortByExtension();
			return;
		}
		if ("sortByModifiedDate".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortByModifiedDate();
			return;
		}
		if ("sortBySize".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortBySize();
			return;
		}
		if ("unsort".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).unsort();
			return;
		}
		if ("sortByCreateDate".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortByCreateDate();
			return;
		}
		if ("sortByAccessTime".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).sortByAccessTime();
			return;
		}
		if ("sortMenu".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).showSortMenu();
			return;
		}
		if ("goToPath".equals(actionEvent.getActionId())) {
			((LocalFilePanel) panel()).showGoToPathDialog();
			return;
		}
		if ("help".equals(actionEvent.getActionId())) {
			openDocumentation();
		}
	}

	private static NuclrMenuResource menu(String name, String keyStroke, String actionId, NuclrResourcePath source) {
		return new LocalMenuResource(name, keyStroke, MENU_ACTION_EVENT_TYPE);
	}

	public void openDocumentation() {
		String docUrl = docUrl();
		if (docUrl == null || docUrl.isBlank()) {
			log.warn("No documentation URL configured for {}", id());
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			log.warn("Desktop browse is not supported, cannot open {}", docUrl);
			return;
		}
		try {
			Desktop.getDesktop().browse(URI.create(docUrl));
		} catch (Exception ex) {
			log.warn("Cannot open documentation URL {}: {}", docUrl, ex.getMessage());
		}
	}

	private static LocalMenuActionEvent toMenuActionEvent(Map<String, Object> event) {
		if (event == null) {
			return null;
		}
		Object label = event.get("label");
		Object resource = event.get("resource");
		NuclrResourcePath source = resource instanceof NuclrResourcePath pathResource ? pathResource : null;
		String actionId = actionIdFromLabel(label instanceof String text ? text : null);
		return actionId != null ? new LocalMenuActionEvent(actionId, source) : null;
	}

	private static String actionIdFromLabel(String label) {
		if (label == null) {
			return null;
		}
		return switch (label) {
		case "Help" -> "help";
		case "User Menu" -> "userMenu";
		case "View" -> "view";
		case "Edit" -> "edit";
		case "Copy" -> "copy";
		case "Move", "Rename/Move" -> "move";
		case "Make Folder" -> "makeFolder";
		case "Delete" -> "delete";
		case "Quit" -> "quit";
		case "Plugins" -> "plugins";
		case "Screen" -> "screen";
		case "Left" -> "left";
		case "Right" -> "right";
		case "Find" -> "find";
		case "History" -> "history";
		case "Fullscreen" -> "fullscreen";
		case "Tree" -> "tree";
		case "View History" -> "viewHistory";
		case "Folder History" -> "folderHistory";
		case "Hide Left" -> "hideLeft";
		case "Hide Right" -> "hideRight";
		case "Sort by name" -> "sortByName";
		case "Sort by extension" -> "sortByExtension";
		case "Sort by modified" -> "sortByModifiedDate";
		case "Sort by size" -> "sortBySize";
		case "Unsort" -> "unsort";
		case "Sort by create" -> "sortByCreateDate";
		case "Sort by access" -> "sortByAccessTime";
		case "Sort menu" -> "sortMenu";
		case "Go to path" -> "goToPath";
		case "Create archive" -> "createArchive";
		case "Extract archive" -> "extractArchive";
		case "Create file" -> "createFile";
		case "Delete Permanently" -> "deletePermanent";
		case "Selection up" -> "selectionUp";
		default -> null;
		};
	}

	private static void addDefaultMenuItems(List<NuclrMenuResource> items, NuclrResourcePath source,
			boolean isDirectory) {
		items.add(menu("Help", "F1", "help", source));
		items.add(menu("User Menu", "F2", "userMenu", source));
		items.add(menu("View", "F3", "view", source));
		items.add(menu("Edit", "F4", "edit", source));
		items.add(menu("Copy", "F5", "copy", source));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "move", source));
		items.add(menu("Make Folder", "F7", "makeFolder", source));
		items.add(menu("Delete", "F8", "delete", source));
		items.add(menu("Quit", "F10", "quit", source));
		items.add(menu("Plugins", "F11", "plugins", source));
		items.add(menu("Screen", "F12", "screen", source));
	}

	private static void addAltMenuItems(List<NuclrMenuResource> items, NuclrResourcePath source) {
		items.add(menu("Left Panel", "Alt+F1", "left", source));
		items.add(menu("Right Panel", "Alt+F2", "right", source));
		items.add(menu("Find", "Alt+F7", "find", source));
		items.add(menu("History", "Alt+F8", "history", source));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen", source));
		items.add(menu("Tree", "Alt+F10", "tree", source));
		items.add(menu("View History", "Alt+F11", "viewHistory", source));
		items.add(menu("Folder History", "Alt+F12", "folderHistory", source));
	}

	private static void addCtrlMenuItems(List<NuclrMenuResource> items, NuclrResourcePath source) {
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft", source));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight", source));
		items.add(menu("Sort by name", "Ctrl+F3", "sortByName", source));
		items.add(menu("Sort by extension", "Ctrl+F4", "sortByExtension", source));
		items.add(menu("Sort by modified", "Ctrl+F5", "sortByModifiedDate", source));
		items.add(menu("Sort by size", "Ctrl+F6", "sortBySize", source));
		items.add(menu("Unsort", "Ctrl+F7", "unsort", source));
		items.add(menu("Sort by create", "Ctrl+F8", "sortByCreateDate", source));
		items.add(menu("Sort by access", "Ctrl+F9", "sortByAccessTime", source));
		items.add(menu("Sort menu", "Ctrl+F12", "sortMenu", source));
	}

	private static void addShiftMenuItems(List<NuclrMenuResource> items, NuclrResourcePath source,
			boolean isDirectory) {
		items.add(menu("Create archive", "Shift+F1", "createArchive", source));
		items.add(menu("Extract archive", "Shift+F2", "extractArchive", source));
		items.add(menu("Create file", "Shift+F4", "createFile", source));
		items.add(menu("Go to path", GO_TO_PATH_SHORTCUT, "goToPath", source));
		items.add(menu("Delete Permanently", "Shift+F8", "deletePermanent", source));
		items.add(menu("Selection up", "Shift+F12", "selectionUp", source));
	}

	@Override
	public void closeResource() {
		// The local filesystem panel keeps state in its Swing component and does not
		// require explicit teardown here.
	}

	@Override
	public int priority() {
		return 0;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		if (panel != null) {
			panel.setThemeScheme(themeScheme);
			panel.repaint();
		}
	}

	@Override
	public boolean onFocusGained() {
		((LocalFilePanel) panel()).setPluginFocused(true);
		return true;
	}

	@Override
	public void onFocusLost() {
		if (panel != null) {
			panel.setPluginFocused(false);
		}
	}

	@Override
	public boolean supports(NuclrResourcePath resource) {
		return resource != null && resource.getPath() != null && Files.isDirectory(resource.getPath());
	}

	@Override
	public boolean singleton() {
		return false;
	}

	@Override
	public boolean isFocused() {
		return this.panel.isFocusOwner() || this.panel.getTable().isFocusOwner();
	}

	/**
	 * Build a runnable that refreshes the source panel after a move completes.
	 *
	 * <p>
	 * The {@code source} argument of {@code handleMessage} is the
	 * {@link LocalFilePanel} that emitted the {@code fs.move} event, so we can call
	 * {@code showDirectory} on it directly to update its listing.
	 *
	 * @return a refresh runnable, or {@code null} if the source is not a local
	 *         panel
	 */
	private static Runnable buildSourceRefresh(Object source, Map<String, Object> event) {
		if (event != null && event.get("refreshSource") instanceof Runnable refreshSource) {
			return refreshSource;
		}
		if (!(source instanceof LocalFilePanel sourcePanel)) {
			return null;
		}
		Path sourceDir = sourcePanel.getCurrentDirectory();
		if (sourceDir == null) {
			return null;
		}
		return () -> sourcePanel.showDirectory(sourceDir);
	}

	@Override
	public NuclrPluginRole role() {
		return NuclrPluginRole.FilePanel;
	}

	@Override
	public NuclrResourcePath getCurrentResource() {
		return new NuclrResourcePath(this.panel.getCurrentDirectory());
	}

	@Override
	public String uuid() {
		return uuid;
	}

}
