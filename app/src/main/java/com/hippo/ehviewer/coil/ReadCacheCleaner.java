// Copyright 2026 Hycnyl. SPDX-License-Identifier: GPL-3.0-or-later
package com.hippo.ehviewer.coil;

import coil3.disk.DiskCache;
import coil3.disk.DiskLruCache;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.regex.Pattern;
import okio.FileMetadata;
import okio.FileSystem;
import okio.Path;

/** Maintenance adapter for the pinned Coil 3.3 cache; normal image loading remains unchanged. */
public final class ReadCacheCleaner {
    private static final Pattern DATA_FILE = Pattern.compile("[a-f0-9]{64}\\.1");

    private ReadCacheCleaner() {}

    public static int clearBefore(DiskCache diskCache, long cutoffMillis)
            throws ReflectiveOperationException, IOException, InterruptedException {
        checkInterrupted();
        // Coil exposes no key enumeration. Use its existing snapshot/remove lifecycle for
        // hashed on-disk keys, rather than unlinking files behind its journal. Java can access
        // the JVM-public DiskLruCache; proguard-rules.pro preserves this one private field.
        Field field = diskCache.getClass().getDeclaredField("cache");
        field.setAccessible(true);
        DiskLruCache cache = (DiskLruCache) field.get(diskCache);
        cache.initialize();
        FileSystem fileSystem = diskCache.getFileSystem();
        int removed = 0;
        for (Path path : fileSystem.list(diskCache.getDirectory())) {
            checkInterrupted();
            if (!DATA_FILE.matcher(path.name()).matches()) continue;
            String key = path.name().substring(0, 64);
            Path metadata = diskCache.getDirectory().resolve(key + ".0");
            // Leave recent entries' normal LRU order untouched.
            if (!olderThan(fileSystem, metadata, path, cutoffMillis)) continue;
            try (DiskLruCache.Snapshot snapshot = cache.get(key)) {
                if (snapshot == null) continue; // An edit may be in progress.
                // The snapshot pins this exact version, so a concurrent replacement cannot
                // turn an old-file check into deletion of a newly cached image.
                if (olderThan(fileSystem, snapshot.file(0), snapshot.file(1), cutoffMillis)
                        && cache.remove(key)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private static boolean olderThan(FileSystem fileSystem, Path metadata, Path data, long cutoff)
            throws IOException {
        return olderThan(fileSystem.metadataOrNull(metadata), cutoff)
                && olderThan(fileSystem.metadataOrNull(data), cutoff);
    }

    private static boolean olderThan(FileMetadata metadata, long cutoff) {
        if (metadata == null || !metadata.isRegularFile()) return false;
        Long modified = metadata.getLastModifiedAtMillis();
        return modified != null && modified > 0 && modified < cutoff;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
    }
}
