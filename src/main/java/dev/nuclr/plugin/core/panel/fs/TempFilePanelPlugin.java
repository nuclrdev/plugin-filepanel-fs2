package dev.nuclr.plugin.core.panel.fs;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.service.Alerts;
import lombok.extern.slf4j.Slf4j;

/**
 * FAR-style "Temporary Panel": a virtual folder whose children are an arbitrary,
 * caller-supplied list of resources (e.g. Find File hits) rather than the contents of a
 * real directory. The user can copy / move / delete / view the entries exactly as in a
 * normal panel.
 *
 * <h2>Why it extends {@link LocalFileSystemPlugin}</h2>
 * The hits are real local {@link FileNuclrResource}s with real paths, so every operation
 * the FS plugin already implements — {@code act()} copy/move/delete routing, F3/F4 view,
 * context menus, selection summaries — works unchanged. This subclass only changes
 * <em>where the listing comes from</em>.
 *
 * <h2>How the list reaches the plugin</h2>
 * The host instantiates panel plugins reflectively (no-arg ctor) and recreates them per
 * navigation, so the data cannot ride in the constructor or instance state. Instead it
 * travels on a synthetic, path-less <b>root resource</b> built by
 * {@link #tempPanelResource(List, String, NuclrResource)} — the hit list lives in that
 * resource's {@code metadata}. Flow:
 * <pre>
 *   results window
 *     -&gt; root = TempFilePanelPlugin.tempPanelResource(hits, title, originFolder)
 *     -&gt; eventBus.emit("filepanel.path.opened", {resource: root})
 *     -&gt; host: getFilePanelPluginsByResource(root) picks THIS plugin via supports(root)
 *     -&gt; host: openResource(root) -&gt; returns the (still-existing) hits
 * </pre>
 *
 * <h2>Registration</h2>
 * List this class alongside {@link LocalFileSystemPlugin} under {@code panelProviders} in
 * {@code plugin.json}; the registry discovers it as a separate template keyed by
 * {@link #id()}.
 *
 * <p>Implemented: the synthetic root + listing, the {@code ..}-to-origin exit, the
 * copy/move destination guard, and a two-column view (Name shown as the full path, Size).
 */
@Slf4j
public class TempFilePanelPlugin extends LocalFileSystemPlugin {

	public static final String PluginId = "dev.nuclr.plugin.core.panel.fs.temp";
	protected static final String PluginName = "Temporary Local Filesystem Panel";
	protected static final String PluginDescription = "Provides temporary local filesystem panel.";

	/** Metadata flag marking a resource as a temp-panel root (claimed only by this plugin). */
	private static final String MARKER = "nuclr.temp.panel";
	/** Metadata key holding the {@code List<NuclrResource>} of hits. */
	private static final String RESULTS_KEY = "nuclr.temp.panel.results";
	/** Metadata key holding the panel title (e.g. the search mask). */
	private static final String TITLE_KEY = "nuclr.temp.panel.title";
	/** Metadata key holding the folder to return to when the user leaves via "..". */
	private static final String ORIGIN_KEY = "nuclr.temp.panel.origin";

	/** Copy/move actions the superclass executes against its (here meaningless) currentFolder. */
	private static final String ACCEPT_COPY = "accept.copy";
	private static final String ACCEPT_MOVE = "accept.move";

	/** Temp panel shows just Name (rendered as the full path) and Size. */
	private static final List<String> COLUMNS = List.of("Name", "Size");

	/** The synthetic root currently shown (path-less; carries the hit list in metadata). */
	private NuclrResource tempRoot;

	/** Captured context — the superclass keeps its own copy private, and we need it to build the ".." entry. */
	private NuclrPluginContext ctx;

	@Override
	public void preinit(NuclrPluginContext context) {
		super.preinit(context);
		this.ctx = context;
	}

	// Redeclaring the constants above only *shadows* the superclass fields — the inherited
	// id()/name()/description() still read the superclass values, so override them here.

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	/**
	 * Claim only the synthetic temp-panel root. Real paths fall through to
	 * {@link LocalFileSystemPlugin}, so navigating INTO a folder hit leaves the temp panel
	 * (FAR behavior) for free.
	 */
	@Override
	public boolean supports(NuclrResource resource) {
		return resource != null
				&& resource.getPath() == null
				&& Boolean.TRUE.equals(resource.getMetadata().get(MARKER));
	}

	/** A results panel has no drive/location selector. */
	@Override
	public MenuItemsHolder getPluginMenuItems() {
		return null;
	}

