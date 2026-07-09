/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.panel.fs.find;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.extras.components.FlatButton;
import com.formdev.flatlaf.extras.components.FlatButton.ButtonType;
import com.formdev.flatlaf.extras.components.FlatComboBox;
import com.formdev.flatlaf.extras.components.FlatLabel;
import com.formdev.flatlaf.extras.components.FlatTextField;
import com.formdev.flatlaf.extras.components.FlatToggleButton;

import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import lombok.extern.slf4j.Slf4j;

/**
 * The Alt+F7 "Find File" dialog — the modernised FAR Alt+F7. UI-only and EDT-only: it
 * owns layout and key bindings, assembles an immutable {@link FindFileRequest} on Find,
 * and hands it to the request sink supplied via {@link FindFileContext}. Search execution
 * lives entirely in {@link FindFileService}.
 *
 * <p>The dialog is non-modal and anchored to the commander window. The {@code .gitignore}
 * chip auto-detects a Git working tree from the current scope root (off the EDT) and
 * re-probes whenever the scope changes; the user may still toggle it off.
 */
@Slf4j
public final class FindFileDialog extends JDialog {

	private static final long serialVersionUID = 1L;

	private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

	private final transient FindFileContext context;

	// --- Name / Containing ---
	private final FlatTextField nameField = new FlatTextField();
	private final FlatTextField containingField = new FlatTextField();
	private final FlatToggleButton modeText = new FlatToggleButton();
	private final FlatToggleButton modeRegex = new FlatToggleButton();
	private final FlatToggleButton modeHex = new FlatToggleButton();
	private final FlatToggleButton caseToggle = new FlatToggleButton();
	private final FlatToggleButton wholeWordToggle = new FlatToggleButton();
	private final FlatToggleButton invertToggle = new FlatToggleButton();

	// --- Where ---
	private final FlatComboBox<ScopeType> whereCombo = new FlatComboBox<>();
	private final FlatTextField customPathField = new FlatTextField();
	private final FlatButton browseButton = new FlatButton();
	private final JPanel customPathPanel = new JPanel(new GridBagLayout());

	// --- Scope chips ---
	private final FlatToggleButton subfoldersChip = chip("Subfolders");
	private final FlatToggleButton symlinksChip = chip("Symlinks");
	private final FlatToggleButton archivesChip = chip("Archives");
	private final FlatToggleButton hiddenChip = chip("Hidden");
	private final FlatToggleButton gitignoreChip = chip(".gitignore");

	// --- More filters ---
	private final FlatButton disclosureButton = new FlatButton();
	private final JPanel moreFiltersPanel = new JPanel(new GridBagLayout());
	private final FlatTextField dateFromField = new FlatTextField();
	private final FlatTextField dateToField = new FlatTextField();
	private final FlatTextField sizeMinField = new FlatTextField();
	private final FlatTextField sizeMaxField = new FlatTextField();

	// --- Footer ---
	private final FlatButton findButton = new FlatButton();

	/** Tracks the gitignore probe in flight so a stale result cannot clobber a newer scope. */
	private transient volatile long gitProbeGeneration;

	/**
	 * Build the dialog (must be called on the EDT).
	 *
	 * @param owner   the commander window to anchor to
	 * @param context scope inputs and plugin-agnostic collaborators
	 */
	public FindFileDialog(Window owner, FindFileContext context) {
		super(owner, "Find File", ModalityType.MODELESS);
		this.context = context;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

		addSection(body, buildNameRow());
		addSection(body, buildContainingBlock());
		addSection(body, buildWhereBlock());
		addSection(body, buildChipsRow());
		addSection(body, buildMoreFilters());

		JPanel content = new JPanel(new java.awt.BorderLayout(0, 10));
		content.add(body, java.awt.BorderLayout.CENTER);
		content.add(buildFooter(), java.awt.BorderLayout.SOUTH);
		setContentPane(content);

		installKeyBindings();
		installFocusTraversal();

		updateFindEnabled();
		updateCustomPathVisibility();
		probeGitignoreForCurrentScope();

		getRootPane().setDefaultButton(findButton);
		pack();
		setMinimumSize(new Dimension(560, getHeight()));
		setLocationRelativeTo(owner);

		SwingUtilities.invokeLater(() -> {
			nameField.requestFocusInWindow();
			nameField.selectAll();
		});
	}

