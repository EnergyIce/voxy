PLEASE do not join cortex's server and complain/ask for support about this backport, no support will be given there, join https://discord.gg/6rH7nzmfg8 instead


# Voxy (Rework)

Voxy is an LoD rendering mod for Minecraft. This is a reworked build of the multiversion backport, available for:

| Minecraft | Loaders          |
|-----------|------------------|
| 1.20.1    | Fabric, Forge    |
| 1.21.1    | Fabric, NeoForge |

The mod ID is unchanged (`voxy`), so existing Voxy configs and LoD data keep working.

## ⚠️ Your game / modpack must run on Java 21

Voxy is built for **Java 21**, so the Minecraft instance (or modpack) you install it into has to be started with **Java 21 or newer**.

- **Minecraft 1.21.1:** already uses Java 21 by default, nothing to do.
- **Minecraft 1.20.1:** launchers and modpacks usually run 1.20.1 on **Java 17** - with Voxy installed the game will then crash on startup, typically with an `UnsupportedClassVersionError` mentioning `class file version 65.0`. Switch the instance to Java 21 in your launcher's instance/Java settings (e.g. Modrinth App, Prism Launcher, MultiMC, CurseForge all let you pick the Java installation per instance or globally). Forge 1.20.1 and Fabric 1.20.1 both run fine on Java 21.

If you don't have Java 21 installed yet, get it for example from [Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21).

## What's different in this rework

- **Camouflage blocks in the LoD:** Framed Blocks, Create Copycats and Copycats+ are rendered with their real material instead of the empty frame / bare copycat. Stairs, slopes, bytes, layers and mixed glass/solid blocks are approximated as closely as Voxy's model format allows.
- **TrafficCraft blocks in the LoD:** painted asphalt and concrete (blocks and slopes) show their real road markings, and barriers, guardrails, cones, bollards, barrels, barrier fences, reflectors, traffic lights, street/house number signs and paint buckets show their real colour instead of black. Traffic light lamps and sign text are drawn live by TrafficCraft's own renderers and are not part of the LoD.
- **No more invisible chunks at the edge of the render distance:** the LoD is now hidden exactly where Embeddium/Sodium really draws chunks (per pixel, using its own render distance rules), instead of an approximation that left holes along the border and next to chunks that were still loading.
- **Sloped blocks in the LoD:** blocks with tilted surfaces - e.g. all Macaw's Roofs roofs (normal, steep, lower, top, corners, attic roofs, awnings) - are rendered as a fine, closed staircase that follows the real slope, instead of stacked cubes with see-through gaps between the roof rows. Framed Blocks / Copycats+ slopes get the same treatment.
- **Create: Pantographs and Wires in the LoD:** masts, brackets and insulators are now visible. Cantilevers, tensioning devices, wires and pantographs are not part of the LoD.
- **Faster LoD loading in busy worlds:** camouflage/TrafficCraft instances that older versions stored many times over (e.g. for every state of a CRN display or traffic light) now share one baked model, and LoD sections waiting for such a bake no longer hold up the rest of the LoD. This fixes LoDs not appearing until Voxy was toggled off and on in worlds with LoD data from older versions.
- **No error messages on screen:** Voxy errors are only written to the log, no more white text in the action bar.
- **Distant Horizons import** supports DH's newer `DataFormatVersion 2` databases (`/voxy import distant_horizons`).

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

for example `build/libs/0.2.15-beta-rework.5/`. Each variant's jar is also in its own folder:

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
