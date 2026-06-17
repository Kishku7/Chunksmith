# Chunksmith — NeoForge 1.21.4

Standalone build for **Minecraft 1.21.4** (NeoForge).

## Build

```
cd NeoForge/1.21.4
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 21.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
