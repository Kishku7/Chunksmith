package com.kishku7.chunksmith.lod.net;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public final class CsLodControl {

    @FunctionalInterface
    public interface Action {
        int rebind();
    }

    private static volatile Action action;
    private static volatile IntSupplier gamePort;
    private static volatile Supplier<String> describe;

    private CsLodControl() {
    }

    public static void register(final Action rebindAction,
                                final IntSupplier gamePortSupplier,
                                final Supplier<String> describeSupplier) {
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
        Action current = action;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.rebind());
    }

    public static OptionalInt gamePort() {
        IntSupplier current = gamePort;
        return current == null ? OptionalInt.empty() : OptionalInt.of(current.getAsInt());
    }

    public static Optional<String> describe() {
        Supplier<String> current = describe;
        return current == null ? Optional.empty() : Optional.ofNullable(current.get());
    }
}
