package org.peekaboot.backend.mapper.actuator;

import java.util.Collections;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.domain.application.GitInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;

public class ApplicationMapper {

    private final TreeMasker treeMasker;

    public ApplicationMapper(MaskingEngine maskingEngine) {
        this.treeMasker = new TreeMasker(maskingEngine);
    }

    public ApplicationInfo map(InfoResponse info, SpringInfo spring, boolean unmask) {
        Map<String, Object> build = Collections.emptyMap();
        GitInfo git = null;
        String javaVersion = null;
        String javaVendor = null;

        if (info != null) {
            // free-form: a consuming app supplies info.build itself, so it is masked as a tree
            build = treeMasker.maskMap(info.build(), unmask);
            if (info.git() != null) {
                git = gitInfo(info.git());
            }
            if (info.java() != null) {
                javaVersion = info.java().version();
                if (info.java().vendor() != null) {
                    javaVendor = info.java().vendor().name();
                }
            }
        }

        String bootVersion = spring != null ? spring.bootVersion() : null;
        String frameworkVersion = spring != null ? spring.frameworkVersion() : null;

        return new ApplicationInfo(build, git, bootVersion, frameworkVersion, javaVersion, javaVendor);
    }

    private static GitInfo gitInfo(InfoResponse.GitInfo git) {
        InfoResponse.GitInfo.CommitInfo commit = git.commit();
        return new GitInfo(git.branch(), commit == null ? null : new GitInfo.Commit(commit.id(), commit.time()));
    }
}
