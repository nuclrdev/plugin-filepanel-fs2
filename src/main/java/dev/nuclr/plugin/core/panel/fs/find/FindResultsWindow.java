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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;

import com.formdev.flatlaf.extras.components.FlatButton;
import com.formdev.flatlaf.extras.components.FlatLabel;

import dev.nuclr.plugin.core.panel.fs.SystemOpen;
import dev.nuclr.plugin.core.panel.fs.SoundEvents;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal, non-modal results window for a Find File search. Implements
 * {@link FindFileService.Listener} so matches stream in live (every callback already runs
 * on the EDT). Interim presentation until the dedicated results panel lands; it gives Find
 * visible output, lets the user stop / pause / resume the search, navigate to a hit in the
 * active panel, and exposes a per-entry context menu.
 */
@Slf4j
public final class FindResultsWindow extends JDialog implements FindFileService.Listener {

	private static final long serialVersionUID = 1L;

	private final transient DefaultListModel<NuclrResource> model = new DefaultListModel<>();
	private final JList<NuclrResource> list = new JList<>(model);
	private final FlatLabel status = new FlatLabel();
	private final FlatButton stopButton = new FlatButton();
	private final FlatButton pauseButton = new FlatButton();
	private final FlatButton panelButton = new FlatButton();
	private final FlatButton closeButton = new FlatButton();

	/** Invoked when the user activates a result (double-click / Enter): navigate the panel to it. */
	private final transient Consumer<NuclrResource> onActivate;

	/** Invoked when the user clicks "Panel": open all results in a temporary panel. */
	private final transient Consumer<List<NuclrResource>> onSendToPanel;
	private final transient NuclrPluginContext context;

	private transient FindFileService service;
	private transient FindFileService.SearchHandle handle;
	private volatile boolean finished;
	private volatile boolean cancellationEmitted;

	public FindResultsWindow(Window owner, FindFileRequest request, Consumer<NuclrResource> onActivate,
			Consumer<List<NuclrResource>> onSendToPanel) {
		this(owner, request, onActivate, onSendToPanel, null);
	}

	public FindResultsWindow(Window owner, FindFileRequest request, Consumer<NuclrResource> onActivate,
			Consumer<List<NuclrResource>> onSendToPanel, NuclrPluginContext context) {
		super(owner, "Find results — " + request.getNamePattern(), ModalityType.MODELESS);
		this.onActivate = onActivate;
		this.onSendToPanel = onSendToPanel;
		this.context = context;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);

