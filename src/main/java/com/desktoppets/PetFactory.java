package com.desktoppets;

import java.util.Locale;

/** Maps a pet name string from config to a freshly-constructed {@link Pet}. */
public final class PetFactory {

    private PetFactory() {
    }

    public static Pet create(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "ducky" -> new Ducky();
            case "cat"   -> new Cat();
            case "dog"   -> new Dog();
            case "bird"  -> new Bird();
            default      -> throw new IllegalArgumentException(
                    "'" + name + "' is not a viable pet choice. Supported: Ducky, Cat, Dog, Bird.");
        };
    }
}
