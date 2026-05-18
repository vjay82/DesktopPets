package com.desktoppets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

/**
 * Tests that the table-driven cooldowns in {@link BehaviorEngine} are
 * configured for every throttled activity and stay in their declared
 * windows. Uses reflection because the lookup is package-private.
 */
final class BehaviorCooldownsTest {

    private static long compute(Activity a) throws Exception {
        Method m = BehaviorEngine.class.getDeclaredMethod("computeCooldownMs", Activity.class);
        m.setAccessible(true);
        return (long) m.invoke(null, a);
    }

    @Test
    void idleAndNeedDrivenHaveNoCooldown() throws Exception {
        assertEquals(0L, compute(Activities.IDLE));
        assertEquals(0L, compute(Activities.SLEEP));
        assertEquals(0L, compute(Activities.EAT));
        assertEquals(0L, compute(Activities.DRINK));
    }

    @Test
    void wanderCooldownInDeclaredWindow() throws Exception {
        // 30..90 s. Sample multiple times since the upper bound is random.
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long v = compute(Activities.WANDER);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(min >= 30_000L, "min was " + min);
        assertTrue(max <  90_000L, "max was " + max);
    }

    @Test
    void zoomiesCooldownInDeclaredWindow() throws Exception {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (int i = 0; i < 200; i++) {
            long v = compute(Activities.ZOOMIES);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertTrue(min >=  60_000L, "min was " + min);
        assertTrue(max <  180_000L, "max was " + max);
    }

    @Test
    void everyThrottledActivityHasNonZeroCooldown() throws Exception {
        Activity[] throttled = {
                Activities.WANDER, Activities.DISAPPEAR_REAPPEAR, Activities.ZOOMIES,
                Activities.PLAY_BALL, Activities.SEEK_PETTING, Activities.SCRATCH,
                Activities.DANCE, Activities.SPEAK, Activities.MAKE_SPACE
        };
        for (Activity a : throttled) {
            assertTrue(compute(a) > 0L, a.name() + " should have a cooldown");
        }
    }

    @Test
    void resolveReturnsValidPathsForCoreSpecies() {
        for (String k : new String[]{"cat", "dog", "ducky", "bird"}) {
            String p = Doodle.resolve(k + "/idle/0");
            assertNotNull(p, k + " idle/0 must resolve");
            assertTrue(p.startsWith("Sprites/"), p);
            assertTrue(p.endsWith(".svg"), p);
        }
        assertNull(Doodle.resolve(null));
        assertNull(Doodle.resolve("unknown-species/idle/0"));
    }
}
