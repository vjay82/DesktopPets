package com.desktoppets;

import java.util.function.BiConsumer;
import java.util.function.ToDoubleBiFunction;

/**
 * One discrete thing a pet can do (wander, sleep, eat, climb the foreground
 * window…). Modelled as data — a name, a priority function, and an action —
 * so the whole catalogue lives in {@link Activities} instead of one tiny
 * class per behaviour.
 *
 * <p>Higher priority wins; values &lt;= 0 mean "not applicable". The
 * {@link BehaviorEngine} multiplies the raw priority by the pet's
 * {@link Personality#multiplier(String) personality bias} before comparing.
 */
public record Activity(
        String name,
        ToDoubleBiFunction<Pet, World> priorityFn,
        BiConsumer<Pet, World> action) {

    public double priority(Pet pet, World world) {
        return priorityFn.applyAsDouble(pet, world);
    }

    public void perform(Pet pet, World world) {
        action.accept(pet, world);
    }
}
