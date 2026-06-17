# Chunksmith — NeoForge 1.20.4

Standalone build for **Minecraft 1.20.4** (NeoForge).

## Build

```
cd NeoForge/1.20.4
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 17.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (the shaded jar; the `-noshade`/`-slim` jar is the un-shaded one).
- Shared `common/` + `nbt/` are pulled from the branch root via relative include.
