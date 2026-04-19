package net.runelite.client.plugins.microbot.agentserver.scripting;

import lombok.extern.slf4j.Slf4j;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles Java source files in-process using {@link javax.tools.JavaCompiler}.
 * Output is a directory of {@code .class} files that can be loaded directly via URLClassLoader.
 */
@Slf4j
public class DynamicScriptCompiler {

    private DynamicScriptCompiler() {
    }

    public static class CompilationResult {
        private final boolean success;
        private final Path classOutputDir;
        private final List<String> errors;

        private CompilationResult(boolean success, Path classOutputDir, List<String> errors) {
            this.success = success;
            this.classOutputDir = classOutputDir;
            this.errors = errors;
        }

        public boolean isSuccess() { return success; }
        public Path getClassOutputDir() { return classOutputDir; }
        public List<String> getErrors() { return errors; }

        static CompilationResult success(Path classOutputDir) {
            return new CompilationResult(true, classOutputDir, List.of());
        }

        static CompilationResult failure(List<String> errors) {
            return new CompilationResult(false, null, errors);
        }
    }

    /**
     * Compiles all {@code .java} files under {@code sourceDir}.
     * Compiled classes are written to {@code outputDir}.
     */
    public static CompilationResult compile(Path sourceDir, Path outputDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return CompilationResult.failure(List.of(
                "No Java compiler available. Ensure the client is running on a JDK (not JRE)."
            ));
        }

        List<Path> sourceFiles;
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            sourceFiles = walk
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());
        }

        if (sourceFiles.isEmpty()) {
            return CompilationResult.failure(List.of("No .java files found in " + sourceDir));
        }

        // Clean and recreate output directory
        cleanDirectory(outputDir);
        Files.createDirectories(outputDir);

        String classpath = System.getProperty("java.class.path", "");

        List<String> options = List.of(
            "-classpath", classpath,
            "-d", outputDir.toString(),
            "-source", "11",
            "-target", "11"
        );

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourceFiles);
            boolean compiled = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();

            if (!compiled) {
                List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> formatDiagnostic(d, sourceDir))
                    .collect(Collectors.toList());
                cleanDirectory(outputDir);
                return CompilationResult.failure(errors);
            }
        }

        log.info("Compiled {} source file(s) → {}", sourceFiles.size(), outputDir);
        return CompilationResult.success(outputDir);
    }

    static void cleanDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                        Files.delete(d);
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Failed to clean directory {}: {}", dir, e.getMessage());
        }
    }

    private static <T> String formatDiagnostic(Diagnostic<T> d, Path sourceDir) {
        String source = "";
        if (d.getSource() != null) {
            try {
                Path srcPath = Path.of(d.getSource().toString());
                source = srcPath.startsWith(sourceDir)
                    ? sourceDir.relativize(srcPath).toString()
                    : srcPath.getFileName().toString();
            } catch (Exception e) {
                source = d.getSource().toString();
            }
        }
        if (d.getLineNumber() > 0) {
            return String.format("%s:%d: %s", source, d.getLineNumber(), d.getMessage(null));
        }
        return d.getMessage(null);
    }
}
