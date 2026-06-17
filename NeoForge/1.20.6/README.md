# Chunksmith — NeoForge 1.20.6

Standalone build for **Minecraft 1.20.6** (NeoForge).

## Build

```
cd NeoForge/1.20.6
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 21.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