	// ------------------------------------------------------------------
	// Sections
	// ------------------------------------------------------------------

	private JComponent buildNameRow() {
		FlatLabel label = new FlatLabel();
		label.setText("Name");
		label.setDisplayedMnemonic('N');
		label.setLabelFor(nameField);

		monospace(nameField);
		nameField.setText("*");
		nameField.setPlaceholderText("*.java, README*, …");
		nameField.getDocument().addDocumentListener(onChange(this::updateFindEnabled));

		JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
		row.add(label, java.awt.BorderLayout.WEST);
		row.add(nameField, java.awt.BorderLayout.CENTER);
		label.setPreferredSize(new Dimension(90, label.getPreferredSize().height));
		return row;
	}

	private JComponent buildContainingBlock() {
		FlatLabel label = new FlatLabel();
		label.setText("Containing");
		label.setDisplayedMnemonic('C');
		label.setLabelFor(containingField);

		// Segmented Text | Regex | Hex (shared ButtonGroup, tab button type).
		configureSegment(modeText, ContentMatchMode.TEXT.label(), true);
		configureSegment(modeRegex, ContentMatchMode.REGEX.label(), false);
		configureSegment(modeHex, ContentMatchMode.HEX.label(), false);
		ButtonGroup modeGroup = new ButtonGroup();
		modeGroup.add(modeText);
		modeGroup.add(modeRegex);
		modeGroup.add(modeHex);

		JPanel segment = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		segment.add(modeText);
		segment.add(modeRegex);
		segment.add(modeHex);

		JPanel header = new JPanel(new java.awt.BorderLayout());
		header.add(label, java.awt.BorderLayout.WEST);
		header.add(segment, java.awt.BorderLayout.EAST);

		monospace(containingField);
		containingField.setPlaceholderText("text to find inside files (optional)");
		containingField.getDocument().addDocumentListener(onChange(this::updateFindEnabled));

		// Trailing Aa / ab / ! toggles live in the field's trailing-component slot.
		configureTrailingToggle(caseToggle, "Aa", "Case sensitive");
		configureTrailingToggle(wholeWordToggle, "ab", "Whole word");
		configureTrailingToggle(invertToggle, "!", "Invert match");
		JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		trailing.setOpaque(false);
		trailing.add(caseToggle);
		trailing.add(wholeWordToggle);
		trailing.add(invertToggle);
		containingField.setTrailingComponent(trailing);

		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		header.setAlignmentX(Component.LEFT_ALIGNMENT);
		containingField.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(header);
		block.add(Box.createVerticalStrut(4));
		block.add(containingField);
		return block;
	}

