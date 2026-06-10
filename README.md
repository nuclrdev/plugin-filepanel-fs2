# 🗂️ Local Filesystem Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that adds local drives and mount points as a file panel root. It enumerates roots dynamically from the host OS via `FileSystems.getDefault().getRootDirectories()` — Windows shows `C:\`, `D:\`, etc.; Linux and macOS show `/`.

## ✨ What it does

| Feature | Details |
|---|---|
| 🖥️ Drive enumeration | Exposes all root directories reported by the host OS |
| 📁 File operations | Copy, move, delete, create new folder |
| 🔗 Symlink resolution | Resolves Windows junctions and symlinks to their readable targets |
| 📊 Selection summary | Human-readable sizes for single-file and multi-file selections |
| 📂 Directory walking | Recursive tree walk with cancellation support |
| 📋 Context menu | Open, Reveal in File Manager, Delete |
| ⌨️ Go to path | `Ctrl+Shift+G` (Windows) / `Shift+Cmd+G` (macOS) |

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-fs-<version>.zip
filepanel-fs-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

The plugin registers as a `FilePanelNuclrPlugin` with priority 0 (the default local-filesystem priority). Each call to `getRoots()` re-enumerates `FileSystems.getDefault().getRootDirectories()` so removable drives and new mounts are picked up without restarting. Delete operations display a confirmation dialog followed by a progress dialog for recursive deletions; `DeleteService` performs the actual tree walk on a virtual thread.

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/panel/fs/
├── LocalFileSystemPlugin.java    plugin entry point, file operations
├── FileNuclrResource.java        NuclrResource wrapper for local files
├── Helper.java                   symlink resolution, size formatting
├── DeleteDialogs.java            delete confirmation dialogs
├── DeleteProgressDialog.java     deletion progress UI
└── service/
    ├── Alerts.java
    ├── DeleteService.java
    └── MakeNewFolderService.java
```

## 📚 Dependencies

All dependencies are provided by Nuclr Commander at runtime — nothing extra is bundled in the plugin ZIP.

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.1` | Nuclr platform interfaces |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