	/**
	 * Return the stored hits instead of listing a directory. Entries whose backing file no
	 * longer exists are dropped, so deletes/moves self-heal on the next refresh.
	 */
	@Override
	public NuclrResourceData openResource(NuclrResource root, AtomicBoolean cancelled, EntrySink sink) {

		if (root == null || !supports(root)) {
			return null;
		}
		this.tempRoot = root;

		NuclrResourceData data = new NuclrResourceData();
		data.setColumnNames(COLUMNS);
		if (sink != null) {
			sink.columns(COLUMNS);
		}

		// Synthetic ".." that returns to the folder the search started in (the temp root has
		// no real parent). It is a normal FileNuclrResource for the origin path, so opening it
		// is claimed by the FS plugin and naturally leaves the temp panel.
		NuclrResource origin = origin(root);
		if (origin != null && origin.getPath() != null && ctx != null) {
			FileNuclrResource up = Helper.build(ctx, origin.getPath());
			up.setName("..");
			data.getEntries().add(up);
			if (sink != null) {
				sink.add(up);
			}
		}

		for (NuclrResource hit : results(root)) {
			if (cancelled != null && cancelled.get()) {
				break;
			}
			if (hit.getPath() != null && !Files.exists(hit.getPath())) {
				continue; // stale (deleted / moved out) — drop it
			}
			// Show the full path in the Name column (the table reads metadata["Name"]).
			// getName() stays the bare filename, which copy/move use for the target name.
			hit.getMetadata().put("Name", fullPathName(hit));
			data.getEntries().add(hit);
			if (sink != null) {
				sink.add(hit);
			}
		}
		return data;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return tempRoot;
	}

	@Override
	public String getWindowTitle() {
		return title(tempRoot);
	}

	@Override
	public String getCurrentLocationDisplayText() {
		return title(tempRoot);
	}

	/**
	 * Guard against being a copy/move <em>destination</em>. The inherited {@code act()} would
	 * write into the superclass {@code currentFolder} (a real drive root from preinit), which
	 * is meaningless for a virtual results panel. Reject those, delegate everything else
	 * (copy/move <em>from</em> here, delete, view, …) to the superclass.
	 */
	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {

		if (ACCEPT_COPY.equals(actionType) || ACCEPT_MOVE.equals(actionType)) {
			Alerts.showError("Temporary panel",
					"A temporary results panel is a virtual collection and cannot be a copy or move destination.");
			return;
		}

		super.act(other, actionType, selectedResources, focusedResource, data, callback);
	}

	// ------------------------------------------------------------------
	// Synthetic root: built by the results window, read back here.
	// ------------------------------------------------------------------

	/**
	 * Build the path-less root resource that, when navigated to, makes the host show
	 * {@code hits} in a temporary panel.
	 *
	 * @param hits   the result resources (real {@link FileNuclrResource}s)
	 * @param title  panel title (e.g. the search mask)
	 * @param origin folder to return to when leaving via ".." (may be {@code null})
	 */
	public static NuclrResource tempPanelResource(List<NuclrResource> hits, String title, NuclrResource origin) {
		TempPanelResource root = new TempPanelResource();
		root.setName(title != null && !title.isBlank() ? title : "Search results");
		root.setFolder(true);
		root.getMetadata().put(MARKER, Boolean.TRUE);
		root.getMetadata().put(RESULTS_KEY, new ArrayList<>(hits));
		root.getMetadata().put(TITLE_KEY, root.getName());
		if (origin != null) {
			root.getMetadata().put(ORIGIN_KEY, origin);
		}
		return root;
	}

	@SuppressWarnings("unchecked")
	private static List<NuclrResource> results(NuclrResource root) {
		Object value = root.getMetadata().get(RESULTS_KEY);
		return value instanceof List<?> list ? (List<NuclrResource>) list : List.of();
	}

	private static String title(NuclrResource root) {
		if (root == null) {
			return "Temporary Panel";
		}
		Object value = root.getMetadata().get(TITLE_KEY);
		return value instanceof String s ? s : "Temporary Panel";
	}

	private static NuclrResource origin(NuclrResource root) {
		Object value = root.getMetadata().get(ORIGIN_KEY);
		return value instanceof NuclrResource r ? r : null;
	}

	/** Full path for the Name column; falls back to the display name when there is no path. */
	private static String fullPathName(NuclrResource resource) {
		return resource.getFullPath() != null ? resource.getFullPath() : resource.getName();
	}

	/** Path-less, in-memory root for a temporary panel; its children live in {@code metadata}. */
	private static final class TempPanelResource extends NuclrResource {

		private static final long serialVersionUID = 1L;

		TempPanelResource() {
			super(null);
			setUuid("temp-panel:" + java.util.UUID.randomUUID());
		}
	}
}
