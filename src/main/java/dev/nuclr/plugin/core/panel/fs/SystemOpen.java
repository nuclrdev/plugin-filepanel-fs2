package dev.nuclr.plugin.core.panel.fs;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SystemOpen {

    private SystemOpen() {
    }

    public static void open(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Path absolutePath = path.toAbsolutePath().normalize();

        if (!Files.exists(absolutePath)) {
            throw new IOException("File does not exist: " + absolutePath);
        }

        // Preferred Java-native approach
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();

            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(absolutePath.toFile());
                return;
            }
        }

        // Fallback for minimal Linux environments / headless edge cases
        openWithCommand(absolutePath);
    }

    public static void reveal(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }

        Path absolutePath = path.toAbsolutePath().normalize();

        if (!Files.exists(absolutePath)) {
            throw new IOException("File does not exist: " + absolutePath);
        }

        String os = osName();

        if (os.contains("win")) {
            run("explorer.exe", "/select,", absolutePath.toString());
        } else if (os.contains("mac")) {
            run("open", "-R", absolutePath.toString());
        } else {
            // Linux has no universal "reveal file" command.
            // Opening parent folder is the safest fallback.
            Path parent = absolutePath.getParent();
            open(parent != null ? parent : absolutePath);
        }
    }

    public static void openUri(URI uri) throws IOException {
        if (uri == null) {
            throw new IllegalArgumentException("uri must not be null");
        }

        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();

            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(uri);
                return;
            }
        }

        openUriWithCommand(uri);
    }

    private static void openWithCommand(Path path) throws IOException {
        String os = osName();

        if (os.contains("win")) {
            run("cmd", "/c", "start", "", path.toString());
        } else if (os.contains("mac")) {
            run("open", path.toString());
        } else {
            run("xdg-open", path.toString());
        }
    }

    private static void openUriWithCommand(URI uri) throws IOException {
        String value = uri.toString();
        String os = osName();

        if (os.contains("win")) {
            run("cmd", "/c", "start", "", value);
        } else if (os.contains("mac")) {
            run("open", value);
        } else {
            run("xdg-open", value);
        }
    }

    private static void run(String... command) throws IOException {
        new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
    }

    private static String osName() {
        return System.getProperty("os.name", "").toLowerCase();
    }
}