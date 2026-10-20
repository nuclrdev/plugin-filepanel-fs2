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
package dev.nuclr.plugin.core.panel.fs.tree;

import static dev.nuclr.plugin.core.panel.fs.Messages.tr;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.ExpandVetoException;
import javax.swing.tree.TreePath;

import com.formdev.flatlaf.extras.components.FlatButton;

/**
 * Modal Alt+F10 file/folder tree. Directory children are loaded lazily on virtual threads;
 * typing searches the rows that have already been populated without moving focus to a field.
 */
final class FileTreeDialog extends JDialog {

	private static final long serialVersionUID = 1L;
	private static final String CloseAction = "filetree.close";
	private static final String GoAction = "filetree.go";
	private static final String BackspaceAction = "filetree.backspace";

	private final transient Consumer<Path> onGo;
	private final PathNode computer = PathNode.computer();
	private final DefaultTreeModel model = new DefaultTreeModel(computer);
	/**
	 * Every JTree carries a {@code BasicTreeUI$Handler} key listener that runs its own
	 * prefix type-ahead. Left alone it moves the selection before this dialog's search does,
	 * so the two fight over every keystroke. Printable keys are handled here and never
	 * forwarded, which is the only way to suppress a listener installed by the UI delegate.
	 */
	private final JTree tree = new JTree(model) {
		private static final long serialVersionUID = 1L;

		@Override
		protected void processKeyEvent(KeyEvent e) {
			revealAbandoned = true;
			if (e.getID() == KeyEvent.KEY_TYPED && isSearchKey(e)) {
				typeIntoSearch(e);
				e.consume();
				return;
			}
			super.processKeyEvent(e);
		}
	};
	private final JLabel status = new JLabel(tr("tree.status.loading"));
	private final FlatButton goButton = new FlatButton();
	private volatile boolean closed;
	private String search = "";
	/**
	 * Set the moment the user drives the tree themselves. The opening reveal walks one
	 * asynchronous level at a time and re-asserts the selection after each one; a large
	 * directory takes hundreds of milliseconds per level, which is easily long enough for
	 * someone to open another drive first. Without this the late reveal step would drag the
	 * view back to the folder the panel started on.
	 */
	private boolean revealAbandoned;

