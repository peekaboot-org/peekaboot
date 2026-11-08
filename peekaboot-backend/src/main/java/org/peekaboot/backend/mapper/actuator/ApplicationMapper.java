package org.peekaboot.backend.mapper.actuator;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.domain.application.GitInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;
import org.springframework.boot.info.GitProperties;

public class ApplicationMapper {

    private final TreeMasker treeMasker;

    public ApplicationMapper(MaskingEngine maskingEngine) {
        this.treeMasker = new TreeMasker(maskingEngine);
    }

    /** {@code git} is the application's own git.properties, since Boot's {@code info.git} can show a library's. */
    public ApplicationInfo map(InfoResponse info, @Nullable GitProperties git, SpringInfo spring, boolean unmask) {
        Map<String, Object> build = Collections.emptyMap();
        String javaVersion = null;
        String javaVendor = null;

        if (info != null) {
            // free-form: a consuming app supplies info.build itself, so it is masked as a tree
            build = treeMasker.maskMap(info.build(), unmask);
            if (info.java() != null) {
                javaVersion = info.java().version();
                if (info.java().vendor() != null) {
                    javaVendor = info.java().vendor().name();
                }
            }
        }

        String bootVersion = spring != null ? spring.bootVersion() : null;
        String frameworkVersion = spring != null ? spring.frameworkVersion() : null;

        return new ApplicationInfo(build, gitInfo(git), bootVersion, frameworkVersion, javaVersion, javaVendor);
    }

    /** Null where the file names neither branch nor commit, so the Overview says it has no git info. */
    private static @Nullable GitInfo gitInfo(@Nullable GitProperties git) {
        if (git == null) {
            return null;
        }
        String shortId = git.getShortCommitId();
        String id = shortId != null ? shortId : git.get("commit.id.full");
        Instant time = git.getCommitTime();
        GitInfo.Commit commit = id == null && time == null ? null : new GitInfo.Commit(id, time);
        return git.getBranch() == null && commit == null ? null : new GitInfo(git.getBranch(), commit);
    }
}
