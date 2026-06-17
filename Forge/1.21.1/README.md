# Chunksmith — Forge 1.21.1

Standalone build for **Minecraft 1.21.1** (Forge).

## Build

```
cd Forge/1.21.1
./gradlew build
```

- Toolchain: ForgeGradle 6, JDK 21.
- Output: `build/libs/Chunksmith-Forge-*.jar` (the shaded jar; the `-noshade`/`-slim` jar is the un-shaded one).
- Shared `common/` + `nbt/` are pulled from the branch root via relative include.