	private JComponent buildWhereBlock() {
		FlatLabel label = new FlatLabel();
		label.setText("Where");
		label.setDisplayedMnemonic('W');
		label.setLabelFor(whereCombo);

		for (ScopeType type : ScopeType.values()) {
			// Hide scopes that have no input available (other panel / marked items).
			if (type == ScopeType.BOTH_PANELS && !context.hasOtherPanel()) {
				continue;
			}
			if (type == ScopeType.MARKED_ITEMS && !context.hasMarkedItems()) {
				continue;
			}
			whereCombo.addItem(type);
		}
		whereCombo.setSelectedItem(ScopeType.CURRENT_FOLDER);
		whereCombo.setRenderer(scopeRenderer());
		whereCombo.addActionListener(e -> onScopeChanged());

		JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
		row.add(label, java.awt.BorderLayout.WEST);
		row.add(whereCombo, java.awt.BorderLayout.CENTER);
		label.setPreferredSize(new Dimension(90, label.getPreferredSize().height));

		// Inline custom-path expansion (hidden until "Custom path…" is chosen).
		monospace(customPathField);
		customPathField.setPlaceholderText("path or plugin URI (sftp://…, zip:/…!/…)");
		browseButton.setText("Browse…");
		browseButton.setMnemonic('B');
		browseButton.addActionListener(e -> onBrowse());

		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.gridy = 0;
		gc.weightx = 1;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.insets = new Insets(6, 98, 0, 6);
		customPathPanel.add(customPathField, gc);
		gc.gridx = 1;
		gc.weightx = 0;
		gc.insets = new Insets(6, 0, 0, 0);
		customPathPanel.add(browseButton, gc);
		customPathPanel.setVisible(false);

		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		customPathPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(row);
		block.add(customPathPanel);
		return block;
	}

	private JComponent buildChipsRow() {
		subfoldersChip.setSelected(true);

		JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
		chips.add(subfoldersChip);
		chips.add(symlinksChip);
		chips.add(archivesChip);
		chips.add(hiddenChip);
		chips.add(gitignoreChip);

		JPanel row = new JPanel(new java.awt.BorderLayout(8, 0));
		FlatLabel west = new FlatLabel();
		west.setText("Scope");
		west.setPreferredSize(new Dimension(90, west.getPreferredSize().height));
		row.add(west, java.awt.BorderLayout.WEST);
		row.add(chips, java.awt.BorderLayout.CENTER);
		return row;
	}

