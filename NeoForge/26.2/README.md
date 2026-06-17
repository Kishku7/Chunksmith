# Chunksmith — NeoForge 26.2

Standalone build for **Minecraft 26.2** (NeoForge).

## Build

```
cd NeoForge/26.2
./gradlew build
```

- Toolchain: ModDevGradle / NeoGradle, JDK 21.
- Output: `build/libs/Chunksmith-NeoForge-*.jar` (the shaded jar; the `-noshade`/`-slim` jar is the un-shaded one).
- Shared `common/` + `nbt/` are pulled from the branch root via relative include.
