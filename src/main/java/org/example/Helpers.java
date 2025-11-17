package org.example;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.gumtreediff.gen.TreeGenerators;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.TreeContext;
import com.github.gumtreediff.tree.Type;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;
import java.nio.file.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.TimeUnit;


public class Helpers {
    public static Map<Path, String> computeSha1Map(List<Path> paths) {
        Map<Path, String> m = new HashMap<>();
        for (Path p : paths) {
            try {
                m.put(p, sha1(Files.readAllBytes(p)));
            } catch (IOException e) {
                // best-effort; leave absent if unreadable
            }
        }
        return m;
    }

    public static String sha1(byte[] data) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Build a "type key" like "com.acme.foo#Main" using simple parsing.
     * If package/type can’t be extracted, fall back to the filename (without .java).
     */
    public static Map<Path, String> computeTypeKeyMap(List<Path> paths) {
        Map<Path, String> m = new HashMap<>();
        for (Path p : paths) {
            try {
                String content = Files.readString(p);
                String pkg = extractPackage(content);
                String top = extractTopTypeName(content);
                if (top == null) {
                    String name = p.getFileName().toString();
                    top = name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
                }
                String key = (pkg == null || pkg.isBlank() ? "" : pkg) + "#" + top;
                m.put(p, key);
            } catch (IOException e) {
                // ignore; absent key means we won't match by type
            }
        }
        return m;
    }

    public static String extractPackage(String src) {
        var m = java.util.regex.Pattern.compile("\\bpackage\\s+([\\w\\.]+)\\s*;")
                .matcher(src);
        return m.find() ? m.group(1) : null;
    }

    public static String extractTopTypeName(String src) {
        // naive but effective for primary type
        var m = java.util.regex.Pattern.compile(
                "\\b(public\\s+)?(class|interface|enum|record)\\s+([A-Za-z_\\$][A-Za-z0-9_\\$]*)"
        ).matcher(src);
        return m.find() ? m.group(3) : null;
    }

    public static Map<String, List<Path>> indexByFilename(List<Path> paths) {
        Map<String, List<Path>> idx = new HashMap<>();
        for (Path p : paths) {
            idx.computeIfAbsent(p.getFileName().toString(), k -> new ArrayList<>()).add(p);
        }
        return idx;
    }

    public static String relativizeSafe(Path root, Path file) {
        try {
            return root.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }


    /** Public helper: extract a ZIP into destDir (prevents Zip Slip); overwrites existing files. */
    public static void extractZipProject(String zipFilePath, String destDir) throws IOException {
        Path zipPath = Paths.get(zipFilePath).toAbsolutePath().normalize();
        Path targetDir = Paths.get(destDir).toAbsolutePath().normalize();
        if (!Files.exists(zipPath)) throw new IOException("ZIP not found: " + zipPath);
        Files.createDirectories(targetDir);

        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipPath))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = targetDir.resolve(entry.getName()).normalize();
                if (!outPath.startsWith(targetDir))
                    throw new IOException("Blocked Zip Slip entry: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    Files.copy(zis, outPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }



    /**
     * Prevents zip-slip vulnerability by checking canonical paths.
     */
    public static Path safeZipResolve(Path targetDir, java.util.zip.ZipEntry entry) throws IOException {
        Path resolved = targetDir.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(targetDir)) {
            throw new IOException("Entry is outside of target dir: " + entry.getName());
        }
        return resolved;
    }