		status.setText("Searching…");

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, list.getFont().getSize()));
		list.setCellRenderer(pathRenderer());
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				maybeShowPopup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				maybeShowPopup(e);
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
					activateSelection();
				}
			}
		});
		// Enter navigates to the selected hit.
		list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.activate");
		list.getActionMap().put("find.activate", action(this::activateSelection));

		stopButton.setText("Stop");
		stopButton.addActionListener(e -> stopSearch());
		pauseButton.setText("Pause");
		pauseButton.addActionListener(e -> togglePause());
		panelButton.setText("Panel");
		panelButton.setToolTipText("Open these results in a temporary panel");
		panelButton.setEnabled(false);
		panelButton.addActionListener(e -> sendResultsToPanel());
		closeButton.setText("Close");
		closeButton.addActionListener(e -> dispose());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(stopButton);
		buttons.add(pauseButton);
		buttons.add(panelButton);
		buttons.add(closeButton);

		JPanel content = new JPanel(new BorderLayout(0, 8));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(status, BorderLayout.NORTH);
		content.add(new JScrollPane(list), BorderLayout.CENTER);
		content.add(buttons, BorderLayout.SOUTH);
		setContentPane(content);

		// Esc closes the results window.
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.results.close");
		getRootPane().getActionMap().put("find.results.close", action(this::dispose));

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				stopSearch();
			}
		});

		// Wide and centered on screen.
		setPreferredSize(new Dimension(960, 560));
		setMinimumSize(new Dimension(700, 380));
		pack();
		setLocationRelativeTo(null);
	}

	/** Bind the running search so the window can stop/pause it and release it on completion. */
	public void bind(FindFileService service, FindFileService.SearchHandle handle) {
		this.service = service;
		this.handle = handle;
	}

	@Override
	public void onMatch(NuclrResource resource) {
		model.addElement(resource);
		panelButton.setEnabled(onSendToPanel != null);
		refreshStatus(model.getSize(), -1, false);
	}

	@Override
	public void onProgress(long visited, long matched, NuclrResource current) {
		if (!finished) {
			refreshStatus(model.getSize(), visited, false);
		}
	}

	@Override
	public void onComplete(long visited, long matched, boolean cancelled) {
		finished = true;
		String verb = cancelled ? "stopped" : "complete";
		status.setText("Search " + verb + " — " + model.getSize() + " item(s), " + visited + " scanned");
		stopButton.setEnabled(false);
		pauseButton.setEnabled(false);
		if (service != null) {
			service.close();
		}
		if (cancelled) {
			emitCancelOnce();
		} else {
			SoundEvents.processComplete(context);
		}
	}

	@Override
	public void onError(NuclrResource resource, Exception error) {
		log.debug("Find error visiting {}: {}", resource != null ? resource.getFullPath() : null, error.getMessage());
	}

	private void refreshStatus(int found, long visited, boolean ignore) {
		boolean paused = handle != null && handle.isPaused();
		StringBuilder sb = new StringBuilder();
		sb.append(found).append(" item(s) found");
		if (visited >= 0) {
			sb.append(" — ").append(visited).append(" scanned");
		}
		sb.append(paused ? " — paused" : " — searching…");
		status.setText(sb.toString());
	}

	private void stopSearch() {
		if (handle != null && !finished && !handle.isCancelled()) {
			emitCancelOnce();
			handle.cancel();
		}
	}

	private void togglePause() {
		if (handle == null || finished) {
			return;
		}
		if (handle.isPaused()) {
			handle.resume();
			pauseButton.setText("Pause");
			SoundEvents.confirmation(context);
		} else {
			handle.pause();
			pauseButton.setText("Resume");
			SoundEvents.warning(context);
		}
		refreshStatus(model.getSize(), -1, false);
	}

	private void activateSelection() {
		NuclrResource selected = list.getSelectedValue();
		if (selected != null && onActivate != null) {
			onActivate.accept(selected);
		}
	}

	/** Hand the full result set to a temporary panel and close this window. */
	private void sendResultsToPanel() {
		if (onSendToPanel == null || model.isEmpty()) {
			return;
		}
		List<NuclrResource> snapshot = new ArrayList<>(model.getSize());
		for (int i = 0; i < model.getSize(); i++) {
			snapshot.add(model.getElementAt(i));
		}
		onSendToPanel.accept(snapshot);
		dispose();
	}

	// ------------------------------------------------------------------
	// Per-entry context menu
	// ------------------------------------------------------------------

	private void maybeShowPopup(MouseEvent e) {
		if (!e.isPopupTrigger()) {
			return;
		}
		int index = list.locationToIndex(e.getPoint());
		if (index < 0) {
			return;
		}
		list.setSelectedIndex(index);
		NuclrResource resource = model.getElementAt(index);
		SoundEvents.popup(context);
		buildContextMenu(resource).show(list, e.getX(), e.getY());
	}

	private JPopupMenu buildContextMenu(NuclrResource resource) {
		JPopupMenu menu = new JPopupMenu();
		menu.add(menuItem("Open file", () -> openFile(resource)));
		menu.add(menuItem("Reveal in File Manager", () -> reveal(resource)));
		menu.add(menuItem("Go to in panel", () -> activate(resource)));
		menu.addSeparator();
		menu.add(menuItem("Copy file", () -> copyFiles(resource)));
		menu.add(menuItem("Copy full path", () -> copyText(pathOf(resource))));
		menu.add(menuItem("Copy name", () -> copyText(resource.getName())));
		return menu;
	}

	private static JMenuItem menuItem(String label, Runnable action) {
		JMenuItem item = new JMenuItem(label);
		item.addActionListener(e -> action.run());
		return item;
	}

	private void activate(NuclrResource resource) {
		if (onActivate != null) {
			onActivate.accept(resource);
		}
	}

	private void openFile(NuclrResource resource) {
		Path path = resource.getPath();
		if (path == null) {
			return;
		}
		try {
			SystemOpen.open(path);
			SoundEvents.confirmation(context);
		} catch (Exception ex) {
			showError("Could not open " + resource.getName(), ex);
		}
	}

	private void reveal(NuclrResource resource) {
		Path path = resource.getPath();
		if (path == null) {
			return;
		}
		try {
			SystemOpen.reveal(path);
			SoundEvents.confirmation(context);
		} catch (Exception ex) {
			showError("Could not reveal " + resource.getName(), ex);
		}
	}

	private void copyFiles(NuclrResource resource) {
		Path path = resource.getPath();
		if (path == null) {
			return;
		}
		setClipboard(new FileListTransferable(List.of(path.toFile())));
	}

	private void copyText(String text) {
		if (text != null && !text.isEmpty()) {
			setClipboard(new StringSelection(text));
		}
	}

	private static String pathOf(NuclrResource resource) {
		Path path = resource.getPath();
		if (path != null) {
			return path.toAbsolutePath().toString();
		}
		return resource.getFullPath() != null ? resource.getFullPath() : resource.getName();
	}

	private void setClipboard(Transferable contents) {
		try {
			Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
			clipboard.setContents(contents, null);
			SoundEvents.confirmation(context);
		} catch (RuntimeException ex) {
			log.warn("Failed to set clipboard contents: {}", ex.getMessage());
			SoundEvents.error(context);
		}
	}

	private void showError(String message, Exception ex) {
		log.warn("{}: {}", message, ex.getMessage());
		SoundEvents.error(context);
		JOptionPane.showMessageDialog(this, message + "\n" + ex.getMessage(), "Find results",
				JOptionPane.WARNING_MESSAGE);
	}

	private void emitCancelOnce() {
		if (!cancellationEmitted) {
			cancellationEmitted = true;
			SoundEvents.cancel(context);
		}
	}

	private static DefaultListCellRenderer pathRenderer() {
		return new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
					boolean focus) {
				String text = value instanceof NuclrResource r ? pathOf(r) : String.valueOf(value);
				return super.getListCellRendererComponent(list, text, index, selected, focus);
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

	/** Minimal {@link Transferable} exposing files via {@link DataFlavor#javaFileListFlavor}. */
	private record FileListTransferable(List<File> files) implements Transferable {

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.javaFileListFlavor };
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor)) {
				throw new UnsupportedFlavorException(flavor);
			}
			return files;
		}
	}
}
