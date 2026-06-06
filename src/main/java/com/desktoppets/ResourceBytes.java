package com.desktoppets;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Reads classpath resources fully into memory under a single process-wide
 * lock.
 *
 * <p>Sprite art is loaded lazily and in parallel: every pet runs on its own
 * thread and each calls {@code computeIfAbsent} on a shared sprite cache, so
 * many <em>different</em> SVGs are read from the application jar at the same
 * time. Concurrently opening and reading multiple entries of the same jar's
 * backing {@link java.util.zip.ZipFile} corrupts its shared read position and
 * throws {@code java.util.zip.ZipException: ZipFile invalid LOC header (bad
 * signature)}, which surfaces as blank or garbled sprites.
 *
 * <p>Serialising the reads here — and copying each entry into a {@code byte[]}
 * before returning, so the jar stream is touched only while the lock is held —
 * eliminates that race. The expensive part (SVG/PNG decoding) still runs
 * concurrently in the callers, off the bytes returned here. The lock only
 * contends briefly during start-up warm-up; once the caches are populated each
 * resource is read exactly once.
 */
final class ResourceBytes {

    /** Shared by {@link Doodle} and {@link Sprites} because both read from the
     *  same application jar — a per-class lock would still let the two race. */
    private static final Object JAR_READ_LOCK = new Object();

    private ResourceBytes() {
    }

    /**
     * Fully read {@code /classpathPath} into a byte array, or return
     * {@code null} if the resource is missing or unreadable. The jar read is
     * serialised across all callers.
     */
    static byte[] read(String classpathPath) {
        URL url = ResourceBytes.class.getResource("/" + classpathPath);
        if (url == null) {
            return null;
        }
        synchronized (JAR_READ_LOCK) {
            try (InputStream in = url.openStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        }
    }
}
