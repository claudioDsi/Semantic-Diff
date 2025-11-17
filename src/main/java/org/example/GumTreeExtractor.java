package org.example;


import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.EditScriptGenerator;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.gen.TreeGenerators;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;

import com.github.gumtreediff.matchers.*;
import com.github.gumtreediff.tree.Tree;
import com.github.gumtreediff.tree.TreeContext;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;



import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import static org.example.Helpers.*;
import static org.example.Serializers.toLisp;

/**
 * GumTree utilities for file and project diffs.
 */
public class GumTreeExtractor {



    /**
     * Compare two Java project directories and write a JSON report.
     *
     * @param oldProjectDir path to OLD project (e.g., v0)
     * @param newProjectDir path to NEW project (e.g., v0.1)
     * @param outputJson    path to the result JSON file to create/overwrite
     * @throws IOException if any IO error occurs
     */
    public static void saveProjectDiffToJson(String oldProjectDir,
                                             String newProjectDir,
                                             String outputJson, String  language) throws IOException {

        Path oldRoot = Paths.get(oldProjectDir).toAbsolutePath().normalize();
        Path newRoot = Paths.get(newProjectDir).toAbsolutePath().normalize();
        Path outFile = Paths.get(outputJson).toAbsolutePath();

        // 1) Collect all .java files by relative path for both roots.
        //Map<String, Path> oldFiles = listJavaFiles(oldRoot);
        //Map<String, Path> newFiles = listJavaFiles(newRoot);

        Set<String> exts = new HashSet<>(Arrays.asList(".java", ".kt"));
        Map<String, Path> oldFiles = listCodeFiles(oldRoot, exts);
        Map<String, Path> newFiles = listCodeFiles(newRoot, exts);

        // 2) Build the union of relative paths and classify.
        Set<String> allRelPaths = new TreeSet<>();
        allRelPaths.addAll(oldFiles.keySet());
        allRelPaths.addAll(newFiles.keySet());

        // 3) Prepare JSON-friendly structure.
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("before", oldRoot.toString());
        report.put("after", newRoot.toString());
        report.put("generatedAt", new Date().toString());

        List<Map<String, Object>> files = new ArrayList<>();

        // 4) For each path, compute edit scripts or mark added/removed.




// Track which concrete paths we've already consumed, so we don't double-report
        Set<Path> usedOld = new HashSet<>();
        Set<Path> usedNew = new HashSet<>();

// 1) Process files that exist at the same relative path in both versions
        for (String rel : allRelPaths) {
            Path oldPath = oldFiles.get(rel);
            Path newPath = newFiles.get(rel);
            if (oldPath != null && newPath != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", rel);
                try {
                    long tStart = System.nanoTime();

                    Map<EditScript, MappingStore> diffStorage = computeEditScript(oldPath, newPath, language);

                    //EditScript script = computeEditScript(oldPath, newPath, language);
                    long tookMs = msSince(tStart);
                    entry.put("status", diffStorage.isEmpty() ? "unchanged" : "modified");
                    for (EditScript key : diffStorage.keySet())
                    {
                        entry.put("actions", toActionList(key,diffStorage.get(key))); // may be empty if only renamed
                    }
                    entry.put("diffTimeMs", tookMs);  // <-- store time
                } catch (Exception ex) {
                    entry.put("status", "error");
                    entry.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    entry.put("actions", Collections.emptyList());
                    entry.put("diffTimeMs", null);
                }
                files.add(entry);
                usedOld.add(oldPath);
                usedNew.add(newPath);
            }
        }

// 2) Build pools of unmatched files (candidates for add/delete/rename)
        List<Path> oldOnly = oldFiles.values().stream().filter(p -> !usedOld.contains(p)).toList();
        List<Path> newOnly = newFiles.values().stream().filter(p -> !usedNew.contains(p)).toList();

// Precompute signals for rename detection
        Map<Path, String> oldSha = Helpers.computeSha1Map(oldOnly);
        Map<Path, String> newSha = Helpers.computeSha1Map(newOnly);
        Map<Path, String> oldTypeKey = Helpers.computeTypeKeyMap(oldOnly); // e.g., "pkg.name#TopType"
        Map<Path, String> newTypeKey = Helpers.computeTypeKeyMap(newOnly);
        Map<String, List<Path>> oldByFilename = Helpers.indexByFilename(oldOnly);
        Map<String, List<Path>> newByFilename = Helpers.indexByFilename(newOnly);

// 2a) First, pair identical-content files (strong rename signal)
        Set<Path> pairedOld = new HashSet<>();
        Set<Path> pairedNew = new HashSet<>();
        for (Path n : newOnly) {
            String sha = newSha.get(n);
            if (sha == null) continue;
            Optional<Path> match = oldOnly.stream()
                    .filter(o -> !pairedOld.contains(o))
                    .filter(o -> sha.equals(oldSha.get(o)))
                    .findFirst();
            if (match.isPresent()) {
                Path o = match.get();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("status", "renamed_file");
                entry.put("oldPath", Helpers.relativizeSafe(oldRoot, o));
                entry.put("newPath", Helpers.relativizeSafe(newRoot, n));
                entry.put("actions", Collections.emptyList()); // identical content => no edits
                entry.put("diffTimeMs", 0L);
                files.add(entry);
                pairedOld.add(o);
                pairedNew.add(n);
            }
        }

// 2b) Next, pair by type key (package + top-level type) or fallback to filename if unique
        for (Path n : newOnly) {
            if (pairedNew.contains(n)) continue;

            Path o = null;

            // Try type key
            String tk = newTypeKey.get(n);
            if (tk != null) {
                o = oldOnly.stream()
                        .filter(x -> !pairedOld.contains(x))
                        .filter(x -> tk.equals(oldTypeKey.get(x)))
                        .findFirst()
                        .orElse(null);
            }

            // Fallback: unique filename match
            if (o == null) {
                String fname = n.getFileName().toString();
                List<Path> olds = oldByFilename.getOrDefault(fname, List.of());
                if (olds.size() == 1 && !pairedOld.contains(olds.get(0))) {
                    o = olds.get(0);
                }
            }

            if (o != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("status", "renamed_file");
                entry.put("oldPath", Helpers.relativizeSafe(oldRoot, o));
                entry.put("newPath", Helpers.relativizeSafe(newRoot, n));
                try {
                    Map<EditScript, MappingStore> diffStorage = computeEditScript(o, n, language);
                    for (EditScript key : diffStorage.keySet())
                    {
                        entry.put("actions", toActionList(key,diffStorage.get(key))); // may be empty if only renamed
                    }

                } catch (Exception ex) {
                    entry.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                    entry.put("actions", Collections.emptyList());
                }
                files.add(entry);
                pairedOld.add(o);
                pairedNew.add(n);
            }
        }

// 3) Whatever is still unmatched is added/deleted
        for (Path o : oldOnly) {
            if (pairedOld.contains(o)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", Helpers.relativizeSafe(oldRoot, o));
            entry.put("status", "deleted_file");
            entry.put("actions", Collections.emptyList());
            files.add(entry);
        }

        for (Path n : newOnly) {
            if (pairedNew.contains(n)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", Helpers.relativizeSafe(newRoot, n));
            entry.put("status", "added_file");
            entry.put("actions", Collections.emptyList());
            files.add(entry);
        }

        report.put("files", files);

        // 5) Write pretty JSON.
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        String json = gson.toJson(report);

        Files.createDirectories(outFile.getParent());
        Files.write(outFile, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    // ===== Helpers =====

    private static Map<String, Path> listJavaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                    .collect(Collectors.toMap(
                            p -> root.relativize(p).toString().replace('\\', '/'),
                            p -> p,
                            // If duplicates somehow occur, keep the first
                            (a, b) -> a,
                            TreeMap::new
                    ));
        }
    }

    private static Map<EditScript, MappingStore> computeEditScript(Path oldFile, Path newFile, String language) throws IOException {
        Tree src, dst;
        Map<EditScript, MappingStore> results = new LinkedHashMap<>();
        //System.out.println("Enter");

        if ("java".equalsIgnoreCase(language)) {

            //src = new JavaTreeSitterNgTreeGenerator().generateFrom().file(oldFile.toString()).getRoot();
            //dst = new JavaTreeSitterNgTreeGenerator().generateFrom().file(newFile.toString()).getRoot();
            src = new JdtTreeGenerator().generateFrom().file(oldFile.toString()).getRoot();
            dst = new JdtTreeGenerator().generateFrom().file(newFile.toString()).getRoot();
        } else if ("kt".equalsIgnoreCase(language)) {
            // Requires GumTree's Tree-Sitter NG generator on the classpath (see deps below).
            // TreeGenerators will pick the proper Tree-Sitter parser based on file extension (.kt).
            src = TreeGenerators.getInstance().getTree(oldFile.toString()).getRoot();
            dst = TreeGenerators.getInstance().getTree(newFile.toString()).getRoot();

        } else {
            // Fallback: let GumTree figure it out from file extension (works for other TS-NG languages too)
            src = TreeGenerators.getInstance().getTree(oldFile.toString()).getRoot();
            dst = TreeGenerators.getInstance().getTree(newFile.toString()).getRoot();
        }


        Matcher defaultMatcher = Matchers.getInstance().getMatcher();
        MappingStore mappings = defaultMatcher.match(src, dst);
        EditScriptGenerator gen = new SimplifiedChawatheScriptGenerator();
        EditScript script = gen.computeActions(mappings);
        results.put(script,mappings);
        return results;
    }






    private static List<Map<String, Object>> toActionList(EditScript script, MappingStore map) {
        List<Map<String, Object>> actions = new ArrayList<>();
        script.forEach(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", a.getClass().getSimpleName()); // Insert, Delete, Update, Move, ...
            Tree n = a.getNode();
            m.put("nodeTree", toLisp(n));
            m.put("treeBefore", toLisp(map.src));
            m.put("treeAfter", toLisp(map.dst));


            actions.add(m);
        });
        return actions;
    }

    private static String safeTreeString(Tree t) {
        if (t == null) return null;
        try {
            // GumTree's ad-hoc pretty format (matches the style in your expected output)
            return t.toTreeString();
        } catch (Exception e) {
            return t.getClass().getSimpleName();
        }
    }

    private static String safeTreeContext(TreeContext t) {
        if (t == null) return null;
        try {

            // GumTree's ad-hoc pretty format (matches the style in your expected output)
            return t.toString();
        } catch (Exception e) {
            return t.getClass().getSimpleName();
        }
    }


    public static void compareArchivesInRoot(String rootArchiveFolder,
                                             String extractBaseDir,
                                             String language) throws IOException {
        Path root = Paths.get(rootArchiveFolder).toAbsolutePath().normalize();

        if (!Files.exists(root)) {
            throw new IOException("Root folder does not exist: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Root path is not a directory: " + root);
        }

        // Discover supported archives
        List<Path> archives;
        try (Stream<Path> stream = Files.list(root)) {
            archives = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".zip") || n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".gz");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .collect(Collectors.toList());
        }

