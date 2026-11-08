package com.truesim.samples.showcase.assets;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Fetches the showcase's Poly Haven content (CC0) into a local cache: every file the manifest ({@code polyhaven.tsv})
 * pins, from Poly Haven's CDN, kept only when its SHA-256 matches the manifest's. Files already in the cache and
 * matching are left alone, so a second run downloads nothing. {@code ./gradlew :samples-showcase:fetchAssets}.
 */
public final class PolyHavenFetch {

    private PolyHavenFetch() {
    }

    /** {@code args}: the manifest, the cache folder. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PolyHavenFetch <polyhaven.tsv> <cache folder>");
        }
        fetch(PolyHavenManifest.read(Path.of(args[0])), Path.of(args[1]));
    }

    /** Fetches what {@code cache} lacks of {@code manifest} or holds changed; throws if a file cannot be had. */
    public static void fetch(PolyHavenManifest manifest, Path cache) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        int fetched = 0;
        long bytes = 0;
        List<PolyHavenManifest.Entry> entries = manifest.entries();
        for (PolyHavenManifest.Entry entry : entries) {
            Path target = cache.resolve(entry.asset()).resolve(entry.file());
            if (Files.isRegularFile(target) && sha256(Files.readAllBytes(target)).equals(entry.sha256())) {
                continue;
            }
            byte[] data = download(client, entry);
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(target.getFileName() + ".part");
            Files.write(part, data);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            fetched++;
            bytes += data.length;
            System.out.printf("%s/%s (%d kB)%n", entry.asset(), entry.file(), data.length / 1000);
        }
        System.out.printf("Poly Haven: %d files in %s, %d fetched (%d MB)%n", entries.size(), cache, fetched,
            bytes / 1_000_000);
    }

    private static byte[] download(HttpClient client, PolyHavenManifest.Entry entry)
        throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(entry.url()))
                .header("User-Agent", "true-sim-showcase (github.com/true-sim/samples)").build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = response.body();
            if (response.statusCode() == 200 && sha256(data).equals(entry.sha256())) {
                return data;
            }
            System.err.printf("%s: status %d, %d bytes (pinned %d), attempt %d%n", entry.url(), response.statusCode(),
                data.length, entry.bytes(), attempt);
            if (attempt == 4) {
                throw new IOException(entry.url() + " does not match its pinned SHA-256 " + entry.sha256());
            }
            Thread.sleep(2000L * attempt);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
