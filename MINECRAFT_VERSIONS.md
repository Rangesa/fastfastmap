# Minecraft version builds

## Supported build targets

1.21.11, 26.1, 26.1.1, 26.1.2, 26.2.

Each target is a separate JAR. Install the JAR matching the exact Minecraft version,
Fabric Loader 0.20.1 or newer, and the matching Fabric API. Mod Menu is optional.
Minecraft 1.21.x uses Java 21 or newer; 26.x uses Java 25 or newer.

## Build

Install JDK 25 and Python 3; set JAVA_HOME to that JDK. From the repository root:

```sh
python scripts/build_versions.py
python scripts/build_versions.py --version 26.2
```

The pinned inputs are in `minecraft-versions.json`. Installable JARs are copied to
`build/versions/<minecraft-version>/`. The root project preserves the original
Minecraft target; version-specific ports live under `versions/`.

The build script checks the generated Minecraft dependency and checks every Mixin
injection target against the downloaded Minecraft bytecode. GitHub Actions builds
each supported version independently and uploads its installable JAR.

## Verification limits

Compilation, packaging, dependency metadata, and Mixin target existence are checked.
An interactive game session, world rendering, and multiplayer behavior have not
been verified. Chat LLM Translation also needs its local model/server setup for
translation; builds do not download models or start the inference server.
