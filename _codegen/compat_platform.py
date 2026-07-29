"""Chunksmith platform adapters -- THE single source for the per-cell platform classes.

D16: these 8 classes used to be hand-copied into all 26 build cells (121 files, the last
non-cog master in the mod). They are now owned here and materialised into each cell's
gen/ by cog-gen, exactly like every other source file.

WHY WHOLE-FILE VARIANTS RATHER THAN INLINE PREDICATES: the adapters carry real MC-era
drift (FabricWorld alone had 9 distinct bodies across 10 cells, NeoForgeWorld 11 across
16) and on 26 the *World classes are a STRUCTURAL fork, not a rename set -- different
interfaces implemented, extra fields, an extra ticket block. Whole-file era emitters are
the house pattern for exactly this (cf. bank-vault's compat_*.py). The CELL_MAP below is
the measured ground truth, so materialisation is byte-identical to what each cell shipped
before the migration -- the hash oracle in scripts/verify-platform.py proves it.

ADDING A CELL: add its 'Loader/version' key to CELL_MAP pointing at the variant it needs.
CHANGING BEHAVIOUR: edit the variant text below -- every cell mapped to it inherits the
change, which is the whole point of moving these out of 26 hand-maintained copies.
"""

import sys


# --------------------------------------------------------------------------
# FabricPlayer -- 5 variant(s) across 10 cells
# --------------------------------------------------------------------------
FABRICPLAYER_VARIANTS = {
    # used by: Fabric/1.21.10, Fabric/1.21.11, Fabric/1.21.8
    "3a23e2c0db9a": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Fabric/1.20.1, Fabric/1.20.4, Fabric/1.20.6, Fabric/1.21.1
    "3e079467e465": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(player.serverLevel());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Fabric/26
    "87e8ff6e0623": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.EnumSet;
import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), EnumSet.noneOf(Relative.class), location.getYaw(), location.getPitch(), true);
    }

    @Override
    public void sendActionBar(final String key) {
        player.sendOverlayMessage(formatColored(translateKey(key, false)));
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Fabric/1.21.5
    "c41197a96d98": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        // 1.21.5: ServerPlayer.level() still returns Level (the covariant ServerLevel override
        // landed in 1.21.6). A ServerPlayer's level is always a ServerLevel, so the cast is safe.
        return new FabricWorld((net.minecraft.server.level.ServerLevel) player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Fabric/1.21.4
    "dd07ee922b5c": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricPlayer extends FabricSender implements Player {
    private final ServerPlayer player;

    public FabricPlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new FabricWorld((ServerLevel) player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((FabricWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
}

# --------------------------------------------------------------------------
# FabricSender -- 3 variant(s) across 10 cells
# --------------------------------------------------------------------------
FABRICSENDER_VARIANTS = {
    # used by: Fabric/1.21.11
    "4874ca00411d": r"""package com.kishku7.chunksmith.platform;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricSender implements Sender {
    private static final boolean HAS_PERMISSIONS;

    static {
        boolean hasPermissions;
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            hasPermissions = true;
        } catch (ClassNotFoundException e) {
            hasPermissions = false;
        }
        HAS_PERMISSIONS = hasPermissions;
    }

    private final CommandSourceStack source;

    public FabricSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return hasPermission(permission, false);
    }

    public boolean hasPermission(final String permission, final boolean defaultOp) {
        if (HAS_PERMISSIONS) {
            if (defaultOp) {
                return Permissions.check(source, permission, 2);
            } else {
                return Permissions.check(source, permission, false);
            }
        } else {
            return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: Fabric/1.20.1, Fabric/1.20.4, Fabric/1.20.6, Fabric/1.21.1, Fabric/1.21.10, Fabric/1.21.4, Fabric/1.21.5, Fabric/1.21.8
    "672c53d13791": r"""package com.kishku7.chunksmith.platform;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricSender implements Sender {
    private static final boolean HAS_PERMISSIONS;

    static {
        boolean hasPermissions;
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            hasPermissions = true;
        } catch (ClassNotFoundException e) {
            hasPermissions = false;
        }
        HAS_PERMISSIONS = hasPermissions;
    }

    private final CommandSourceStack source;

    public FabricSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return hasPermission(permission, false);
    }

    public boolean hasPermission(final String permission, final boolean defaultOp) {
        if (HAS_PERMISSIONS) {
            if (defaultOp) {
                return Permissions.check(source, permission, 2);
            } else {
                return Permissions.check(source, permission, false);
            }
        } else {
            return source.hasPermission(2);
        }
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: Fabric/26
    "bc12b7117280": r"""package com.kishku7.chunksmith.platform;

import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class FabricSender implements Sender {
    private static final boolean HAS_PERMISSIONS;

    static {
        boolean hasPermissions;
        try {
            Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
            hasPermissions = true;
        } catch (ClassNotFoundException e) {
            hasPermissions = false;
        }
        HAS_PERMISSIONS = hasPermissions;
    }

    private final CommandSourceStack source;

    public FabricSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new FabricWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return hasPermission(permission, false);
    }

    public boolean hasPermission(final String permission, final boolean defaultOp) {
        if (HAS_PERMISSIONS) {
            if (defaultOp) {
                return Permissions.check(source, permission, source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER));
            } else {
                return Permissions.check(source, permission, false);
            }
        } else {
            return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
        }
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
}

# --------------------------------------------------------------------------
# FabricServer -- 3 variant(s) across 10 cells
# --------------------------------------------------------------------------
FABRICSERVER_VARIANTS = {
    # used by: Fabric/26
    "2109cb61565c": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithFabric;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FabricServer implements Server {
    private final ChunksmithFabric plugin;
    private final MinecraftServer server;

    public FabricServer(final ChunksmithFabric plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(Identifier.tryParse(name))
                .map(resourceLocation -> server.getLevel(ResourceKey.create(Registries.DIMENSION, resourceLocation)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().identifier().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(FabricWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new FabricWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new FabricSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(FabricPlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(FabricPlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: Fabric/1.20.1, Fabric/1.20.4, Fabric/1.20.6, Fabric/1.21.1, Fabric/1.21.10, Fabric/1.21.4, Fabric/1.21.5, Fabric/1.21.8
    "bdb369b2db5f": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithFabric;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FabricServer implements Server {
    private final ChunksmithFabric plugin;
    private final MinecraftServer server;

    public FabricServer(final ChunksmithFabric plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(ResourceLocation.tryParse(name))
                .map(resourceLocation -> server.getLevel(ResourceKey.create(Registries.DIMENSION, resourceLocation)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().location().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(FabricWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new FabricWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new FabricSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(FabricPlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(FabricPlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: Fabric/1.21.11
    "e58bbfc7e2ef": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithFabric;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class FabricServer implements Server {
    private final ChunksmithFabric plugin;
    private final MinecraftServer server;

    public FabricServer(final ChunksmithFabric plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(Identifier.tryParse(name))
                .map(identifier -> server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().identifier().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(FabricWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new FabricWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new FabricSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(FabricPlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(FabricPlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
}

# --------------------------------------------------------------------------
# FabricWorld -- 9 variant(s) across 10 cells
# --------------------------------------------------------------------------
FABRICWORLD_VARIANTS = {
    # used by: Fabric/1.20.6
    "22058249072c": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.21.5, Fabric/1.21.8
    "26bcd83653a9": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType CHUNKY = new TicketType(0L, false, TicketType.TicketUse.LOADING_AND_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.21.1
    "29b3406f3bca": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.21.4
    "2db2befa8051": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.20.4
    "3fd9b48b25de": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.21.11
    "a2e3c49d5235": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.SimpleRegionStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final Identifier soundId = Identifier.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.21.11: ChunkMap extends SimpleRegionStorage (ChunkStorage removed), which holds the IOWorker.
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/26
    "dee9ad810f22": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.lod.LodSupport;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.MinecraftServerAccess;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.mixin.SimpleRegionStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final int TICKING_LOAD_DURATION = Input.tryInteger(System.getProperty("chunksmith.tickingLoadDuration")).orElse(0);
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING);
    private static final TicketType CHUNKY_TICKING = new TicketType(TICKING_LOAD_DURATION * 20L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.pack());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse(null);
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            if (TICKING_LOAD_DURATION > 0) {
                serverChunkCache.addTicketWithRadius(CHUNKY_TICKING, chunkPos, 1);
            }
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run and
                        // voxy's ingest gate is satisfied.
                        // P1: a false return is backpressure -- retry the chunk instead of dropping it.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                        if (PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS) {
                            // note: to prevent pausing on dedicated server when Moonrise is present
                            ((MinecraftServerAccess) world.getServer()).setEmptyTicks(0);
                        }
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getRespawnData().pos();
        final float yaw = world.getRespawnData().yaw();
        final float pitch = world.getRespawnData().pitch();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        world.getServer()
                .registryAccess()
                .get(Registries.SOUND_EVENT)
                .flatMap(soundEventRegistry -> soundEventRegistry.value().getOptional(Identifier.tryParse(sound)))
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final Path directory = DimensionType.getStorageFolder(world.dimension(), world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.21.10
    "f628d5e197b8": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Fabric/1.20.1
    "f692bfa499d4": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class FabricWorld implements World, ServerLevelHolder {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public FabricWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new FabricBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        // Pre-1.20.5 the future resolves to an Either, not a ChunkResult.
                        if (throwable == null && result != null) {
                            result.left().ifPresent(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
}

# --------------------------------------------------------------------------
# NeoForgePlayer -- 5 variant(s) across 16 cells
# --------------------------------------------------------------------------
NEOFORGEPLAYER_VARIANTS = {
    # used by: Forge/1.21.4, NeoForge/1.21.4
    "19d3cb602068": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgePlayer extends NeoForgeSender implements Player {
    private final ServerPlayer player;

    public NeoForgePlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(player.serverLevel());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((NeoForgeWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Forge/1.20.1, Forge/1.20.4, Forge/1.20.6, Forge/1.21.1, NeoForge/1.20.6, NeoForge/1.21.1
    "28e8b1f38b59": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgePlayer extends NeoForgeSender implements Player {
    private final ServerPlayer player;

    public NeoForgePlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(player.serverLevel());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((NeoForgeWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Forge/1.21.10, Forge/1.21.11, Forge/1.21.8, NeoForge/1.21.10, NeoForge/1.21.11, NeoForge/1.21.8
    "5d2c5994c60c": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgePlayer extends NeoForgeSender implements Player {
    private final ServerPlayer player;

    public NeoForgePlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((NeoForgeWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: NeoForge/26
    "77f801fc5916": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.EnumSet;
import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgePlayer extends NeoForgeSender implements Player {
    private final ServerPlayer player;

    public NeoForgePlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((NeoForgeWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), EnumSet.noneOf(Relative.class), location.getYaw(), location.getPitch(), true);
    }

    @Override
    public void sendActionBar(final String key) {
        player.sendOverlayMessage(formatColored(translateKey(key, false)));
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
    # used by: Forge/1.21.5
    "92de497fb11f": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.util.Collections;
import com.kishku7.chunksmith.platform.util.Location;

import java.util.UUID;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgePlayer extends NeoForgeSender implements Player {
    private final ServerPlayer player;

    public NeoForgePlayer(final ServerPlayer player) {
        super(player.createCommandSourceStack());
        this.player = player;
    }

    @Override
    public boolean isPlayer() {
        return true;
    }

    @Override
    public String getName() {
        return player.getName().toString();
    }

    @Override
    public World getWorld() {
        // 1.21.5: ServerPlayer.level() still returns Level (the covariant ServerLevel override
        // landed in 1.21.6). A ServerPlayer's level is always a ServerLevel, so the cast is safe.
        return new NeoForgeWorld((net.minecraft.server.level.ServerLevel) player.level());
    }

    @Override
    public Location getLocation() {
        return new Location(getWorld(), player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        player.sendSystemMessage(formatColored(translateKey(key, prefixed, args)));
    }

    @Override
    public UUID getUUID() {
        return player.getUUID();
    }

    @Override
    public void teleport(final Location location) {
        player.teleportTo(((NeoForgeWorld) location.getWorld()).getWorld(), location.getX(), location.getY(), location.getZ(), Collections.emptySet(), location.getYaw(), location.getPitch(), false);
    }

    @Override
    public void sendActionBar(final String key) {
        player.displayClientMessage(formatColored(translateKey(key, false)), true);
    }

    private Component formatColored(final String message) {
        return Component.nullToEmpty(message.replaceAll("&(?=[0-9a-fk-orA-FK-OR])", "\u00A7"));
    }
}
""",
}

# --------------------------------------------------------------------------
# NeoForgeSender -- 5 variant(s) across 16 cells
# --------------------------------------------------------------------------
NEOFORGESENDER_VARIANTS = {
    # used by: Forge/1.21.11, NeoForge/1.21.11
    "6df9377b1ff3": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgeSender implements Sender {
    private final CommandSourceStack source;

    public NeoForgeSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        // 1.20.1 mojmap: gate on the vanilla operator level (op level 2 == gamemaster),
        // matching the fabric variant's default permission behavior.
        return source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: Forge/1.21.10, Forge/1.21.4, Forge/1.21.5, Forge/1.21.8, NeoForge/1.21.10, NeoForge/1.21.4, NeoForge/1.21.8
    "b03334014bf5": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgeSender implements Sender {
    private final CommandSourceStack source;

    public NeoForgeSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        // 1.20.1 mojmap: gate on the vanilla operator level (op level 2 == gamemaster),
        // matching the fabric variant's default permission behavior.
        return source.hasPermission(2);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: Forge/1.21.1, NeoForge/1.21.1
    "b3417df0ffa5": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgeSender implements Sender {
    private final CommandSourceStack source;

    public NeoForgeSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        // 1.21.1 mojmap: gate on the vanilla operator level (op level 2 == gamemaster),
        // matching the fabric variant's default permission behavior.
        return source.hasPermission(2);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: Forge/1.20.1, Forge/1.20.4, Forge/1.20.6, NeoForge/1.20.6
    "bbe5da5038e8": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgeSender implements Sender {
    private final CommandSourceStack source;

    public NeoForgeSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        // 1.20.6 mojmap: gate on the vanilla operator level (op level 2 == gamemaster),
        // matching the fabric variant's default permission behavior.
        return source.hasPermission(2);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
    # used by: NeoForge/26
    "fbafd290845e": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import com.kishku7.chunksmith.platform.util.Location;

import static com.kishku7.chunksmith.util.Translator.translateKey;

public class NeoForgeSender implements Sender {
    private final CommandSourceStack source;

    public NeoForgeSender(final CommandSourceStack source) {
        this.source = source;
    }

    @Override
    public boolean isPlayer() {
        return source.getEntity() instanceof ServerPlayer;
    }

    @Override
    public String getName() {
        return source.getTextName();
    }

    @Override
    public World getWorld() {
        return new NeoForgeWorld(source.getLevel());
    }

    @Override
    public Location getLocation() {
        final Vec3 pos = source.getPosition();
        final Vec2 rot = source.getRotation();
        return new Location(getWorld(), pos.x(), pos.y(), pos.z(), rot.x, rot.y);
    }

    @Override
    public boolean hasPermission(final String permission) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    @Override
    public void sendMessage(final String key, final boolean prefixed, final Object... args) {
        source.sendSuccess(() -> Component.nullToEmpty(translateKey(key, prefixed, args).replaceAll("&[0-9a-fk-orA-FK-OR]", "")), false);
    }
}
""",
}

# --------------------------------------------------------------------------
# NeoForgeServer -- 5 variant(s) across 16 cells
# --------------------------------------------------------------------------
NEOFORGESERVER_VARIANTS = {
    # used by: Forge/1.21.11
    "015b326e41fc": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithForge;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class NeoForgeServer implements Server {
    private final ChunksmithForge plugin;
    private final MinecraftServer server;

    public NeoForgeServer(final ChunksmithForge plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(Identifier.tryParse(name))
                .map(Identifier -> server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().identifier().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(NeoForgeWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new NeoForgeWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new NeoForgeSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(NeoForgePlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(NeoForgePlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: NeoForge/1.20.6, NeoForge/1.21.1, NeoForge/1.21.10, NeoForge/1.21.4, NeoForge/1.21.8
    "53b00cde3728": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithNeoForge;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class NeoForgeServer implements Server {
    private final ChunksmithNeoForge plugin;
    private final MinecraftServer server;

    public NeoForgeServer(final ChunksmithNeoForge plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(ResourceLocation.tryParse(name))
                .map(resourceLocation -> server.getLevel(ResourceKey.create(Registries.DIMENSION, resourceLocation)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().location().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(NeoForgeWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new NeoForgeWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new NeoForgeSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(NeoForgePlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(NeoForgePlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: NeoForge/1.21.11
    "652cbcfc1f62": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithNeoForge;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class NeoForgeServer implements Server {
    private final ChunksmithNeoForge plugin;
    private final MinecraftServer server;

    public NeoForgeServer(final ChunksmithNeoForge plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(Identifier.tryParse(name))
                .map(Identifier -> server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().identifier().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(NeoForgeWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new NeoForgeWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new NeoForgeSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(NeoForgePlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(NeoForgePlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: Forge/1.20.1, Forge/1.20.4, Forge/1.20.6, Forge/1.21.1, Forge/1.21.10, Forge/1.21.4, Forge/1.21.5, Forge/1.21.8
    "9519b6947329": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithForge;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class NeoForgeServer implements Server {
    private final ChunksmithForge plugin;
    private final MinecraftServer server;

    public NeoForgeServer(final ChunksmithForge plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(ResourceLocation.tryParse(name))
                .map(resourceLocation -> server.getLevel(ResourceKey.create(Registries.DIMENSION, resourceLocation)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().location().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(NeoForgeWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new NeoForgeWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new NeoForgeSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(NeoForgePlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(NeoForgePlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
    # used by: NeoForge/26
    "b8ef20b55e35": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerInterface;
import net.minecraft.server.level.ServerLevel;
import com.kishku7.chunksmith.ChunksmithNeoForge;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.integration.Integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class NeoForgeServer implements Server {
    private final ChunksmithNeoForge plugin;
    private final MinecraftServer server;

    public NeoForgeServer(final ChunksmithNeoForge plugin, final MinecraftServer server) {
        this.plugin = plugin;
        this.server = server;
    }

    @Override
    public Map<String, Integration> getIntegrations() {
        return Map.of();
    }

    @Override
    public Optional<World> getWorld(final String name) {
        return Optional.ofNullable(Identifier.tryParse(name))
                .map(resourceLocation -> server.getLevel(ResourceKey.create(Registries.DIMENSION, resourceLocation)))
                .or(() -> {
                    for (final ServerLevel level : server.getAllLevels()) {
                        if (name.equals(level.dimension().identifier().getPath())) {
                            return Optional.of(level);
                        }
                    }
                    return Optional.empty();
                })
                .map(NeoForgeWorld::new);
    }

    @Override
    public List<World> getWorlds() {
        final List<World> worlds = new ArrayList<>();
        server.getAllLevels().forEach(world -> worlds.add(new NeoForgeWorld(world)));
        return worlds;
    }

    @Override
    public int getMaxWorldSize() {
        if (server instanceof final ServerInterface serverInterface) {
            return serverInterface.getProperties().maxWorldSize;
        } else {
            return server.getAbsoluteMaxWorldSize();
        }
    }

    @Override
    public Sender getConsole() {
        return new NeoForgeSender(server.createCommandSourceStack());
    }

    @Override
    public Collection<Player> getPlayers() {
        return server.getPlayerList().getPlayers().stream().map(NeoForgePlayer::new).collect(Collectors.toList());
    }

    @Override
    public Optional<Player> getPlayer(final String name) {
        return Optional.ofNullable(server.getPlayerList().getPlayerByName(name)).map(NeoForgePlayer::new);
    }

    @Override
    public Config getConfig() {
        return plugin.getChunky().getConfig();
    }

    @Override
    public double getMillisPerTick() {
        return ((MinecraftServerExtension) server).chunksmith$getMillisPerTick();
    }
}
""",
}

# --------------------------------------------------------------------------
# NeoForgeWorld -- 11 variant(s) across 16 cells
# --------------------------------------------------------------------------
NEOFORGEWORLD_VARIANTS = {
    # used by: Forge/1.21.11
    "05a4beda3f61": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.SimpleRegionStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final Identifier soundId = Identifier.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.21.11: ChunkMap extends SimpleRegionStorage (ChunkStorage removed), which holds the IOWorker.
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.20.1
    "15b0be1a9d38": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        // Pre-1.20.5 the future resolves to an Either, not a ChunkResult.
                        if (throwable == null && result != null) {
                            result.left().ifPresent(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.20.6: ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.20.6, NeoForge/1.20.6
    "1fd2534cd269": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.20.6: ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.21.5, Forge/1.21.8, NeoForge/1.21.8
    "5e42817f0210": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType CHUNKY = new TicketType(0L, false, TicketType.TicketUse.LOADING_AND_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: NeoForge/1.21.11
    "7bf10bd451b2": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.SimpleRegionStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final Identifier soundId = Identifier.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.21.11: ChunkMap extends SimpleRegionStorage (ChunkStorage removed), which holds the IOWorker.
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.21.4, NeoForge/1.21.4
    "8323aa9b0b62": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.21.1
    "89acbe234555": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.21.1: ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: NeoForge/26
    "89c5f7323e2d": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.MinecraftServerAccess;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.mixin.SimpleRegionStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World, ServerLevelHolder {
    private static final int TICKING_LOAD_DURATION = Input.tryInteger(System.getProperty("chunksmith.tickingLoadDuration")).orElse(0);
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING);
    private static final TicketType CHUNKY_TICKING = new TicketType(TICKING_LOAD_DURATION * 20L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunksmith.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().identifier().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.pack());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse(null);
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            if (TICKING_LOAD_DURATION > 0) {
                serverChunkCache.addTicketWithRadius(CHUNKY_TICKING, chunkPos, 1);
            }
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                        if (PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS) {
                            // note: to prevent pausing on dedicated server when Moonrise is present
                            ((MinecraftServerAccess) world.getServer()).setEmptyTicks(0);
                        }
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getRespawnData().pos();
        final float yaw = world.getRespawnData().yaw();
        final float pitch = world.getRespawnData().pitch();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, pitch);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @Override
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        world.getServer()
                .registryAccess()
                .get(Registries.SOUND_EVENT)
                .flatMap(soundEventRegistry -> soundEventRegistry.value().getOptional(Identifier.tryParse(sound)))
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final Path directory = DimensionType.getStorageFolder(world.dimension(), world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((SimpleRegionStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.21.10, NeoForge/1.21.10
    "a2ce956542c5": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SequencedMap;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType CHUNKY = new TicketType(0L, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .flatMap(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status").orElse("");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addTicketWithRadius(CHUNKY, chunkPos, 0);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeTicketWithRadius(CHUNKY, chunkPos, 0);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final LevelData.RespawnData respawn = world.getRespawnData();
        final BlockPos pos = respawn.pos();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), respawn.yaw(), respawn.pitch());
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinY()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .lookupOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final SequencedMap<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: Forge/1.20.4
    "a76f05889949": r"""package com.kishku7.chunksmith.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLastAvailableStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((ignored, throwable) -> {
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.20.6: ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
    # used by: NeoForge/1.21.1
    "d066c86b9632": r"""package com.kishku7.chunksmith.platform;

import com.kishku7.chunksmith.lod.LodSupport;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import com.kishku7.chunksmith.PlatformCompat;
import com.kishku7.chunksmith.ducks.MinecraftServerExtension;
import com.kishku7.chunksmith.mixin.ChunkMapMixin;
import com.kishku7.chunksmith.mixin.ChunkStorageAccessor;
import com.kishku7.chunksmith.mixin.IOWorkerAccessor;
import com.kishku7.chunksmith.mixin.ServerChunkCacheMixin;
import com.kishku7.chunksmith.platform.util.Location;
import com.kishku7.chunksmith.util.Input;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class NeoForgeWorld implements World {
    private static final TicketType<Unit> CHUNKY = TicketType.create("chunky", (unit, unit2) -> 0);
    private static final boolean UPDATE_CHUNK_NBT = Boolean.getBoolean("chunky.updateChunkNbt");
    private final ServerLevel world;
    private final Border worldBorder;

    public NeoForgeWorld(final ServerLevel world) {
        this.world = world;
        this.worldBorder = new NeoForgeBorder(world.getWorldBorder());
    }

    @Override
    public String getName() {
        return world.dimension().location().toString();
    }

    @Override
    public String getKey() {
        return getName();
    }

    @Override
    public CompletableFuture<Boolean> isChunkGenerated(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> isChunkGenerated(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            final ChunkMap chunkStorage = serverChunkCache.chunkMap;
            final ChunkMapMixin chunkMapMixin = (ChunkMapMixin) chunkStorage;
            final ChunkHolder loadedChunkHolder = chunkMapMixin.invokeGetVisibleChunkIfPresent(chunkPos.toLong());
            if (loadedChunkHolder != null && loadedChunkHolder.getLatestStatus() == ChunkStatus.FULL) {
                return CompletableFuture.completedFuture(true);
            }
            if (UPDATE_CHUNK_NBT) {
                return chunkMapMixin.invokeReadChunk(chunkPos)
                        .thenApply(optionalNbt -> optionalNbt
                                .filter(chunkNbt -> chunkNbt.contains("Status"))
                                .map(chunkNbt -> chunkNbt.getString("Status"))
                                .map(status -> "minecraft:full".equals(status) || "full".equals(status))
                                .orElse(false));
            }
            final FieldSelector statusSelector = new FieldSelector(StringTag.TYPE, "Status");
            final CollectFields statusCollector = new CollectFields(statusSelector);
            return serverChunkCache.chunkScanner().scanChunk(chunkPos, statusCollector)
                    .thenApply(ignored -> {
                        if (statusCollector.getResult() instanceof final CompoundTag chunkNbt) {
                            final String status = chunkNbt.getString("Status");
                            return "minecraft:full".equals(status) || "full".equals(status);
                        }
                        return false;
                    });
        }
    }

    @Override
    public CompletableFuture<Void> getChunkAtAsync(final int x, final int z) {
        if (Thread.currentThread() != world.getServer().getRunningThread()) {
            return CompletableFuture.supplyAsync(() -> getChunkAtAsync(x, z), world.getServer()).thenCompose(Function.identity());
        } else {
            final ChunkPos chunkPos = new ChunkPos(x, z);
            final ServerChunkCache serverChunkCache = world.getChunkSource();
            serverChunkCache.addRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
            ((ServerChunkCacheMixin) serverChunkCache).invokeRunDistanceManagerUpdates();
            // note: when Moonrise is present, holders do not get created most of the time even after explicit distance manager update
            // so we force `create = true` *only if* Moonrise is present, as it breaks pausing for everyone else
            final boolean create = PlatformCompat.ENABLE_MOONRISE_WORKAROUNDS;
            return ((ServerChunkCacheMixin) world.getChunkSource()).invokeGetChunkFutureMainThread(x, z, ChunkStatus.FULL, create)
                    .thenApplyAsync(Function.identity(), ((ChunkMapMixin) serverChunkCache.chunkMap).getMainThreadExecutor()) // workaround to prevent memory leaks in vanilla chunk system when racing with entity chunks
                    .whenCompleteAsync((result, throwable) -> {
                        // The only moment a live chunk at FULL status exists on the main thread while it
                        // is still ticket-pinned. Offer it to the LOD sink BEFORE the ticket is released.
                        // FULL is downstream of the LIGHT status, so the light engine has already run.
                        if (throwable == null && result != null) {
                            result.ifSuccess(chunkAccess -> {
                                if (chunkAccess instanceof final LevelChunk levelChunk) {
                                    LodSupport.offer(world, levelChunk);
                                }
                            });
                        }
                        serverChunkCache.removeRegionTicket(CHUNKY, chunkPos, 0, Unit.INSTANCE);
                        ((MinecraftServerExtension) world.getServer()).chunksmith$markChunkSystemHousekeeping();
                    }, world.getServer())
                    .thenApply(ignored -> null);
        }
    }

    @Override
    public UUID getUUID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSeaLevel() {
        return world.getSeaLevel();
    }

    @Override
    public Location getSpawn() {
        final BlockPos pos = world.getSharedSpawnPos();
        final float yaw = world.getSharedSpawnAngle();
        return new Location(this, pos.getX(), pos.getY(), pos.getZ(), yaw, 0);
    }

    @Override
    public Border getWorldBorder() {
        return worldBorder;
    }

    @Override
    // isSolid() is @Deprecated in vanilla but has no public non-deprecated equivalent: it uniquely
    // exposes the cached legacySolid value (isSolidRender() is a different field). Kept intentionally.
    @SuppressWarnings("deprecation")
    public int getElevation(final int x, final int z) {
        final int height = world.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        final int logicalHeight = world.getLogicalHeight();
        if (height >= logicalHeight) {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, logicalHeight, z);
            int air = 0;
            while (pos.getY() > world.getMinBuildHeight()) {
                pos = pos.move(Direction.DOWN);
                final BlockState blockState = world.getBlockState(pos);
                if (blockState.isSolid() && air > 1) {
                    return pos.getY() + 1;
                }
                air = blockState.isAir() ? air + 1 : 0;
            }
        }
        return height;
    }

    @Override
    public int getMaxElevation() {
        return world.getLogicalHeight();
    }

    @Override
    public void playEffect(final Player player, final String effect) {
        final Location location = player.getLocation();
        final BlockPos pos = BlockPos.containing(location.getX(), location.getY(), location.getZ());
        Input.tryInteger(effect).ifPresent(eventId -> world.levelEvent(eventId, pos, 0));
    }

    @Override
    public void playSound(final Player player, final String sound) {
        final Location location = player.getLocation();
        final ResourceLocation soundId = ResourceLocation.tryParse(sound);
        if (soundId == null) {
            return;
        }
        world.getServer()
                .registryAccess()
                .registryOrThrow(Registries.SOUND_EVENT)
                .getOptional(soundId)
                .ifPresent(soundEvent -> world.playSound(null, location.getX(), location.getY(), location.getZ(), soundEvent, SoundSource.MASTER, 2f, 1f));
    }

    @Override
    public Optional<Path> getDirectory(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final ResourceKey<Level> dimension = world.dimension();
        final Path directory = DimensionType.getStorageFolder(dimension, world.getServer().getWorldPath(LevelResource.ROOT)).normalize().resolve(name);
        return Files.exists(directory) ? Optional.of(directory) : Optional.empty();
    }

    @Override
    public long getQueuedChunkWrites() {
        try {
            // 1.21.1: ChunkMap extends ChunkStorage, which holds the IOWorker (SimpleRegionStorage is the 26.x rename).
            final ChunkMap chunkMap = world.getChunkSource().chunkMap;
            final IOWorker worker = ((ChunkStorageAccessor) (Object) chunkMap).chunksmith$getWorker();
            if (worker == null) {
                return -1;
            }
            final Map<?, ?> pendingWrites = ((IOWorkerAccessor) (Object) worker).chunksmith$getPendingWrites();
            return pendingWrites == null ? -1 : pendingWrites.size();
        } catch (final Throwable t) {
            return -1;
        }
    }

    public ServerLevel getWorld() {
        return world;
    }
}
""",
}

# Measured cell -> variant map (ground truth at migration time, 2026-07-28).
CELL_MAP = {
    "FabricPlayer": {
        "Fabric/1.20.1": "3e079467e465",
        "Fabric/1.20.4": "3e079467e465",
        "Fabric/1.20.6": "3e079467e465",
        "Fabric/1.21.1": "3e079467e465",
        "Fabric/1.21.10": "3a23e2c0db9a",
        "Fabric/1.21.11": "3a23e2c0db9a",
        "Fabric/1.21.4": "dd07ee922b5c",
        "Fabric/1.21.5": "c41197a96d98",
        "Fabric/1.21.8": "3a23e2c0db9a",
        "Fabric/26": "87e8ff6e0623",
    },
    "FabricSender": {
        "Fabric/1.20.1": "672c53d13791",
        "Fabric/1.20.4": "672c53d13791",
        "Fabric/1.20.6": "672c53d13791",
        "Fabric/1.21.1": "672c53d13791",
        "Fabric/1.21.10": "672c53d13791",
        "Fabric/1.21.11": "4874ca00411d",
        "Fabric/1.21.4": "672c53d13791",
        "Fabric/1.21.5": "672c53d13791",
        "Fabric/1.21.8": "672c53d13791",
        "Fabric/26": "bc12b7117280",
    },
    "FabricServer": {
        "Fabric/1.20.1": "bdb369b2db5f",
        "Fabric/1.20.4": "bdb369b2db5f",
        "Fabric/1.20.6": "bdb369b2db5f",
        "Fabric/1.21.1": "bdb369b2db5f",
        "Fabric/1.21.10": "bdb369b2db5f",
        "Fabric/1.21.11": "e58bbfc7e2ef",
        "Fabric/1.21.4": "bdb369b2db5f",
        "Fabric/1.21.5": "bdb369b2db5f",
        "Fabric/1.21.8": "bdb369b2db5f",
        "Fabric/26": "2109cb61565c",
    },
    "FabricWorld": {
        "Fabric/1.20.1": "f692bfa499d4",
        "Fabric/1.20.4": "3fd9b48b25de",
        "Fabric/1.20.6": "22058249072c",
        "Fabric/1.21.1": "29b3406f3bca",
        "Fabric/1.21.10": "f628d5e197b8",
        "Fabric/1.21.11": "a2e3c49d5235",
        "Fabric/1.21.4": "2db2befa8051",
        "Fabric/1.21.5": "26bcd83653a9",
        "Fabric/1.21.8": "26bcd83653a9",
        "Fabric/26": "dee9ad810f22",
    },
    "NeoForgePlayer": {
        "Forge/1.20.1": "28e8b1f38b59",
        "Forge/1.20.4": "28e8b1f38b59",
        "Forge/1.20.6": "28e8b1f38b59",
        "Forge/1.21.1": "28e8b1f38b59",
        "Forge/1.21.10": "5d2c5994c60c",
        "Forge/1.21.11": "5d2c5994c60c",
        "Forge/1.21.4": "19d3cb602068",
        "Forge/1.21.5": "92de497fb11f",
        "Forge/1.21.8": "5d2c5994c60c",
        "NeoForge/1.20.6": "28e8b1f38b59",
        "NeoForge/1.21.1": "28e8b1f38b59",
        "NeoForge/1.21.10": "5d2c5994c60c",
        "NeoForge/1.21.11": "5d2c5994c60c",
        "NeoForge/1.21.4": "19d3cb602068",
        "NeoForge/1.21.8": "5d2c5994c60c",
        "NeoForge/26": "77f801fc5916",
    },
    "NeoForgeSender": {
        "Forge/1.20.1": "bbe5da5038e8",
        "Forge/1.20.4": "bbe5da5038e8",
        "Forge/1.20.6": "bbe5da5038e8",
        "Forge/1.21.1": "b3417df0ffa5",
        "Forge/1.21.10": "b03334014bf5",
        "Forge/1.21.11": "6df9377b1ff3",
        "Forge/1.21.4": "b03334014bf5",
        "Forge/1.21.5": "b03334014bf5",
        "Forge/1.21.8": "b03334014bf5",
        "NeoForge/1.20.6": "bbe5da5038e8",
        "NeoForge/1.21.1": "b3417df0ffa5",
        "NeoForge/1.21.10": "b03334014bf5",
        "NeoForge/1.21.11": "6df9377b1ff3",
        "NeoForge/1.21.4": "b03334014bf5",
        "NeoForge/1.21.8": "b03334014bf5",
        "NeoForge/26": "fbafd290845e",
    },
    "NeoForgeServer": {
        "Forge/1.20.1": "9519b6947329",
        "Forge/1.20.4": "9519b6947329",
        "Forge/1.20.6": "9519b6947329",
        "Forge/1.21.1": "9519b6947329",
        "Forge/1.21.10": "9519b6947329",
        "Forge/1.21.11": "015b326e41fc",
        "Forge/1.21.4": "9519b6947329",
        "Forge/1.21.5": "9519b6947329",
        "Forge/1.21.8": "9519b6947329",
        "NeoForge/1.20.6": "53b00cde3728",
        "NeoForge/1.21.1": "53b00cde3728",
        "NeoForge/1.21.10": "53b00cde3728",
        "NeoForge/1.21.11": "652cbcfc1f62",
        "NeoForge/1.21.4": "53b00cde3728",
        "NeoForge/1.21.8": "53b00cde3728",
        "NeoForge/26": "b8ef20b55e35",
    },
    "NeoForgeWorld": {
        "Forge/1.20.1": "15b0be1a9d38",
        "Forge/1.20.4": "a76f05889949",
        "Forge/1.20.6": "1fd2534cd269",
        "Forge/1.21.1": "89acbe234555",
        "Forge/1.21.10": "a2ce956542c5",
        "Forge/1.21.11": "05a4beda3f61",
        "Forge/1.21.4": "8323aa9b0b62",
        "Forge/1.21.5": "5e42817f0210",
        "Forge/1.21.8": "5e42817f0210",
        "NeoForge/1.20.6": "1fd2534cd269",
        "NeoForge/1.21.1": "d066c86b9632",
        "NeoForge/1.21.10": "a2ce956542c5",
        "NeoForge/1.21.11": "7bf10bd451b2",
        "NeoForge/1.21.4": "8323aa9b0b62",
        "NeoForge/1.21.8": "5e42817f0210",
        "NeoForge/26": "89c5f7323e2d",
    },
}


def platform_classes(loader, cell):
    """Class-name -> source text for this cell. `cell` is the build-cell dir name."""
    key = "%s/%s" % (loader, cell)
    out = {}
    for cls, mapping in CELL_MAP.items():
        h = mapping.get(key)
        if h is None:
            continue
        out[cls] = globals()[cls.upper() + "_VARIANTS"][h]
    return out


def platform_source(cls, loader, cell):
    """Source text for ONE adapter class in this cell, or None if the cell has none."""
    return platform_classes(loader, cell).get(cls)
