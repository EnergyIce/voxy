PLEASE do not join cortex's server and complain/ask for support about this backport, no support will be given there, join https://discord.gg/6rH7nzmfg8 instead


# Voxy (Rework)

Voxy is an LoD rendering mod for Minecraft. This is a reworked build of the multiversion backport, available for:

| Minecraft | Loaders          |
|-----------|------------------|
| 1.20.1    | Fabric, Forge    |
| 1.21.1    | Fabric, NeoForge |

The mod ID is unchanged (`voxy`), so existing Voxy configs and LoD data keep working.

## What's different in this rework

- **Camouflage blocks in the LoD:** Framed Blocks, Create Copycats and Copycats+ are rendered with their real material instead of the empty frame / bare copycat. Stairs, slopes, bytes, layers and mixed glass/solid blocks are approximated as closely as Voxy's model format allows.
- **No more freeze on disconnect:** leaving a server, or being kicked by a server restart, no longer hangs the game.
- **Distant Horizons import** supports DH's newer `DataFormatVersion 2` databases (`/voxy import distant_horizons`).

### Camouflage debug switches

On first launch the file `config/voxy_camo_debug.properties` is created. All switches default to the shipped behaviour; you only need it to track down rendering problems.

| Key | Default | Meaning |
|-----|---------|---------|
| `enabled` | `true` | Master switch for camouflage block support |
| `multiPlane` | `true` | Split stepped/sloped blocks into several depth planes |
| `mixedLayers` | `true` | Keep glass and solid parts of one block as separate layers |
| `singleSided` | `true` | Hide the back faces of camouflage models |
| `twoSided` | `true` | Two-sided rasterisation while baking |
| `faceOcclusion` | `true` | Per-face occlusion for translucent camouflage models |
| `diagnostics` | `false` | Log statistics and dump baked textures to `voxy_camo_dump/` |

Restart the game after editing the file.

## Building from source

### Requirements

- **JDK 21** (for example [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21)). Make sure `java -version` prints version 21, or point `JAVA_HOME` at your JDK 21 installation.
- **Git** (optional, only needed to clone the repository - you can also download the source as a ZIP).
- About **4 GB of free RAM** and a few GB of disk space. The first build downloads Minecraft, the mod loaders and all dependencies, which takes a while.

You do **not** need to install Gradle - the included Gradle wrapper (`gradlew` / `gradlew.bat`) downloads the correct version automatically.

### 1. Get the source

```bash
git clone https://github.com/EnergyIce/voxy.git
cd voxy
git checkout fix/framedblocks-create-copycat-compat
```

(Or download the branch as a ZIP from GitHub and extract it.)

### 2. Build

**Windows** (Command Prompt or PowerShell, inside the `voxy` folder):

```bat
gradlew.bat buildAndCollect
```

**Linux / macOS:**

```bash
chmod +x gradlew
./gradlew buildAndCollect
```

This builds all four variants (1.20.1 Fabric/Forge, 1.21.1 Fabric/NeoForge).

### 3. Find the jars

All finished jars are collected in:

```
build/libs/<mod version>/
```

for example `build/libs/0.2.15-beta-rework.3/`. Each variant's jar is also in its own folder:

| Variant | Output folder | Jar name |
|---------|---------------|----------|
| 1.20.1 Fabric | `versions/1.20.1-fabric/build/libs/` | `voxy-<version>+1.20.1-fabric.jar` |
| 1.20.1 Forge | `versions/1.20.1-forge/build/libs/` | `voxy-<version>+1.20.1-legacyforge.jar` |
| 1.21.1 Fabric | `versions/1.21.1-fabric/build/libs/` | `voxy-<version>+1.21.1-fabric.jar` |
| 1.21.1 NeoForge | `versions/1.21.1-neoforge/build/libs/` | `voxy-<version>+1.21.1-neoforge.jar` |

Use the jar **without** `-sources` in its name and put it into your instance's `mods` folder (remove any older Voxy jar first).

> **Forge 1.20.1:** only use the jar from `build/libs`. The jar in `versions/1.20.1-forge/build/devlibs/` is a development build that crashes in a normal Forge installation.

### Building only one variant

Building a single variant is much faster. Replace `buildAndCollect` with the variant's task, e.g.:

```bash
./gradlew :1.20.1-forge:build
./gradlew :1.20.1-fabric:build
./gradlew :1.21.1-fabric:build
./gradlew :1.21.1-neoforge:build
```

(on Windows use `gradlew.bat` instead of `./gradlew`). Add `-x test` to skip the tests.

### Troubleshooting

- **`Unsupported class file major version` / wrong Java:** Gradle is running on the wrong JDK. Install JDK 21 and set `JAVA_HOME` to it, then open a new terminal.
- **Out of memory during the build:** raise `org.gradle.jvmargs=-Xmx2G` in `gradle.properties` (e.g. to `-Xmx4G`).
- **Changed `mod_version` / `mod_name` in `gradle.properties` but the jar still shows the old values:** run the build once with `--rerun-tasks`, e.g. `./gradlew buildAndCollect --rerun-tasks`.
- **Strange errors after switching branches:** run `./gradlew clean` and build again.
- **Network / download errors on the first build:** simply run the command again; already downloaded files are cached.
