# Nuclr File Panel - Local Filesystem

Official Nuclr core plugin that provides local filesystem roots (drives/mount points) to the file panel.

## Overview

- Plugin ID: `dev.nuclr.plugin.core.panel.fs`
- Name: `Local Filesystem Panel`
- Version: `1.0.0`
- Plugin class: `dev.nuclr.plugin.core.panel.fs.LocalFileSystemPlugin`
- License: Apache-2.0

This plugin registers a `NuclrPlugin` that exposes one root per entry returned by `FileSystems.getDefault().getRootDirectories()`:

- Windows: typically `C:\`, `D:\`, etc.
- Linux/macOS: typically `/`

## Features

- Adds a `local` file panel provider.
- Displays provider name as `Local Filesystem`.
- Enumerates roots dynamically from the host OS.
- Uses priority `0`.

## Requirements

- Java 21
- Maven 3.9+ (recommended)
- Nuclr platform SDK dependency:
  - `dev.nuclr:platform-sdk:3.0.1`

## Build

Build and package:

```bash
mvn clean package
```

Create the detached signature too:

```bash
mvn clean verify -Djarsigner.storepass=<your-password>
```

## Output Artifacts

After build/verify, artifacts are placed in `target/`:

- `filepanel-fs-1.0.0.jar`
- `filepanel-fs-1.0.0.zip` (plugin package)
- `filepanel-fs-1.0.0.zip.sig` (created during `verify`)

Plugin ZIP contents:

- `filepanel-fs-1.0.0.jar`
- `lib/` runtime dependencies

## Install in Nuclr

1. Build `filepanel-fs-1.0.0.zip`.
2. Copy the ZIP (and `.sig` if required by your runtime) to your Nuclr plugins directory.
3. Restart Nuclr.

## Repository Layout

```text
src/main/java/dev/nuclr/plugin/core/panel/fs/LocalFileSystemPlugin.java
src/main/java/dev/nuclr/plugin/core/panel/fs/FileNuclrResource.java
src/assembly/plugin.xml
pom.xml
```
