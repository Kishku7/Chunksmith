# Chunksmith — Fabric 1.21.8

Standalone build for **Minecraft 1.21.8** (Fabric).

## Build

```
cd Fabric/1.21.8
./gradlew build
```

- Toolchain: fabric-loom, JDK 21.
- Output: `build/libs/Chunksmith-Fabric-*.jar` (shaded).
- Shared `common/` + `nbt/` pulled from the branch root via relative include.