	FileTreeDialog(Window owner, Path currentFolder, Consumer<Path> onGo) {
		super(owner, tr("tree.dialog.title"), ModalityType.APPLICATION_MODAL);
		this.onGo = onGo;

		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				closed = true;
			}
		});

		buildRoots();
		setContentPane(buildContent());
		installActions();
		installKeyBindings();

		setPreferredSize(new Dimension(820, 620));
		pack();
		setMinimumSize(new Dimension(480, 320));
		setLocationRelativeTo(owner);

		Path initial = normalize(currentFolder);
		SwingUtilities.invokeLater(() -> {
			reveal(initial);
			tree.requestFocusInWindow();
		});
	}

	private void buildRoots() {
		for (Path root : FileSystems.getDefault().getRootDirectories()) {
			computer.add(PathNode.path(root.toAbsolutePath().normalize(), true));
		}
		model.reload(computer);
		tree.expandPath(new TreePath(computer.getPath()));
	}

	private JPanel buildContent() {
		tree.setRootVisible(false);
		tree.setShowsRootHandles(true);
		tree.setExpandsSelectedPaths(true);
		tree.getSelectionModel().setSelectionMode(
				javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
		tree.addTreeSelectionListener(e -> updateSelectionState());
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				revealAbandoned = true;
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
					return;
				}
				TreePath clickedPath = tree.getPathForLocation(e.getX(), e.getY());
				Object value = clickedPath == null ? null : clickedPath.getLastPathComponent();
				if (value instanceof PathNode node && node.path != null && !node.directory) {
					tree.setSelectionPath(clickedPath);
					goToSelected();
				}
			}
		});
		tree.addTreeWillExpandListener(new TreeWillExpandListener() {
			@Override
			public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
				Object value = event.getPath().getLastPathComponent();
				if (value instanceof PathNode node) {
					ensureLoaded(node, null);
				}
			}

			@Override
			public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {
			}
		});
		goButton.setText(tr("tree.button.go"));
		goButton.setFocusable(false);
		goButton.addActionListener(e -> goToSelected());
		FlatButton closeButton = new FlatButton();
		closeButton.setText(tr("tree.button.close"));
		closeButton.setFocusable(false);
		closeButton.addActionListener(e -> close());

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttons.add(goButton);
		buttons.add(closeButton);

		status.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
		JPanel footer = new JPanel(new BorderLayout(12, 0));
		footer.add(status, BorderLayout.CENTER);
		footer.add(buttons, BorderLayout.EAST);

		JPanel content = new JPanel(new BorderLayout(0, 10));
		content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		content.add(new JScrollPane(tree), BorderLayout.CENTER);
		content.add(footer, BorderLayout.SOUTH);
		return content;
	}

	private void installActions() {
		tree.getActionMap().put(GoAction, action(this::goToSelected));
		tree.getActionMap().put(BackspaceAction, action(this::backspaceSearch));
		getRootPane().getActionMap().put(CloseAction, action(this::close));
	}

	private void installKeyBindings() {
		tree.getInputMap(JComponent.WHEN_FOCUSED)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), GoAction);
		tree.getInputMap(JComponent.WHEN_FOCUSED)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), BackspaceAction);
		getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CloseAction);
	}

	/** Expand asynchronous levels one at a time until the panel's current folder is selected. */
	private void reveal(Path target) {
		if (revealAbandoned) {
			return;
		}
		PathNode root = rootFor(target);
		if (root == null) {
			return;
		}
		TreePath rootPath = new TreePath(root.getPath());
		tree.setSelectionPath(rootPath);
		tree.scrollPathToVisible(rootPath);
		if (target == null || root.path.equals(target)) {
			ensureLoaded(root, null);
			return;
		}
		revealBelow(root, target);
	}

	private void revealBelow(PathNode parent, Path target) {
		ensureLoaded(parent, () -> {
			if (revealAbandoned) {
				return;
			}
			Path nextPath = FileTreeLoader.childTowards(parent.path, target);
			if (nextPath == null) {
				return;
			}
			PathNode next = child(parent, nextPath);
			if (next == null || !next.directory) {
				return;
			}
			TreePath treePath = new TreePath(next.getPath());
			tree.expandPath(new TreePath(parent.getPath()));
			tree.setSelectionPath(treePath);
			tree.scrollPathToVisible(treePath);
			if (!next.path.equals(target)) {
				revealBelow(next, target);
			}
		});
	}

	private void ensureLoaded(PathNode node, Runnable after) {
		if (!node.directory || node.path == null) {
			if (after != null) {
				after.run();
			}
			return;
		}
		if (after != null) {
			node.afterLoad.add(after);
		}
		if (node.state == LoadState.LOADED) {
			runAfterLoad(node);
			return;
		}
		if (node.state == LoadState.LOADING) {
			return;
		}

		node.state = LoadState.LOADING;
		status.setText(tr("tree.status.loading_path", node.path));
		FileTreeLoader.load(node.path, result -> SwingUtilities.invokeLater(() -> finishLoad(node, result)));
	}

	private void finishLoad(PathNode node, FileTreeLoader.Result result) {
		if (closed) {
			return;
		}
		node.removeAllChildren();
		for (FileTreeLoader.Entry entry : result.entries()) {
			node.add(PathNode.path(entry.path(), entry.directory()));
		}
		if (result.error() != null) {
			node.add(PathNode.message(tr("tree.error.unable_to_read", safeMessage(result.error()))));
		}
		node.state = LoadState.LOADED;
		model.nodeStructureChanged(node);
		updateSelectionState();
		runAfterLoad(node);
		if (!search.isEmpty()) {
			selectMatching(search, false);
		}
	}

	private void runAfterLoad(PathNode node) {
		if (node.afterLoad.isEmpty()) {
			return;
		}
		List<Runnable> callbacks = List.copyOf(node.afterLoad);
		node.afterLoad.clear();
		callbacks.forEach(Runnable::run);
	}

	/** A printable keystroke with no modifier, i.e. one that should extend the search. */
	private static boolean isSearchKey(KeyEvent event) {
		char typed = event.getKeyChar();
		return !event.isControlDown() && !event.isAltDown() && !event.isMetaDown()
				&& typed != KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(typed);
	}

	private void typeIntoSearch(KeyEvent event) {
		char typed = event.getKeyChar();
		search += typed;
		if (!selectMatching(search, true)) {
			// Repeating a first character cycles through matching populated rows.
			search = String.valueOf(typed);
			selectMatching(search, true);
		}
		updateSelectionState();
	}

	private void backspaceSearch() {
		if (search.isEmpty()) {
			return;
		}
		search = search.substring(0, search.length() - 1);
		if (!search.isEmpty()) {
			selectMatching(search, false);
		}
		updateSelectionState();
	}

	/** Search populated/visible rows, wrapping once after the current selection. */
	private boolean selectMatching(String query, boolean startAfterSelection) {
		if (query == null || query.isEmpty() || tree.getRowCount() == 0) {
			return false;
		}
		String needle = query.toLowerCase(Locale.ROOT);
		int selected = tree.getLeadSelectionRow();
		int start = startAfterSelection && selected >= 0 ? selected + 1 : 0;
		for (int offset = 0; offset < tree.getRowCount(); offset++) {
			int row = (start + offset) % tree.getRowCount();
			TreePath candidate = tree.getPathForRow(row);
			Object value = candidate == null ? null : candidate.getLastPathComponent();
			if (value instanceof PathNode node && node.path != null
					&& node.toString().toLowerCase(Locale.ROOT).contains(needle)) {
				tree.setSelectionPath(candidate);
				tree.scrollPathToVisible(candidate);
				return true;
			}
		}
		return false;
	}

	private void updateSelectionState() {
		PathNode node = selectedNode();
		goButton.setEnabled(node != null && node.path != null);
		if (!search.isEmpty()) {
			status.setText(tr("tree.status.search", search));
		} else if (node != null && node.path != null) {
			status.setText(node.path.toString());
		} else {
			status.setText("");
		}
	}

	private void goToSelected() {
		PathNode node = selectedNode();
		if (node == null || node.path == null) {
			return;
		}
		Path selected = node.path;
		close();
		if (onGo != null) {
			SwingUtilities.invokeLater(() -> onGo.accept(selected));
		}
	}

	private PathNode selectedNode() {
		Object selected = tree.getLastSelectedPathComponent();
		return selected instanceof PathNode node ? node : null;
	}

	private PathNode rootFor(Path target) {
		Path targetRoot = target == null ? null : target.getRoot();
		for (int index = 0; index < computer.getChildCount(); index++) {
			PathNode root = (PathNode) computer.getChildAt(index);
			if (targetRoot != null && root.path.equals(targetRoot)) {
				return root;
			}
		}
		if (targetRoot != null) {
			// A UNC share is not one of the roots getRootDirectories() enumerates. Add it rather
			// than falling back to an unrelated drive: the reveal would walk the wrong tree, and
			// relativizing across two different roots throws.
			PathNode added = PathNode.path(targetRoot, true);
			computer.add(added);
			model.nodeStructureChanged(computer);
			tree.expandPath(new TreePath(computer.getPath()));
			return added;
		}
		return computer.getChildCount() == 0 ? null : (PathNode) computer.getChildAt(0);
	}

	private static PathNode child(PathNode parent, Path wanted) {
		for (int index = 0; index < parent.getChildCount(); index++) {
			Object value = parent.getChildAt(index);
			if (value instanceof PathNode child && Objects.equals(child.path, wanted)) {
				return child;
			}
		}
		return null;
	}

	private static Path normalize(Path path) {
		return path == null ? null : path.toAbsolutePath().normalize();
	}

	private static String safeMessage(Exception error) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
	}

	private void close() {
		closed = true;
		dispose();
	}

	private static AbstractAction action(Runnable body) {
		return new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				body.run();
			}
		};
	}

	private enum LoadState {
		UNLOADED, LOADING, LOADED
	}

	private static final class PathNode extends DefaultMutableTreeNode {
		private static final long serialVersionUID = 1L;

		private final Path path;
		private final boolean directory;
		private final String label;
		private final List<Runnable> afterLoad = new ArrayList<>();
		private LoadState state;

		private PathNode(Path path, boolean directory, String label, LoadState state) {
			super(label, directory);
			this.path = path;
			this.directory = directory;
			this.label = label;
			this.state = state;
			if (directory && path != null) {
				add(message(tr("tree.status.loading")));
			}
		}

		static PathNode computer() {
			return new PathNode(null, true, tr("tree.node.computer"), LoadState.LOADED);
		}

		static PathNode path(Path path, boolean directory) {
			return new PathNode(path, directory, FileTreeLoader.displayName(path),
					directory ? LoadState.UNLOADED : LoadState.LOADED);
		}

		static PathNode message(String label) {
			return new PathNode(null, false, label, LoadState.LOADED);
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
