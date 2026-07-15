package com.richard.fyoung.customeradmin.aiconfig.skill.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 本地 workspace 存储：把 SKILL.md 写到 {base-dir}/{skillCode}/SKILL.md。
 *
 * <p>默认且始终启用的目标，无外部依赖；remove 递归删除该 skill 目录。</p>
 * @author owlzhangfq@gmail.com
 */
public class LocalWorkspaceSkillPublisher implements SkillContentPublisher {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkspaceSkillPublisher.class);

    private static final String SKILL_FILE_NAME = "SKILL.md";

    private final Path baseDir;

    public LocalWorkspaceSkillPublisher(String baseDir) {
        this.baseDir = Path.of(baseDir);
    }

    @Override
    public SkillStorageTarget target() {
        return SkillStorageTarget.LOCAL;
    }

    @Override
    public void publish(String skillCode, String content) {
        Path skillFile = baseDir.resolve(skillCode).resolve(SKILL_FILE_NAME);
        try {
            Files.createDirectories(skillFile.getParent());
            Files.write(skillFile, content.getBytes(StandardCharsets.UTF_8));
            log.info("local skill published, skillCode={}, path={}", skillCode, skillFile);
        } catch (IOException e) {
            throw new UncheckedIOException("write local SKILL.md failed: " + skillFile, e);
        }
    }

    @Override
    public void remove(String skillCode) {
        Path skillDir = baseDir.resolve(skillCode);
        if (!Files.exists(skillDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(skillDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("delete failed: " + p, e);
                }
            });
            log.info("local skill removed, skillCode={}, dir={}", skillCode, skillDir);
        } catch (IOException e) {
            throw new UncheckedIOException("remove local skill dir failed: " + skillDir, e);
        }
    }
}
