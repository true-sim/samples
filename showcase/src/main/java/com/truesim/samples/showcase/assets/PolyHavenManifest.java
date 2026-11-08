package com.truesim.samples.showcase.assets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pinned list of the showcase's Poly Haven files ({@code polyhaven.tsv}): per file its asset, kind (texture or
 * model), resolution, path within the asset's folder, source URL, size and SHA-256. Lines starting with {@code #} are
 * comments.
 */
public record PolyHavenManifest(List<Entry> entries) {

    /** One file of an asset. */
    public record Entry(String asset, String kind, String resolution, String file, String url, long bytes,
        String sha256) {
    }

    public PolyHavenManifest {
        entries = List.copyOf(entries);
    }

    /** Reads a manifest. */
    public static PolyHavenManifest read(Path file) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] c = line.split("\t");
            if (c.length != 7) {
                throw new IOException("Not a manifest line (7 tab-separated cells): " + line);
            }
            entries.add(new Entry(c[0], c[1], c[2], c[3], c[4], Long.parseLong(c[5]), c[6]));
        }
        return new PolyHavenManifest(entries);
    }

    /** The assets named, sorted. */
    public Set<String> assets() {
        Set<String> assets = new TreeSet<>();
        for (Entry entry : entries) {
            assets.add(entry.asset());
        }
        return assets;
    }

    /** Whether every file is in {@code cache} (by name and size; the fetch checks the hashes). */
    public boolean presentIn(Path cache) {
        for (Entry entry : entries) {
            Path path = cache.resolve(entry.asset()).resolve(entry.file());
            try {
                if (!Files.isRegularFile(path) || Files.size(path) != entry.bytes()) {
                    return false;
                }
            } catch (IOException e) {
                return false;
            }
        }
        return true;
    }
}
