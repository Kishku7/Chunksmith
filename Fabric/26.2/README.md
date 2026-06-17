# Chunksmith — Fabric 26.2

Standalone build for **Minecraft 26.2** (Fabric).

## Build

```
cd Fabric/26.2
./gradlew build
```

- Toolchain: fabric-loom, JDK 21.
- Output: `build/libs/Chunksmith-Fabric-*.jar` (the shaded jar; the `-noshade`/`-slim` jar is the un-shaded one).
- Shared `common/` + `nbt/` are pulled from the branch root via relative include.