        if (archives.isEmpty()) {
            throw new IOException("No archives found in: " + root);
        }
        if (archives.size() < 2) {
            System.out.println("Only one archive found in " + root + " — nothing to compare.");
            return;
        }

        // Ensure extraction base exists
        Files.createDirectories(Paths.get(extractBaseDir));

        // Extract each archive once
        Map<Path, Path> extracted = new LinkedHashMap<>();
        for (Path archive : archives) {
            String baseName = stripArchiveExtension(archive.getFileName().toString());
            Path destDir = Paths.get(extractBaseDir, baseName).toAbsolutePath().normalize();
            System.out.println("Extracting " + archive + " → " + destDir);
            extractArchive(archive.toString(), destDir.toString());
            extracted.put(archive, destDir);
        }

        // Compare adjacent pairs
        String rootName = root.getFileName().toString();
        List<Path> list = new ArrayList<>(extracted.keySet());
        for (int i = 0; i < list.size() - 1; i++) {
            Path a = list.get(i);
            Path b = list.get(i + 1);
            Path aDir = extracted.get(a);
            Path bDir = extracted.get(b);

            String verA = stripArchiveExtension(a.getFileName().toString());
            String verB = stripArchiveExtension(b.getFileName().toString());

            Path out = Paths.get("build", "diff_" + rootName + "__" + verA + "_to_" + verB + ".json")
                    .toAbsolutePath().normalize();

            System.out.println("Comparing: " + verA + " → " + verB);
            // If your saveProjectDiffToJson accepts a language param, pass "auto" (or "java"/"kotlin").
            saveProjectDiffToJson(aDir.toString(), bDir.toString(), out.toString(), language);
            System.out.println("  ✓ Wrote: " + out);
        }
    }

    private static String stripArchiveExtension(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) return name.substring(0, name.length() - ".tar.gz".length());
        if (lower.endsWith(".tgz"))     return name.substring(0, name.length() - ".tgz".length());
        if (lower.endsWith(".zip"))     return name.substring(0, name.length() - ".zip".length());
        if (lower.endsWith(".gz"))      return name.substring(0, name.length() - ".gz".length());
        return name;
    }







    // --- replace toActionList(...) with this version ---



    // Optional small demo runner
   /* public static void main(String[] args) {
        try {
            // Example usage:
            // Compare two project roots and write diff to build/diff.json
            saveProjectDiffToJson("path/to/oldProject", "path/to/newProject", "build/diff.json");
            System.out.println("Project diff JSON written to build/diff.json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }*/
}
