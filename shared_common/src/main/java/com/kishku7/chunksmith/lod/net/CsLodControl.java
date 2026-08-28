package com.kishku7.chunksmith.lod.net;

import java.util.Optional;
import java.util.OptionalInt;

public final class CsLodControl {

    @FunctionalInterface
    public interface Action {
        int rebind();
    }

    private static volatile Action action;
    private static volatile java.util.function.IntSupplier gamePort;
    private static volatile java.util.function.Supplier<String> describe;

    private CsLodControl() {
    }

    public static void register(final Action rebindAction,
                                final java.util.function.IntSupplier gamePortSupplier,
                                final java.util.function.Supplier<String> describeSupplier) {
        action = rebindAction;
        gamePort = gamePortSupplier;
        describe = describeSupplier;
    }

    public static void clear() {
        action = null;
        gamePort = null;
        describe = null;
    }

    public static OptionalInt apply() {
        final Action current = action;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.rebind());
    }

    public static OptionalInt gamePort() {
        final java.util.function.IntSupplier current = gamePort;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.getAsInt());
    }

    public static Optional<String> describe() {
        final java.util.function.Supplier<String> current = describe;
        return current == null ? Optional.empty() : Optional.ofNullable(current.get());
    }
}
