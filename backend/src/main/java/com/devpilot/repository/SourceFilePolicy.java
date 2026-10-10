package com.devpilot.repository;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SourceFilePolicy {
    private static final Set<String> EXTENSIONS = Set.of("java", "kt", "js", "jsx", "ts", "tsx", "py", "go", "sql", "xml", "json", "yml", "yaml", "properties", "md", "gradle");
    private static final Set<String> EXCLUDED = Set.of(".git", "node_modules", "target", "build", "dist", "out", "vendor", "coverage", ".idea", ".next", "generated", "generated-sources", ".gradle", "__pycache__", ".venv", "venv");
    private static final Set<String> LOCKFILES = Set.of("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml");
    private final long maxFileBytes;

    public SourceFilePolicy(@Value("${app.source-acquisition.max-file-bytes:524288}") long maxFileBytes) {
        if (maxFileBytes < 1 || maxFileBytes > 1_048_576) throw new IllegalArgumentException("Source file limit must be between 1 and 1048576 bytes");
        this.maxFileBytes = maxFileBytes;
    }

    /** Git uses slash separators. Reject backslashes rather than aliasing distinct Git filenames. */
    public String normalize(String path) {
        if (path == null || path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IllegalArgumentException("Invalid repository path");
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.equals("..") || part.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid repository path");
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("Empty repository path");
        return String.join("/", parts);
    }

    public boolean directoryAllowed(String path) {
        return Arrays.stream(path.toLowerCase(Locale.ROOT).split("/")).noneMatch(EXCLUDED::contains);
    }

    public boolean eligible(String path, long size) {
        if (!sizeAllowed(size) || !directoryAllowed(path)) return false;
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (LOCKFILES.contains(name) || name.endsWith(".min.js") || name.endsWith(".min.css")) return false;
        int dot = name.lastIndexOf('.');
        return name.equals("dockerfile") || (dot > 0 && EXTENSIONS.contains(name.substring(dot + 1)));
    }

    public boolean sizeAllowed(long size) { return size >= 0 && size <= maxFileBytes; }

    public Optional<String> decode(byte[] bytes) {
        if (!sizeAllowed(bytes.length)) return Optional.empty();
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' && c != '\f')) return Optional.empty();
            if (text.startsWith("version https://git-lfs.github.com/spec/v1\n") || text.startsWith("version https://git-lfs.github.com/spec/v1\r\n")) return Optional.empty();
            return Optional.of(text);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
