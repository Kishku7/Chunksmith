# Chunksmith — Fabric 1.20.1

Standalone build for **Minecraft 1.20.1** (Fabric).

## Build

```
cd Fabric/1.20.1
./gradlew build
```

- Toolchain: fabric-loom, JDK 17.
- Output: `build/libs/Chunksmith-Fabric-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
