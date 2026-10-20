package dev.nuclr.plugin.core.panel.fs;

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import mslinks.LinkInfo;
import mslinks.LinkTargetIDList;
import mslinks.ShellLink;
import mslinks.ShellLinkException;
import mslinks.ShellLinkHeader;
import mslinks.data.CNRLink;
import mslinks.data.FileAttributesFlags;
import mslinks.data.HotKeyFlags;
import mslinks.data.LinkFlags;
import mslinks.data.VolumeID;
import mslinks.extra.Tracker;

public class QuickViewLnkPlugin implements QuickViewNuclrPlugin {

	private static final Logger LOG = LoggerFactory.getLogger(QuickViewLnkPlugin.class);
	private static final Pattern WINDOWS_ENV_VAR = Pattern.compile("%([^%]+)%");
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	protected String uuid = UUID.randomUUID().toString();


	private NuclrPluginContext context;
	private LnkQuickViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrResource currentResource;
	private NuclrThemeScheme theme;

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new LnkQuickViewPanel();
			panel.applyTheme(theme);
		}
		return panel;
	}


	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (!supports(resource)) {
			return false;
		}
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		currentResource = resource;
		currentCancelled = cancelled;
		panel();
		return panel.load(resource, cancelled);
	}

	@Override
	public boolean supports(NuclrResource resource) {
		if (resource == null || resource.isFolder()) {
			return false;
		}

		String name = resource.getName();
		if ((name == null || name.isBlank()) && resource.getPath() != null) {
			Path fileName = resource.getPath().getFileName();
			name = fileName != null ? fileName.toString() : resource.getPath().toString();
		}
		return name != null && name.toLowerCase(Locale.ROOT).endsWith(".lnk");
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		if (context != null) {
			Messages.use(context.getLocale());
		}
		updateTheme(context != null ? context.getTheme() : null);
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void init() {
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
		this.theme = themeScheme;
		if (panel != null) {
			panel.applyTheme(themeScheme);
		}
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		currentResource = null;
		if (panel != null) {
			panel.clear();
		}
	}

	@Override
	public void unload() {
		closeResource();
		panel = null;
		context = null;
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return currentResource;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public String getWindowTitle() {
		return tr("lnk.window.title", currentResource != null ? currentResource.getName() : "");
	}


	private static final class LnkQuickViewPanel extends JPanel {

		private LnkTheme theme = LnkTheme.from(null);
		private volatile Thread loadThread;
		private ShortcutInfo currentInfo;
		private String currentMessage = tr("lnk.message.none");
		private String currentError;

		LnkQuickViewPanel() {
			super(new BorderLayout());
			setOpaque(true);
			showMessage(currentMessage);
		}

		void applyTheme(NuclrThemeScheme themeScheme) {
			this.theme = LnkTheme.from(themeScheme);
			setBackground(theme.background());
			if (currentInfo != null) {
				showInfo(currentInfo);
			} else if (currentError != null) {
				showError(currentError);
			} else {
				showMessage(currentMessage);
			}
		}

		boolean load(NuclrResource resource, AtomicBoolean cancelled) {
			Thread previous = loadThread;
			if (previous != null) {
				previous.interrupt();
			}

			currentInfo = null;
			currentError = null;
			showMessage(tr("lnk.message.reading"));

			loadThread = Thread.ofVirtual()
					.name("lnk-quick-view-" + safeThreadName(resource))
					.start(() -> {
						try {
							ShortcutInfo info = ShortcutInfo.read(resource);
							if (isCancelled(cancelled)) {
								return;
							}
							SwingUtilities.invokeLater(() -> {
								if (!isCancelled(cancelled)) {
									showInfo(info);
								}
							});
						} catch (IOException | ShellLinkException | RuntimeException e) {
							if (isCancelled(cancelled)) {
								return;
							}
							LOG.warn("Failed to inspect shortcut [{}]: {}", resource.getName(), e.getMessage(), e);
							String message = friendlyError(e);
							SwingUtilities.invokeLater(() -> {
								if (!isCancelled(cancelled)) {
									showError(message);
								}
							});
						}
					});
			return true;
		}

		void clear() {
			Thread previous = loadThread;
			if (previous != null) {
				previous.interrupt();
			}
			loadThread = null;
			currentInfo = null;
			currentError = null;
			showMessage("");
		}

		private void showMessage(String message) {
			currentInfo = null;
			currentError = null;
			currentMessage = message == null ? "" : message;

			JPanel empty = new JPanel(new GridBagLayout());
			empty.setBackground(theme.background());

			JPanel stack = new JPanel();
			stack.setOpaque(false);
			stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));

			JLabel icon = new JLabel(new ShortcutGlyph(54, theme));
			icon.setAlignmentX(Component.CENTER_ALIGNMENT);
			JLabel label = new JLabel(currentMessage, SwingConstants.CENTER);
			label.setAlignmentX(Component.CENTER_ALIGNMENT);
			label.setForeground(theme.muted());
			label.setFont(theme.bodyFont().deriveFont(Font.PLAIN, 13f));

			stack.add(icon);
			stack.add(Box.createVerticalStrut(10));
			stack.add(label);
			empty.add(stack);

			setContent(empty);
		}

		private void showError(String message) {
			currentInfo = null;
			currentError = message;

			JPanel body = new JPanel(new GridBagLayout());
			body.setBackground(theme.background());
			body.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

			RoundedPanel panel = new RoundedPanel(theme.surface(), theme.border(), 8);
			panel.setLayout(new BorderLayout(12, 0));
			panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));

			JLabel icon = new JLabel(new ShortcutGlyph(46, theme));
			panel.add(icon, BorderLayout.WEST);

			JPanel text = new JPanel();
			text.setOpaque(false);
			text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));

			JLabel title = new JLabel(tr("lnk.error.title"));
			title.setForeground(theme.foreground());
			title.setFont(theme.bodyFont().deriveFont(Font.BOLD, 15f));

			JTextArea detail = valueArea(message, theme, false);
			detail.setForeground(theme.muted());

			text.add(title);
			text.add(Box.createVerticalStrut(4));
			text.add(detail);
			panel.add(text, BorderLayout.CENTER);

			body.add(panel);
			setContent(body);
		}

		private void showInfo(ShortcutInfo info) {
			currentInfo = info;
			currentError = null;

			ScrollablePage page = new ScrollablePage(theme.background());
			page.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

			page.add(new HeaderPanel(info, theme));
			page.add(Box.createVerticalStrut(10));

			addSection(page, tr("lnk.section.target"), info.targetRows());
			addSection(page, tr("lnk.section.launch"), info.launchRows());
			addSection(page, tr("lnk.section.icon"), info.iconRows());
			addSection(page, tr("lnk.section.source"), info.sourceRows());
			addSection(page, tr("lnk.section.timestamps"), info.timestampRows());
			addSection(page, tr("lnk.section.location"), info.locationRows());
			addChipSection(page, tr("lnk.section.link_flags"), info.linkFlags());
			addChipSection(page, tr("lnk.section.attributes"), info.attributeFlags());
			addSection(page, tr("lnk.section.notes"), info.noteRows());

			page.add(Box.createVerticalGlue());

			JScrollPane scroll = new JScrollPane(
					page,
					ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
					ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
			scroll.setBorder(null);
			scroll.getViewport().setBackground(theme.background());
			scroll.getVerticalScrollBar().setUnitIncrement(18);

			setContent(scroll);
			SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(0));
		}

		private void addSection(JPanel page, String title, List<InfoRow> rows) {
			if (rows.isEmpty()) {
				return;
			}
			SectionPanel section = new SectionPanel(title, theme);
			rows.forEach(section::addRow);
			page.add(section);
			page.add(Box.createVerticalStrut(8));
		}

		private void addChipSection(JPanel page, String title, List<String> chips) {
			if (chips.isEmpty()) {
				return;
			}
			SectionPanel section = new SectionPanel(title, theme);
			section.addChips(chips);
			page.add(section);
			page.add(Box.createVerticalStrut(8));
		}

		private void setContent(Component component) {
			Runnable swap = () -> {
				removeAll();
				add(component, BorderLayout.CENTER);
				revalidate();
				repaint();
			};
			if (SwingUtilities.isEventDispatchThread()) {
				swap.run();
			} else {
				SwingUtilities.invokeLater(swap);
			}
		}

		private static String safeThreadName(NuclrResource resource) {
			String name = resource != null ? resource.getName() : "shortcut";
			if (name == null || name.isBlank()) {
				name = "shortcut";
			}
			return name.replaceAll("[^A-Za-z0-9._-]", "_");
		}

		private static String friendlyError(Exception e) {
			String message = e.getMessage();
			if (message == null || message.isBlank()) {
				message = e.getClass().getSimpleName();
			}
			if (message.length() > 320) {
				message = message.substring(0, 320) + "...";
			}
			return message;
		}
	}

	private record ShortcutInfo(
			String title,
			String subtitle,
			String target,
			TargetStatus targetStatus,
			List<InfoRow> targetRows,
			List<InfoRow> launchRows,
			List<InfoRow> iconRows,
			List<InfoRow> sourceRows,
			List<InfoRow> timestampRows,
			List<InfoRow> locationRows,
			List<String> linkFlags,
			List<String> attributeFlags,
			List<InfoRow> noteRows) {

		static ShortcutInfo read(NuclrResource resource) throws IOException, ShellLinkException {
			ShellLink link = readShellLink(resource);
			Path sourcePath = resource.getPath();
			if (sourcePath != null) {
				link.setLinkFileSource(sourcePath);
			}

			ShellLinkHeader header = link.getHeader();
			LinkInfo linkInfo = link.getLinkInfo();
			LinkTargetIDList targetIdList = link.getTargetIdList();

			String resolvedTarget = clean(safe(link::resolveTarget));
			String linkInfoPath = linkInfo != null ? clean(safe(linkInfo::buildPath)) : null;
			String idListPath = targetIdList != null && targetIdList.canBuildPath()
					? clean(safe(targetIdList::buildPath))
					: null;
			String environmentTarget = environmentTarget(link);
			String relativePath = clean(link.getRelativePath());
			String target = firstNonBlank(resolvedTarget, linkInfoPath, environmentTarget, relativePath, idListPath);

			TargetStatus status = QuickViewLnkPlugin.targetStatus(target, sourcePath);
			List<String> notes = new ArrayList<>();
			if (target == null) {
				notes.add(tr("lnk.note.no_target"));
			} else if (status.tone() == Tone.WARNING) {
				notes.add(status.detail());
			} else if (status.tone() == Tone.NEUTRAL && status.detail() != null) {
				notes.add(status.detail());
			}

			List<InfoRow> targetRows = new ArrayList<>();
			add(targetRows, tr("lnk.row.resolved_target"), target, true);
			add(targetRows, tr("lnk.row.target_status"), status.label(), false);
			add(targetRows, tr("lnk.row.status_detail"), status.detail(), false);
			add(targetRows, tr("lnk.row.checked_path"), status.checkedPath(), true);
			addIfDifferent(targetRows, tr("lnk.row.linkinfo_path"), linkInfoPath, target);
			addIfDifferent(targetRows, tr("lnk.row.idlist_path"), idListPath, target);
			addIfDifferent(targetRows, tr("lnk.row.environment_target"), environmentTarget, target);
			addIfDifferent(targetRows, tr("lnk.row.relative_path"), relativePath, target);

			List<InfoRow> launchRows = new ArrayList<>();
			add(launchRows, tr("lnk.row.description"), clean(link.getName()), false);
			add(launchRows, tr("lnk.row.arguments"), clean(link.getCMDArgs()), true);
			add(launchRows, tr("lnk.row.working_dir"), clean(link.getWorkingDir()), true);
			add(launchRows, tr("lnk.row.window_mode"), header != null ? showCommand(header.getShowCommand()) : null, false);
			add(launchRows, tr("lnk.row.hotkey"), header != null ? hotkey(header.getHotKeyFlags()) : null, false);
			add(launchRows, tr("lnk.row.run_behavior"), header != null ? runBehavior(header.getLinkFlags()) : null, false);

			List<InfoRow> iconRows = new ArrayList<>();
			add(iconRows, tr("lnk.row.icon_location"), clean(link.getIconLocation()), true);
			add(iconRows, tr("lnk.row.icon_environment"), iconEnvironment(link), true);
			add(iconRows, tr("lnk.row.icon_index"), header != null ? Integer.toString(header.getIconIndex()) : null, false);

			List<InfoRow> sourceRows = new ArrayList<>();
			add(sourceRows, tr("lnk.row.shortcut_file"), sourcePath(resource), true);
			add(sourceRows, tr("lnk.row.shortcut_size"), formatSize(resource), false);
			add(sourceRows, tr("lnk.row.modified"), format(resource.getLastModifiedDateTime()), false);
			add(sourceRows, tr("lnk.row.created"), format(resource.getCreatedDateTime()), false);
			add(sourceRows, tr("lnk.row.accessed"), format(resource.getLastAccessDateTime()), false);
			add(sourceRows, tr("lnk.row.format"), tr("lnk.format.value"), false);

			List<InfoRow> timestampRows = new ArrayList<>();
			if (header != null) {
				add(timestampRows, tr("lnk.row.target_created"), format(header.getCreationTime()), false);
				add(timestampRows, tr("lnk.row.target_accessed"), format(header.getAccessTime()), false);
				add(timestampRows, tr("lnk.row.target_modified"), format(header.getWriteTime()), false);
				add(timestampRows, tr("lnk.row.target_size"), header.getFileSize() > 0 ? FileUtils.byteCountToDisplaySize(header.getFileSize()) : null, false);
			}

			List<InfoRow> locationRows = new ArrayList<>();
			addLocationRows(locationRows, linkInfo, link);

			List<String> linkFlags = header != null ? QuickViewLnkPlugin.linkFlags(header.getLinkFlags()) : List.of();
			List<String> attributeFlags = header != null ? QuickViewLnkPlugin.attributeFlags(header.getFileAttributesFlags()) : List.of();
			List<InfoRow> noteRows = notes.stream().map(note -> new InfoRow("", note, false)).toList();

			String title = displayName(resource);
			String subtitle = target != null ? target : tr("lnk.default_name");
			return new ShortcutInfo(title, subtitle, target, status, targetRows, launchRows, iconRows, sourceRows,
					timestampRows, locationRows, linkFlags, attributeFlags, noteRows);
		}

		private static ShellLink readShellLink(NuclrResource resource) throws IOException, ShellLinkException {
			Path path = resource.getPath();
			if (path != null) {
				return new ShellLink(path);
			}

			try (InputStream in = resource.openInputStream()) {
				return new ShellLink(in);
			} catch (Exception e) {
				if (e instanceof IOException io) {
					throw io;
				}
				// The message reaches the preview's error card through friendlyError().
				throw new IOException(tr("lnk.error.read_stream"), e);
			}
		}
	}

	private record InfoRow(String label, String value, boolean monospace) {
	}

	private enum Tone {
		SUCCESS,
		WARNING,
		NEUTRAL
	}

	private record TargetStatus(String label, String detail, String checkedPath, Tone tone) {
	}

	private static TargetStatus targetStatus(String target, Path sourcePath) {
		if (target == null || target.isBlank()) {
			return new TargetStatus(tr("lnk.status.not_stored"), tr("lnk.status.not_stored.detail"), null,
					Tone.WARNING);
		}

		String expanded = expandWindowsEnvironment(target);

		try {
			if (looksLikeWindowsPath(expanded) && !SystemUtils.IS_OS_WINDOWS) {
				return new TargetStatus(tr("lnk.status.windows"), tr("lnk.status.windows.detail"), expanded,
						Tone.NEUTRAL);
			}

			Path checked = Path.of(expanded);
			if (!checked.isAbsolute() && sourcePath != null && sourcePath.getParent() != null) {
				checked = sourcePath.getParent().resolve(checked).normalize();
			}

			if (Files.exists(checked)) {
				String detail = Files.isDirectory(checked)
						? tr("lnk.status.found.folder")
						: tr("lnk.status.found.file");
				return new TargetStatus(tr("lnk.status.found"), detail, checked.toString(), Tone.SUCCESS);
			}
			return new TargetStatus(tr("lnk.status.missing"), tr("lnk.status.missing.detail"),
					checked.toString(), Tone.WARNING);
		} catch (InvalidPathException | SecurityException e) {
			return new TargetStatus(tr("lnk.status.not_checkable"),
					tr("lnk.status.not_checkable.detail", String.valueOf(e.getMessage())),
					expanded, Tone.NEUTRAL);
		}
	}

	private static String expandWindowsEnvironment(String value) {
		if (value == null || value.indexOf('%') < 0) {
			return value;
		}
		Matcher matcher = WINDOWS_ENV_VAR.matcher(value);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find()) {
			String env = System.getenv(matcher.group(1));
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(env != null ? env : matcher.group()));
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private static boolean looksLikeWindowsPath(String value) {
		if (value == null || value.length() < 2) {
			return false;
		}
		return value.startsWith("\\\\")
				|| value.startsWith("//")
				|| (value.length() > 2
						&& Character.isLetter(value.charAt(0))
						&& value.charAt(1) == ':'
						&& (value.charAt(2) == '\\' || value.charAt(2) == '/'));
	}

	private static void addLocationRows(List<InfoRow> rows, LinkInfo linkInfo, ShellLink link) {
		if (linkInfo != null) {
			VolumeID volume = linkInfo.getVolumeID();
			if (volume != null) {
				add(rows, tr("lnk.row.drive_type"), driveType(volume.getDriveType()), false);
				add(rows, tr("lnk.row.volume_label"), clean(volume.getLabel()), false);
				add(rows, tr("lnk.row.volume_serial"), String.format("0x%08X", volume.getSerialNumber()), true);
			}

			CNRLink network = linkInfo.getCommonNetworkRelativeLink();
			if (network != null) {
				add(rows, tr("lnk.row.network_share"), clean(network.getNetName()), true);
				add(rows, tr("lnk.row.network_device"), clean(network.getDeviceName()), true);
				add(rows, tr("lnk.row.network_type"), String.format("0x%08X", network.getNetworkType()), true);
			}

			add(rows, tr("lnk.row.common_suffix"), clean(linkInfo.getCommonPathSuffix()), true);
			add(rows, tr("lnk.row.local_base_path"), clean(linkInfo.getLocalBasePath()), true);
		}

		Tracker tracker = tracker(link);
		if (tracker != null) {
			add(rows, tr("lnk.row.tracked_machine"), clean(tracker.getNetbiosName()), false);
		}
	}

	private static String driveType(int driveType) {
		return switch (driveType) {
			case VolumeID.DRIVE_NO_ROOT_DIR -> tr("lnk.drive.no_root");
			case VolumeID.DRIVE_REMOVABLE -> tr("lnk.drive.removable");
			case VolumeID.DRIVE_FIXED -> tr("lnk.drive.fixed");
			case VolumeID.DRIVE_REMOTE -> tr("lnk.drive.network");
			case VolumeID.DRIVE_CDROM -> tr("lnk.drive.cdrom");
			case VolumeID.DRIVE_RAMDISK -> tr("lnk.drive.ramdisk");
			case VolumeID.DRIVE_UNKNOWN -> tr("lnk.drive.unknown");
			default -> tr("lnk.drive.other", String.valueOf(driveType));
		};
	}

	private static List<String> linkFlags(LinkFlags flags) {
		if (flags == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		add(values, flags.hasLinkTargetIDList(), "lnk.flag.target_id_list");
		add(values, flags.hasLinkInfo(), "lnk.flag.link_info");
		add(values, flags.hasName(), "lnk.row.description");
		add(values, flags.hasRelativePath(), "lnk.row.relative_path");
		add(values, flags.hasWorkingDir(), "lnk.flag.working_dir");
		add(values, flags.hasArguments(), "lnk.row.arguments");
		add(values, flags.hasIconLocation(), "lnk.flag.custom_icon");
		add(values, flags.isUnicode(), "lnk.flag.unicode");
		add(values, flags.hasExpString(), "lnk.row.environment_target");
		add(values, flags.hasExpIcon(), "lnk.flag.environment_icon");
		add(values, flags.runAsUser(), "lnk.flag.run_as_user");
		add(values, flags.runInSeparateProcess(), "lnk.flag.separate_process");
		add(values, flags.runWithShimLayer(), "lnk.flag.shim_layer");
		add(values, flags.preferEnvironmentPath(), "lnk.flag.prefer_env_path");
		add(values, flags.enableTargetMetadata(), "lnk.flag.target_metadata");
		add(values, flags.disableLinkPathTracking(), "lnk.flag.path_tracking_disabled");
		add(values, flags.disableKnownFolderTracking(), "lnk.flag.known_folder_tracking_disabled");
		add(values, flags.allowLinkToLink(), "lnk.flag.link_to_link");
		add(values, flags.keepLocalIDListForUNCTarget(), "lnk.flag.unc_id_list");
		return values;
	}

	private static List<String> attributeFlags(FileAttributesFlags flags) {
		if (flags == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		add(values, flags.isNormal(), "lnk.attr.normal");
		add(values, flags.isDirecory(), "lnk.attr.directory");
		add(values, flags.isArchive(), "lnk.attr.archive");
		add(values, flags.isReadonly(), "lnk.attr.readonly");
		add(values, flags.isHidden(), "lnk.attr.hidden");
		add(values, flags.isSystem(), "lnk.attr.system");
		add(values, flags.isCompressed(), "lnk.attr.compressed");
		add(values, flags.isEncypted(), "lnk.attr.encrypted");
		add(values, flags.isOffline(), "lnk.attr.offline");
		add(values, flags.isTemporary(), "lnk.attr.temporary");
		add(values, flags.isReparsePoint(), "lnk.attr.reparse_point");
		add(values, flags.isSparseFile(), "lnk.attr.sparse_file");
		add(values, flags.isNotContentIndexed(), "lnk.attr.not_indexed");
		return values;
	}

	/** Adds the translated text for {@code labelKey} when {@code present}. */
	private static void add(List<String> values, boolean present, String labelKey) {
		if (present) {
			values.add(tr(labelKey));
		}
	}

	private static String showCommand(int showCommand) {
		return switch (showCommand) {
			case ShellLinkHeader.SW_SHOWMAXIMIZED -> tr("lnk.show.maximized");
			case ShellLinkHeader.SW_SHOWMINNOACTIVE -> tr("lnk.show.minimized");
			case ShellLinkHeader.SW_SHOWNORMAL -> tr("lnk.show.normal");
			default -> tr("lnk.show.other", String.valueOf(showCommand));
		};
	}

	private static String hotkey(HotKeyFlags hotKey) {
		if (hotKey == null) {
			return null;
		}

		String key = clean(hotKey.getKey());
		if (key == null || "NO_KEY".equalsIgnoreCase(key)) {
			return null;
		}

		List<String> parts = new ArrayList<>();
		if (hotKey.isCtrl()) {
			parts.add("Ctrl");
		}
		if (hotKey.isAlt()) {
			parts.add("Alt");
		}
		if (hotKey.isShift()) {
			parts.add("Shift");
		}
		parts.add(key);
		return String.join("+", parts);
	}

	private static String runBehavior(LinkFlags flags) {
		if (flags == null) {
			return null;
		}
		List<String> values = new ArrayList<>();
		if (flags.runAsUser()) {
			values.add(tr("lnk.flag.run_as_user"));
		}
		if (flags.runInSeparateProcess()) {
			values.add(tr("lnk.flag.separate_process"));
		}
		if (flags.runWithShimLayer()) {
			values.add(tr("lnk.run.compat_shim"));
		}
		return values.isEmpty() ? tr("lnk.run.default") : String.join(", ", values);
	}

	private static String environmentTarget(ShellLink link) {
		try {
			return link.HasEnvironmentVariable() ? clean(link.getEnvironmentVariable().getVariable()) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String iconEnvironment(ShellLink link) {
		try {
			return link.HasIconEnvironment() ? clean(link.getIconEnvironment().getIconPath()) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static Tracker tracker(ShellLink link) {
		try {
			return link.HasTracker() ? link.getTracker() : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String safe(StringSupplier supplier) {
		try {
			return supplier.get();
		} catch (RuntimeException e) {
			return null;
		}
	}

	@FunctionalInterface
	private interface StringSupplier {
		String get();
	}

	private static void add(List<InfoRow> rows, String label, String value, boolean monospace) {
		String cleaned = clean(value);
		if (cleaned != null) {
			rows.add(new InfoRow(label, cleaned, monospace));
		}
	}

	private static void addIfDifferent(List<InfoRow> rows, String label, String value, String reference) {
		String cleaned = clean(value);
		if (cleaned != null && !Objects.equals(cleaned, clean(reference))) {
			rows.add(new InfoRow(label, cleaned, true));
		}
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			String cleaned = clean(value);
			if (cleaned != null) {
				return cleaned;
			}
		}
		return null;
	}

	private static String clean(String value) {
		if (value == null) {
			return null;
		}
		String cleaned = value.trim();
		return cleaned.isEmpty() ? null : cleaned;
	}

	private static String displayName(NuclrResource resource) {
		String name = clean(resource.getName());
		if (name != null) {
			return name;
		}
		Path path = resource.getPath();
		if (path != null && path.getFileName() != null) {
			return path.getFileName().toString();
		}
		return tr("lnk.default_name");
	}

	private static String sourcePath(NuclrResource resource) {
		String fullPath = clean(resource.getFullPath());
		if (fullPath != null) {
			return fullPath;
		}
		Path path = resource.getPath();
		return path != null ? path.toAbsolutePath().toString() : null;
	}

	private static String formatSize(NuclrResource resource) {
		long size = resource.getLength();
		if (size <= 0 && resource.getPath() != null) {
			try {
				size = Files.size(resource.getPath());
			} catch (IOException ignored) {
				// Keep the resource-provided value.
			}
		}
		return FileUtils.byteCountToDisplaySize(size);
	}

	private static String format(LocalDateTime dateTime) {
		if (dateTime == null || dateTime.getYear() <= 1970) {
			return null;
		}
		return DATE_TIME.format(dateTime);
	}

	private static String format(Calendar calendar) {
		if (calendar == null || calendar.get(Calendar.YEAR) <= 1601) {
			return null;
		}
		return DATE_TIME.format(calendar.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return (cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted();
	}

	private static JTextArea valueArea(String text, LnkTheme theme, boolean monospace) {
		JTextArea area = new WrappedTextArea(text == null ? "" : text);
		area.setEditable(false);
		area.setFocusable(false);
		area.setOpaque(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(!monospace);
		area.setRows(1);
		area.setColumns(1);
		area.setBorder(null);
		area.setForeground(theme.foreground());
		area.setCaretColor(theme.foreground());
		area.setSelectedTextColor(theme.foreground());
		area.setSelectionColor(theme.selection());
		area.setFont(monospace ? theme.monoFont() : theme.bodyFont());
		return area;
	}

	private static final class WrappedTextArea extends JTextArea {
		WrappedTextArea(String text) {
			super(text);
		}

		@Override
		public Dimension getMinimumSize() {
			Dimension minimum = super.getMinimumSize();
			minimum.width = 0;
			return minimum;
		}

		@Override
		public Dimension getPreferredSize() {
			int width = availableWidth();
			if (width <= 0) {
				width = 360;
			}
			setSize(width, Short.MAX_VALUE);
			Dimension preferred = super.getPreferredSize();
			preferred.width = width;
			return preferred;
		}

		private int availableWidth() {
			Component parent = getParent();
			if (parent == null) {
				return 0;
			}

			int width = parent.getWidth();
			if (width <= 0) {
				return 0;
			}

			Insets insets = parent instanceof Container container ? container.getInsets() : new Insets(0, 0, 0, 0);
			return Math.max(1, width - insets.left - insets.right);
		}
	}

	private static final class HeaderPanel extends RoundedPanel {
		HeaderPanel(ShortcutInfo info, LnkTheme theme) {
			super(theme.surfaceStrong(), theme.border(), 8);
			setLayout(new BorderLayout(12, 0));
			setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
			setAlignmentX(LEFT_ALIGNMENT);

			add(new JLabel(new ShortcutGlyph(50, theme)), BorderLayout.WEST);

			JPanel middle = new JPanel();
			middle.setOpaque(false);
			middle.setLayout(new BoxLayout(middle, BoxLayout.Y_AXIS));

			JPanel statusRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
			statusRow.setOpaque(false);
			statusRow.setAlignmentX(LEFT_ALIGNMENT);
			statusRow.add(new StatusPill(info.targetStatus(), theme));

			JTextArea title = valueArea(info.title(), theme, false);
			title.setFont(theme.bodyFont().deriveFont(Font.BOLD, 18f));
			title.setForeground(theme.foreground());
			title.setAlignmentX(LEFT_ALIGNMENT);
			title.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));

			JTextArea subtitle = valueArea(info.subtitle(), theme, true);
			subtitle.setForeground(theme.muted());
			subtitle.setFont(theme.monoFont().deriveFont(Font.PLAIN, 12f));
			subtitle.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
			subtitle.setAlignmentX(LEFT_ALIGNMENT);

			middle.add(statusRow);
			middle.add(title);
			middle.add(subtitle);
			add(middle, BorderLayout.CENTER);
		}

		@Override
		public Dimension getMaximumSize() {
			Dimension preferred = getPreferredSize();
			return new Dimension(Integer.MAX_VALUE, preferred.height);
		}
	}

	private static final class SectionPanel extends RoundedPanel {
		private final LnkTheme theme;
		private final JPanel grid = new JPanel(new GridBagLayout());
		private int row;

		SectionPanel(String title, LnkTheme theme) {
			super(theme.surface(), theme.border(), 8);
			this.theme = theme;
			setLayout(new BorderLayout(0, 8));
			setBorder(BorderFactory.createEmptyBorder(11, 12, 12, 12));
			setAlignmentX(LEFT_ALIGNMENT);

			JLabel heading = new JLabel(title);
			heading.setForeground(theme.foreground());
			heading.setFont(theme.bodyFont().deriveFont(Font.BOLD, 13f));
			add(heading, BorderLayout.NORTH);

			grid.setOpaque(false);
			add(grid, BorderLayout.CENTER);
		}

		void addRow(InfoRow rowData) {
			GridBagConstraints key = new GridBagConstraints();
			key.gridx = 0;
			key.gridy = row;
			key.anchor = GridBagConstraints.NORTHWEST;
			key.insets = new Insets(2, 0, 5, 12);

			JLabel keyLabel = new JLabel(rowData.label() == null || rowData.label().isBlank() ? "" : rowData.label() + ":");
			keyLabel.setForeground(theme.muted());
			keyLabel.setFont(theme.bodyFont().deriveFont(Font.PLAIN, 12f));
			keyLabel.setPreferredSize(new Dimension(112, keyLabel.getPreferredSize().height));

			GridBagConstraints value = new GridBagConstraints();
			value.gridx = 1;
			value.gridy = row;
			value.anchor = GridBagConstraints.NORTHWEST;
			value.fill = GridBagConstraints.HORIZONTAL;
			value.weightx = 1.0;
			value.insets = new Insets(1, 0, 5, 0);
			row++;

			JTextArea valueLabel = valueArea(rowData.value(), theme, rowData.monospace());
			valueLabel.setFont(rowData.monospace()
					? theme.monoFont().deriveFont(Font.PLAIN, 12f)
					: theme.bodyFont().deriveFont(Font.PLAIN, 12f));

			grid.add(keyLabel, key);
			grid.add(valueLabel, value);
		}

		void addChips(List<String> chips) {
			JPanel chipPanel = new WrapPanel(6, 6);
			chipPanel.setOpaque(false);
			for (String chip : chips) {
				chipPanel.add(new Chip(chip, theme));
			}

			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = 0;
			constraints.gridy = row++;
			constraints.gridwidth = 2;
			constraints.anchor = GridBagConstraints.NORTHWEST;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.weightx = 1.0;
			grid.add(chipPanel, constraints);
		}

		@Override
		public Dimension getMaximumSize() {
			Dimension preferred = getPreferredSize();
			return new Dimension(Integer.MAX_VALUE, preferred.height);
		}
	}

	private static final class StatusPill extends JLabel {
		private final Color background;

		StatusPill(TargetStatus status, LnkTheme theme) {
			super(status.label());
			Color tone = switch (status.tone()) {
				case SUCCESS -> theme.success();
				case WARNING -> theme.warning();
				case NEUTRAL -> theme.accent();
			};
			this.background = blend(theme.background(), tone, 0.18f);
			setForeground(tone);
			setFont(theme.bodyFont().deriveFont(Font.BOLD, 12f));
			setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(background);
			g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
			g2.dispose();
			super.paintComponent(g);
		}
	}

	private static final class Chip extends JLabel {
		private final Color background;
		private final Color border;

		Chip(String text, LnkTheme theme) {
			super(text);
			this.background = theme.surfaceStrong();
			this.border = theme.border();
			setForeground(theme.foreground());
			setFont(theme.bodyFont().deriveFont(Font.PLAIN, 11f));
			setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(background);
			g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
			g2.setColor(border);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
			g2.dispose();
			super.paintComponent(g);
		}
	}

	private static final class ShortcutGlyph implements Icon {
		private final int size;
		private final LnkTheme theme;

		ShortcutGlyph(int size, LnkTheme theme) {
			this.size = size;
			this.theme = theme;
		}

		@Override
		public int getIconWidth() {
			return size;
		}

		@Override
		public int getIconHeight() {
			return size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			float scale = size / 50f;
			Shape tile = new RoundRectangle2D.Float(x + 4 * scale, y + 3 * scale, 36 * scale, 42 * scale,
					8 * scale, 8 * scale);
			g2.setColor(theme.accentSoft());
			g2.fill(tile);
			g2.setColor(theme.accent());
			g2.setStroke(new BasicStroke(Math.max(1.2f, 1.5f * scale)));
			g2.draw(tile);

			Path2D fold = new Path2D.Float();
			fold.moveTo(x + 29 * scale, y + 3 * scale);
			fold.lineTo(x + 40 * scale, y + 14 * scale);
			fold.lineTo(x + 29 * scale, y + 14 * scale);
			fold.closePath();
			g2.setColor(blend(theme.background(), theme.accent(), 0.25f));
			g2.fill(fold);

			g2.setStroke(new BasicStroke(Math.max(2f, 2.8f * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(theme.foreground());
			g2.drawLine(Math.round(x + 16 * scale), Math.round(y + 33 * scale),
					Math.round(x + 32 * scale), Math.round(y + 17 * scale));
			g2.drawLine(Math.round(x + 32 * scale), Math.round(y + 17 * scale),
					Math.round(x + 32 * scale), Math.round(y + 27 * scale));
			g2.drawLine(Math.round(x + 32 * scale), Math.round(y + 17 * scale),
					Math.round(x + 22 * scale), Math.round(y + 17 * scale));
			g2.drawLine(Math.round(x + 14 * scale), Math.round(y + 33 * scale),
					Math.round(x + 14 * scale), Math.round(y + 24 * scale));
			g2.drawLine(Math.round(x + 14 * scale), Math.round(y + 33 * scale),
					Math.round(x + 23 * scale), Math.round(y + 33 * scale));

			g2.dispose();
		}
	}

	private static class RoundedPanel extends JPanel {
		private final Color fill;
		private final Color border;
		private final int arc;

		RoundedPanel(Color fill, Color border, int arc) {
			this.fill = fill;
			this.border = border;
			this.arc = arc;
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(fill);
			g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
			g2.setColor(border);
			g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
			g2.dispose();
			super.paintComponent(g);
		}
	}

	private static final class ScrollablePage extends JPanel implements Scrollable {
		ScrollablePage(Color background) {
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setBackground(background);
		}

		@Override
		public Dimension getPreferredScrollableViewportSize() {
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
			return 18;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
			return Math.max(18, visibleRect.height - 18);
		}

		@Override
		public boolean getScrollableTracksViewportWidth() {
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight() {
			return false;
		}
	}

	private static final class WrapPanel extends JPanel {
		private final int hgap;
		private final int vgap;

		WrapPanel(int hgap, int vgap) {
			this.hgap = hgap;
			this.vgap = vgap;
			setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, hgap, vgap));
		}

		@Override
		public Dimension getPreferredSize() {
			return layoutSize(false);
		}

		@Override
		public Dimension getMinimumSize() {
			return layoutSize(true);
		}

		private Dimension layoutSize(boolean minimum) {
			int targetWidth = getParent() != null ? getParent().getWidth() : getWidth();
			if (targetWidth <= 0) {
				return super.getPreferredSize();
			}

			Insets insets = getInsets();
			int maxWidth = Math.max(1, targetWidth - insets.left - insets.right - hgap * 2);
			int rowWidth = 0;
			int rowHeight = 0;
			int width = 0;
			int height = insets.top + insets.bottom;

			for (Component component : getComponents()) {
				if (!component.isVisible()) {
					continue;
				}
				Dimension size = minimum ? component.getMinimumSize() : component.getPreferredSize();
				if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
					width = Math.max(width, rowWidth);
					height += rowHeight + vgap;
					rowWidth = 0;
					rowHeight = 0;
				}
				if (rowWidth > 0) {
					rowWidth += hgap;
				}
				rowWidth += size.width;
				rowHeight = Math.max(rowHeight, size.height);
			}

			width = Math.max(width, rowWidth);
			height += rowHeight;
			return new Dimension(width + insets.left + insets.right, height);
		}
	}

	private record LnkTheme(
			Color background,
			Color foreground,
			Color muted,
			Color surface,
			Color surfaceStrong,
			Color border,
			Color accent,
			Color accentSoft,
			Color selection,
			Color success,
			Color warning,
			Font bodyFont,
			Font monoFont) {

		static LnkTheme from(NuclrThemeScheme scheme) {
			Color defaultBg = uiColor("Panel.background", new Color(0x111827));
			Color defaultFg = uiColor("Label.foreground", new Color(0xE5E7EB));
			Color bg = scheme != null ? scheme.color("Panel.background", defaultBg) : defaultBg;
			Color fg = scheme != null ? scheme.color("Label.foreground", defaultFg) : defaultFg;
			Color accent = scheme != null
					? scheme.color("Component.accentColor", scheme.color("Table.selectionBackground", new Color(0x3B82F6)))
					: uiColor("Component.accentColor", uiColor("Table.selectionBackground", new Color(0x3B82F6)));

			boolean dark = isDark(bg);
			Color muted = scheme != null
					? scheme.color("Label.disabledForeground", blend(bg, fg, dark ? 0.55f : 0.48f))
					: uiColor("Label.disabledForeground", blend(bg, fg, dark ? 0.55f : 0.48f));
			Color surface = blend(bg, dark ? Color.WHITE : Color.BLACK, dark ? 0.065f : 0.035f);
			Color surfaceStrong = blend(bg, accent, dark ? 0.16f : 0.08f);
			Color border = scheme != null
					? scheme.color("Component.borderColor", blend(bg, fg, dark ? 0.22f : 0.14f))
					: uiColor("Component.borderColor", blend(bg, fg, dark ? 0.22f : 0.14f));
			Color accentSoft = blend(bg, accent, dark ? 0.24f : 0.16f);
			Color selection = blend(bg, accent, dark ? 0.34f : 0.24f);

			Font base = scheme != null ? scheme.defaultFont() : UIManager.getFont("Label.font");
			if (base == null) {
				base = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
			}
			Font body = base.deriveFont(Font.PLAIN, 13f);
			Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 12);

			return new LnkTheme(bg, fg, muted, surface, surfaceStrong, border, accent, accentSoft, selection,
					new Color(0x22C55E), new Color(0xF59E0B), body, mono);
		}
	}

	private static Color uiColor(String key, Color fallback) {
		Color color = UIManager.getColor(key);
		return color != null ? color : fallback;
	}

	private static boolean isDark(Color color) {
		double luminance = 0.299 * color.getRed() + 0.587 * color.getGreen() + 0.114 * color.getBlue();
		return luminance < 128.0;
	}

	private static Color blend(Color base, Color overlay, float overlayWeight) {
		float clamped = Math.max(0f, Math.min(1f, overlayWeight));
		float baseWeight = 1f - clamped;
		return new Color(
				Math.round(base.getRed() * baseWeight + overlay.getRed() * clamped),
				Math.round(base.getGreen() * baseWeight + overlay.getGreen() * clamped),
				Math.round(base.getBlue() * baseWeight + overlay.getBlue() * clamped));
	}

}