    /**
     * Detects the language of a source file based on its extension.
     * Returns "java" for .java, "kotlin" for .kt, or "unknown" otherwise.
     */
    public static String detectLanguage(Path file) {
        if (file == null) return "unknown";
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        } else if (name.endsWith(".kt")) {
            return "kotlin";
        } else {
            return "unknown";
        }
    }


    public static Map<String, Path> listCodeFiles(Path root, Set<String> extensions) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        final Set<String> extsLower = extensions.stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        for (String ext : extsLower) {
                            if (name.endsWith(ext)) return true;
                        }
                        return false;
                    })
                    .collect(Collectors.toMap(
                            p -> root.relativize(p).toString().replace('\\', '/'),
                            p -> p,
                            (a, b) -> a,          // keep first on collision
                            TreeMap::new          // sorted by relative path
                    ));
        }
    }



     public static void extractArchive(String archivePath, String destDir) throws IOException {
     Path archive = Paths.get(archivePath).toAbsolutePath().normalize();
     Path targetDir = Paths.get(destDir).toAbsolutePath().normalize();
     Files.createDirectories(targetDir);

     String n = archive.getFileName().toString().toLowerCase(Locale.ROOT);
     if (n.endsWith(".zip")) {
     extractZip(archive, targetDir);
     } else if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
     extractTarGz(archive, targetDir);
     } else if (n.endsWith(".gz")) {
     extractGenericGz(archive, targetDir); // .gz that might be tar/zip or a single file
     } else {
     throw new IOException("Unsupported archive format: " + n);
     }
     }

     // --- ZIP (.zip) ---
     private static void extractZip(Path zip, Path targetDir) throws IOException {
     try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
     ZipEntry entry;
     while ((entry = zis.getNextEntry()) != null) {
     Path outPath = safeResolve(targetDir, entry.getName());
     if (entry.isDirectory()) {
     Files.createDirectories(outPath);
     } else {
     Files.createDirectories(outPath.getParent());
     try (OutputStream out = Files.newOutputStream(outPath)) {
     IOUtils.copy(zis, out);
     }
     }
     zis.closeEntry();
     }
     }
     }

     // --- TAR.GZ (.tar.gz / .tgz) ---
     private static void extractTarGz(Path archive, Path targetDir) throws IOException {
     try (InputStream fi = Files.newInputStream(archive);
     BufferedInputStream bi = new BufferedInputStream(fi);
     GzipCompressorInputStream gzi = new GzipCompressorInputStream(bi);
     TarArchiveInputStream tis = new TarArchiveInputStream(gzi)) {

     ArchiveEntry entry;
     while ((entry = tis.getNextEntry()) != null) {
     Path outPath = safeResolve(targetDir, entry.getName());
     if (entry.isDirectory()) {
     Files.createDirectories(outPath);
     } else {
     Files.createDirectories(outPath.getParent());
     try (OutputStream out = Files.newOutputStream(outPath)) {
     IOUtils.copy(tis, out);
     }
     }
     }
     }
     }

     /**
     * Handle generic .gz:
     * 1) gunzip to a temp file
     * 2) if it's a TAR → extract TAR
     * 3) else if it's a ZIP → extract ZIP
     * 4) else → treat as a single file (write it under targetDir/<originalNameSansGz>)
     */
    public static void extractGenericGz(Path gz, Path targetDir) throws IOException {
        Path tmp = Files.createTempFile("ungz_", ".bin");
        try (InputStream in = Files.newInputStream(gz);
             BufferedInputStream bin = new BufferedInputStream(in);
             GzipCompressorInputStream gzi = new GzipCompressorInputStream(bin);
             OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {
            IOUtils.copy(gzi, out);
        }

        try {
            if (isTarFile(tmp)) {
                extractTarFromFile(tmp, targetDir);
                return;
            }
            if (isZipFile(tmp)) {
                extractZipFromFile(tmp, targetDir);
                return;
            }
            // Single file: move it to targetDir with name sans ".gz"
            String base = stripGzExtension(gz.getFileName().toString());
            Path outFile = targetDir.resolve(base).normalize();
            Files.createDirectories(outFile.getParent());
            Files.move(tmp, outFile, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // if we didn't move it (single-file case handles move), ensure temp is gone
            try { Files.deleteIfExists(tmp); } catch (IOException ignore) {}
        }
    }

    // --- Helpers for reading TAR/ZIP from an already-decompressed file ---
    private static void extractTarFromFile(Path tarFile, Path targetDir) throws IOException {
        try (InputStream fi = Files.newInputStream(tarFile);
             BufferedInputStream bi = new BufferedInputStream(fi);
             TarArchiveInputStream tis = new TarArchiveInputStream(bi)) {

            ArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                Path outPath = safeResolve(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        IOUtils.copy(tis, out);
                    }
                }
            }
        }
    }

    private static void extractZipFromFile(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path outPath = safeResolve(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    try (OutputStream out = Files.newOutputStream(outPath)) {
                        IOUtils.copy(zis, out);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    // --- Type sniffers ---
    private static boolean isZipFile(Path file) throws IOException {
        // ZIP local file header signature: 50 4B 03 04 (PK..)
        byte[] sig = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.read(sig) < 4) return false;
        }
        return sig[0] == 0x50 && sig[1] == 0x4B && sig[2] == 0x03 && sig[3] == 0x04;
    }

    private static boolean isTarFile(Path file) throws IOException {
        // Quick heuristic: try opening as TAR and seeing if we can read at least one entry
        try (InputStream fi = Files.newInputStream(file);
             BufferedInputStream bi = new BufferedInputStream(fi);
             TarArchiveInputStream tis = new TarArchiveInputStream(bi)) {
            return tis.getNextEntry() != null; // if readable and has entries → it's a tar
        } catch (Exception e) {
            return false;
        }
    }

    // --- Path safety ---
    private static Path safeResolve(Path targetDir, String entryName) throws IOException {
        Path out = targetDir.resolve(entryName).normalize();
        if (!out.startsWith(targetDir)) {
            throw new IOException("Blocked Zip Slip entry: " + entryName);
        }
        return out;
    }

    private static String stripGzExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) return name.substring(0, name.length() - ".tar.gz".length());
        if (lower.endsWith(".tgz"))     return name.substring(0, name.length() - ".tgz".length());
        if (lower.endsWith(".gz"))      return name.substring(0, name.length() - ".gz".length());
        return name;
    }


    public static long msSince(long startNano) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNano);
    }

    private static final Set<String> INLINE_REDUNDANT_TYPES = new HashSet<>(Arrays.asList(
            "Modifier",
            "PrimitiveType",
            "SimpleName",
            "SimpleType",
            "TYPE_DECLARATION_KIND",
            "METHOD_INVOCATION_RECEIVER",
            "METHOD_INVOCATION_ARGUMENTS",
            "ASSIGNMENT_OPERATOR"
    ));


    private static Map<String, Object> generateFileTree(String relPath, Path file, String language) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("path", relPath);
        try {
            Tree root;
            TreeContext tc;
            if ("java".equalsIgnoreCase(language)) {
                root = new JdtTreeGenerator().generateFrom().file(file.toString()).getRoot();
                //root = tc.getRoot();

            }  else {
                root = com.github.gumtreediff.gen.TreeGenerators.getInstance()
                        .getTree(file.toString()).getRoot();
            }
            node.put("tree", serializeTreeNoPos(root));  // GumTree's formatted representation
        } catch (Exception ex) {
            node.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return node;
    }



    private static String safeTypeName(Type ty) {
        try {
            // GumTree 3.x Type usually has getName()
            String n = ty.toString();
            if (n != null && !n.isEmpty()) return n;
        } catch (Throwable ignored) {}
        return String.valueOf(ty);
    }

    private static Map<String, Object> simplifiedFileTree(String relPath, Path file, String language) {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("path", relPath);
        try {
            String src = Files.readString(file, StandardCharsets.UTF_8);
            Tree root = parseToGumTree(file, language);
            // assign stable pre-order IDs and extract one full source line per node
            AtomicLong counter = new AtomicLong(0L);
            Map<String, Object> simplified = toSimpleNode(root, src, counter);
            obj.put("tree", simplified);
        } catch (Exception ex) {
            obj.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        return obj;
    }

    /** Parse file with the right frontend. language = "java"|"kotlin"|"auto". */
    private static Tree parseToGumTree(Path file, String language) throws IOException {
        String lang = !"auto".equalsIgnoreCase(language) ? language : detectLanguage(file);
        if ("java".equalsIgnoreCase(lang)) {
            return new JdtTreeGenerator().generateFrom().file(file.toString()).getRoot();
        }  else {
            // fallback to registry (works if other generators are wired)
            return TreeGenerators.getInstance().getTree(file.toString()).getRoot();
        }
    }

    private static Map<String, Object> toSimpleNode(Tree t, String source, java.util.concurrent.atomic.AtomicLong idGen) {
        Map<String, Object> node = new LinkedHashMap<>();
        long id = idGen.incrementAndGet();

        String myType = safeTypeName(t.getType());
        String myLine = firstFullLineForNode(source, t);

        node.put("id", id);
        node.put("type", myType);
        node.put("code", myLine);

        if (!t.getChildren().isEmpty()) {
            List<Map<String, Object>> kids = new ArrayList<>(t.getChildren().size());
            for (Tree c : t.getChildren()) {
                String childType = safeTypeName(c.getType());
                String childLine = firstFullLineForNode(source, c);

                // Skip “inline structural” children that duplicate the parent header line
                if (INLINE_REDUNDANT_TYPES.contains(childType) && childLine.equals(myLine)) {
                    continue;
                }

                kids.add(toSimpleNode(c, source, idGen));
            }
            if (!kids.isEmpty()) node.put("children", kids);
        }
        return node;
    }

    private static String firstFullLineForNode(String src, Tree t) {
        int pos = t.getPos();
        if (pos >= 0 && pos <= src.length()) {
            int lineStart = lastIndexOf(src, '\n', Math.max(0, pos - 1)) + 1;
            int lineEnd = indexOf(src, '\n', pos);
            if (lineEnd < 0) lineEnd = src.length();
            String line = src.substring(lineStart, lineEnd).trim();
            if (!line.isEmpty()) return line;
        }
        if (t.getLabel() != null && !t.getLabel().isEmpty()) return t.getLabel().trim();
        return safeTypeName(t.getType());
    }

    private static int lastIndexOf(String s, char ch, int from) {
        for (int i = from; i >= 0; i--) if (s.charAt(i) == ch) return i;
        return -1;
    }

    private static int indexOf(String s, char ch, int from) {
        for (int i = from; i < s.length(); i++) if (s.charAt(i) == ch) return i;
        return -1;
    }



    @SuppressWarnings("unchecked")
    private static Map<String, Object> serializeTreeNoPos(Tree t) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("type", safeTypeName(t.getType()));
        String label = t.getLabel();
        if (label != null && !label.isEmpty()) node.put("label", label);

        List<Map<String, Object>> children = new ArrayList<>();
        for (Tree c : t.getChildren()) {
            children.add(serializeTreeNoPos(c));
        }
        if (!children.isEmpty()) node.put("children", children);
        return node;
    }

    public static void exportProjectSourceTrees(String oldProjectDir,
                                                String newProjectDir,
                                                String outputJson,
                                                String language) throws IOException {
        Path oldRoot = Paths.get(oldProjectDir).toAbsolutePath().normalize();
        Path newRoot = Paths.get(newProjectDir).toAbsolutePath().normalize();
        Path outFile = Paths.get(outputJson).toAbsolutePath();
        Files.createDirectories(outFile.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("oldProject", oldRoot.toString());
        report.put("newProject", newRoot.toString());
        report.put("generatedAt", new Date().toString());

        // Collect files (.java / .kt)
        Set<String> exts = new HashSet<>(Arrays.asList(".java", ".kt"));
        Map<String, Path> oldFiles = listCodeFiles(oldRoot, exts);
        Map<String, Path> newFiles = listCodeFiles(newRoot, exts);

        List<Map<String, Object>> oldTrees = new ArrayList<>();
        List<Map<String, Object>> newTrees = new ArrayList<>();

        // Extract ASTs for each file in old project
        for (Map.Entry<String, Path> e : oldFiles.entrySet()) {
            oldTrees.add(simplifiedFileTree(e.getKey(), e.getValue(), language));
        }

        // Extract ASTs for each file in new project
        for (Map.Entry<String, Path> e : newFiles.entrySet()) {
            newTrees.add(simplifiedFileTree(e.getKey(), e.getValue(), language));
        }

        report.put("oldProjectTrees", oldTrees);
        report.put("newProjectTrees", newTrees);

        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        byte[] json = gson.toJson(report).getBytes(StandardCharsets.UTF_8);
        Files.write(outFile, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("✅ Source code trees exported to: " + outFile);
    }






}