	private JComponent buildMoreFilters() {
		disclosureButton.setText("▸ More filters");
		disclosureButton.setButtonType(ButtonType.borderless);
		disclosureButton.setMnemonic('M');
		disclosureButton.addActionListener(e -> toggleMoreFilters());

		GridBagConstraints gc = baseGbc();
		int row = 0;

		gc.gridy = row++;
		moreFiltersPanel.add(labeled("Date modified", dateFromField, "from (yyyy-MM-dd)", dateToField, "to"), gc);
		gc.gridy = row++;
		moreFiltersPanel.add(labeled("Size (bytes)", sizeMinField, "min", sizeMaxField, "max"), gc);

		moreFiltersPanel.setVisible(false);
		moreFiltersPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 0, 0));

		JPanel block = new JPanel();
		block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));
		JPanel discRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		discRow.add(disclosureButton);
		discRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		moreFiltersPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		block.add(discRow);
		block.add(moreFiltersPanel);
		return block;
	}

	private JComponent buildFooter() {
		findButton.setText("Find");
		findButton.setMnemonic('F');
		findButton.setButtonType(ButtonType.none);
		findButton.addActionListener(e -> doFind());

		FlatButton cancel = new FlatButton();
		cancel.setText("Cancel");
		cancel.addActionListener(e -> cancelDialog());

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		actions.add(cancel);
		actions.add(findButton);

		JPanel footer = new JPanel(new java.awt.BorderLayout());
		footer.setBorder(BorderFactory.createEmptyBorder(8, 16, 12, 16));
		JPanel withSep = new JPanel(new java.awt.BorderLayout());
		withSep.add(new JSeparator(), java.awt.BorderLayout.NORTH);
		footer.add(actions, java.awt.BorderLayout.EAST);
		withSep.add(footer, java.awt.BorderLayout.CENTER);
		return withSep;
	}

	// ------------------------------------------------------------------
	// Behaviour
	// ------------------------------------------------------------------

	private void updateFindEnabled() {
		boolean hasName = !nameField.getText().trim().isEmpty();
		boolean hasContaining = !containingField.getText().trim().isEmpty();
		findButton.setEnabled(hasName || hasContaining);
	}

	private void onScopeChanged() {
		updateCustomPathVisibility();
		probeGitignoreForCurrentScope();
	}

	private void updateCustomPathVisibility() {
		boolean custom = selectedScope() == ScopeType.CUSTOM_PATH;
		customPathPanel.setVisible(custom);
		revalidate();
		repaint();
		if (custom) {
			SwingUtilities.invokeLater(customPathField::requestFocusInWindow);
		}
	}

	private void onBrowse() {
		if (context.getBrowser() == null) {
			return;
		}
		NuclrResource start = scopeProbeRoot();
		Optional<NuclrResource> chosen = context.getBrowser().browse(this, start);
		chosen.ifPresent(r -> {
			customPathField.setText(r.getFullPath());
			probeGitignoreForCurrentScope();
		});
	}

	private void toggleMoreFilters() {
		boolean show = !moreFiltersPanel.isVisible();
		moreFiltersPanel.setVisible(show);
		disclosureButton.setText((show ? "▾" : "▸") + " More filters");
		pack();
	}

	/**
	 * Probe the current scope's root for a Git working tree off the EDT, then set the
	 * {@code .gitignore} chip on the EDT. A generation counter discards stale probes when
	 * the scope changes faster than a probe completes.
	 */
	private void probeGitignoreForCurrentScope() {
		final long generation = ++gitProbeGeneration;
		final NuclrResource root = scopeProbeRoot();
		if (root == null || root.getPath() == null) {
			gitignoreChip.setSelected(false);
			return;
		}
		Thread.ofVirtual().name("find-gitignore-probe").start(() -> {
			boolean inside = GitIgnoreProbe.probe(root).isInsideWorkTree();
			SwingUtilities.invokeLater(() -> {
				if (generation == gitProbeGeneration) {
					gitignoreChip.setSelected(inside);
				}
			});
		});
	}

	/** The resource used to anchor gitignore detection and Browse for the current scope. */
	private NuclrResource scopeProbeRoot() {
		return switch (selectedScope()) {
			case CUSTOM_PATH -> {
				String text = customPathField.getText().trim();
				yield text.isEmpty() || context.getPathParser() == null
						? null
						: context.getPathParser().parse(text).orElse(null);
			}
			case VOLUMES -> null;
			default -> context.getCurrentFolder();
		};
	}

	private ScopeType selectedScope() {
		Object s = whereCombo.getSelectedItem();
		return s instanceof ScopeType type ? type : ScopeType.CURRENT_FOLDER;
	}

	private void doFind() {
		FindFileRequest request;
		try {
			request = collectRequest();
		} catch (IllegalArgumentException e) {
			SoundEvents.warning(context.getPluginContext());
			JOptionPane.showMessageDialog(this, e.getMessage(), "Invalid search", JOptionPane.WARNING_MESSAGE);
			return;
		}
		if (context.getOnSubmit() != null) {
			context.getOnSubmit().accept(request);
		}
		SoundEvents.confirmation(context.getPluginContext());
		dispose();
	}

	private void cancelDialog() {
		SoundEvents.cancel(context.getPluginContext());
		dispose();
	}

	private FindFileRequest collectRequest() {
		List<NuclrResource> roots = resolveRoots();

		return FindFileRequest.builder()
				.namePattern(nameField.getText())
				.containingText(containingField.getText())
				.contentMode(selectedContentMode())
				.caseSensitive(caseToggle.isSelected())
				.wholeWord(wholeWordToggle.isSelected())
				.invertMatch(invertToggle.isSelected())
				.scopeType(selectedScope())
				.customPath(customPathField.getText())
				.roots(roots)
				.searchSubfolders(subfoldersChip.isSelected())
				.followSymlinks(symlinksChip.isSelected())
				.searchArchives(archivesChip.isSelected())
				.includeHidden(hiddenChip.isSelected())
				.respectGitignore(gitignoreChip.isSelected())
				.modifiedFrom(parseDate(dateFromField.getText(), false))
				.modifiedTo(parseDate(dateToField.getText(), true))
				.minSizeBytes(parseSize(sizeMinField.getText()))
				.maxSizeBytes(parseSize(sizeMaxField.getText()))
				.build();
	}

	private List<NuclrResource> resolveRoots() {
		List<NuclrResource> roots = new ArrayList<>();
		switch (selectedScope()) {
			case CURRENT_FOLDER -> addIfPresent(roots, context.getCurrentFolder());
			case BOTH_PANELS -> {
				addIfPresent(roots, context.getCurrentFolder());
				addIfPresent(roots, context.getOtherPanelFolder());
			}
			case MARKED_ITEMS -> roots.addAll(context.getMarkedItems());
			case VOLUMES -> roots.addAll(context.volumes());
			case CUSTOM_PATH -> {
				String text = customPathField.getText().trim();
				if (text.isEmpty() || context.getPathParser() == null) {
					throw new IllegalArgumentException("Enter a custom path to search");
				}
				NuclrResource parsed = context.getPathParser().parse(text)
						.orElseThrow(() -> new IllegalArgumentException("Cannot resolve path: " + text));
				roots.add(parsed);
			}
		}
		if (roots.isEmpty()) {
			throw new IllegalArgumentException("No locations to search for the selected scope");
		}
		return roots;
	}

	private static void addIfPresent(List<NuclrResource> roots, NuclrResource resource) {
		if (resource != null) {
			roots.add(resource);
		}
	}

	private ContentMatchMode selectedContentMode() {
		if (modeRegex.isSelected()) {
			return ContentMatchMode.REGEX;
		}
		if (modeHex.isSelected()) {
			return ContentMatchMode.HEX;
		}
		return ContentMatchMode.TEXT;
	}

	private static LocalDate tryParseDate(String text) {
		try {
			return LocalDate.parse(text.trim(), DATE_FMT);
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Date must be yyyy-MM-dd: " + text);
		}
	}

	private static java.time.LocalDateTime parseDate(String text, boolean endOfDay) {
		if (text == null || text.isBlank()) {
			return null;
		}
		LocalDate date = tryParseDate(text);
		return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
	}

	private static Long parseSize(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Size must be a whole number of bytes: " + text);
		}
	}

	// ------------------------------------------------------------------
	// Key bindings & focus traversal
	// ------------------------------------------------------------------

	private void installKeyBindings() {
		JComponent root = getRootPane();
		InputMap in = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap act = root.getActionMap();

		in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.cancel");
		act.put("find.cancel", action(this::cancelDialog));

		// Enter triggers Find from any focused field (combo popups still consume their own Enter).
		in.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.run");
		act.put("find.run", action(() -> {
			if (findButton.isEnabled()) {
				doFind();
			}
		}));
	}

	private void installFocusTraversal() {
		List<Component> order = new ArrayList<>();
		order.add(nameField);
		order.add(containingField);
		order.add(modeText);
		order.add(modeRegex);
		order.add(modeHex);
		order.add(caseToggle);
		order.add(wholeWordToggle);
		order.add(invertToggle);
		order.add(whereCombo);
		order.add(customPathField);
		order.add(browseButton);
		order.add(subfoldersChip);
		order.add(symlinksChip);
		order.add(archivesChip);
		order.add(hiddenChip);
		order.add(gitignoreChip);
		order.add(disclosureButton);
		order.add(findButton);
		setFocusTraversalPolicy(new OrderedFocusPolicy(order));
		setFocusTraversalPolicyProvider(true);
	}

	// ------------------------------------------------------------------
	// Component helpers
	// ------------------------------------------------------------------

	private static void addSection(JPanel body, JComponent section) {
		section.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(section);
		body.add(Box.createVerticalStrut(10));
	}

	private static void monospace(FlatTextField field) {
		field.setFont(new Font(Font.MONOSPACED, Font.PLAIN, field.getFont().getSize()));
	}

	private static FlatToggleButton chip(String text) {
		FlatToggleButton b = new FlatToggleButton();
		b.setText(text);
		// roundRect: pill fill when selected, no border when inactive.
		b.setButtonType(ButtonType.roundRect);
		return b;
	}

	private void configureSegment(FlatToggleButton b, String text, boolean selected) {
		b.setText(text);
		b.setButtonType(ButtonType.tab);
		b.setSelected(selected);
	}

	private static void configureTrailingToggle(FlatToggleButton b, String text, String tooltip) {
		b.setText(text);
		b.setButtonType(ButtonType.roundRect);
		b.setToolTipText(tooltip);
		b.setMargin(new Insets(0, 6, 0, 6));
	}

	private JComponent labeled(String title, FlatTextField a, String aHint, FlatTextField b, String bHint) {
		a.setPlaceholderText(aHint);
		b.setPlaceholderText(bHint);
		a.setColumns(12);
		b.setColumns(12);
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		FlatLabel label = new FlatLabel();
		label.setText(title);
		label.setPreferredSize(new Dimension(110, label.getPreferredSize().height));
		row.add(label);
		row.add(a);
		FlatLabel dash = new FlatLabel();
		dash.setText("–");
		row.add(dash);
		row.add(b);
		return row;
	}

	private static GridBagConstraints baseGbc() {
		GridBagConstraints gc = new GridBagConstraints();
		gc.gridx = 0;
		gc.anchor = GridBagConstraints.WEST;
		gc.fill = GridBagConstraints.HORIZONTAL;
		gc.weightx = 1;
		gc.insets = new Insets(2, 0, 2, 0);
		return gc;
	}

	private static DocumentListener onChange(Runnable r) {
		return new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				r.run();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				r.run();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				r.run();
			}
		};
	}

	private static AbstractAction action(Runnable r) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				r.run();
			}
		};
	}

	private javax.swing.ListCellRenderer<Object> scopeRenderer() {
		javax.swing.DefaultListCellRenderer base = new javax.swing.DefaultListCellRenderer();
		return (list, value, index, selected, focus) -> {
			String label = value instanceof ScopeType type ? type.label() : String.valueOf(value);
			return base.getListCellRendererComponent(list, label, index, selected, focus);
		};
	}

	/** Explicit, list-driven focus order so Tab follows the spec exactly. */
	private static final class OrderedFocusPolicy extends java.awt.FocusTraversalPolicy {

		private final List<Component> order;

		OrderedFocusPolicy(List<Component> order) {
			this.order = order;
		}

		private List<Component> visible() {
			List<Component> v = new ArrayList<>();
			for (Component c : order) {
				if (c != null && c.isShowing() && c.isFocusable() && c.isEnabled()) {
					v.add(c);
				}
			}
			return v;
		}

		@Override
		public Component getComponentAfter(Container focusCycleRoot, Component current) {
			List<Component> v = visible();
			int i = v.indexOf(current);
			if (i < 0) {
				return getFirstComponent(focusCycleRoot);
			}
			return v.get((i + 1) % v.size());
		}

		@Override
		public Component getComponentBefore(Container focusCycleRoot, Component current) {
			List<Component> v = visible();
			int i = v.indexOf(current);
			if (i < 0) {
				return getLastComponent(focusCycleRoot);
			}
			return v.get((i - 1 + v.size()) % v.size());
		}

		@Override
		public Component getFirstComponent(Container focusCycleRoot) {
			List<Component> v = visible();
			return v.isEmpty() ? null : v.get(0);
		}

		@Override
		public Component getLastComponent(Container focusCycleRoot) {
			List<Component> v = visible();
			return v.isEmpty() ? null : v.get(v.size() - 1);
		}

		@Override
		public Component getDefaultComponent(Container focusCycleRoot) {
			return getFirstComponent(focusCycleRoot);
		}
	}

	static {
		// FlatLabel mnemonic underlines require this UI default; harmless if already set.
		javax.swing.UIManager.put("Component.hideMnemonics", Boolean.FALSE);
	}
}
