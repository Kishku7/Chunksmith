# Chunksmith — Fabric 26.2

Standalone build for **Minecraft 26.2** (Fabric).

## Build

```
cd Fabric/26.2
./gradlew build
```

- Toolchain: fabric-loom, JDK 25.
- Output: `build/libs/Chunksmith-Fabric-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
