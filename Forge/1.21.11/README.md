# Chunksmith — Forge 1.21.8

Standalone build for **Minecraft 1.21.8** (Forge).

## Build

```
cd Forge/1.21.8
./gradlew build
```

- Toolchain: ForgeGradle 6, JDK 21.
- Output: `build/libs/Chunksmith-Forge-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
