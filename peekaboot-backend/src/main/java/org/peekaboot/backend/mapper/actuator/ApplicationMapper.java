package org.peekaboot.backend.mapper.actuator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.InfoResponse;
import org.peekaboot.backend.actuator.parsed.SpringInfo;
import org.peekaboot.backend.domain.application.ApplicationInfo;
import org.peekaboot.backend.masking.MaskingEngine;
import org.peekaboot.backend.masking.TreeMasker;

public class ApplicationMapper {

    private final TreeMasker treeMasker;

    public ApplicationMapper(MaskingEngine maskingEngine) {
        this.treeMasker = new TreeMasker(maskingEngine);
    }

    public ApplicationInfo map(InfoResponse info, SpringInfo spring, boolean unmask) {
        Map<String, Object> build = Collections.emptyMap();
        Map<String, Object> git = Collections.emptyMap();
        String javaVersion = null;
        String javaVendor = null;

        if (info != null) {
            if (info.build() != null) {
                build = maskBuild(info.build(), unmask);
            }
            if (info.git() != null) {
                git = mapGitInfo(info.git());
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

    /**
     * Unlike git and the JVM/Spring version fields, which are all populated here, {@code
     * info.build} is a free-form map a consuming app supplies itself - not controlled by
     * this mapper at all.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskBuild(Map<String, Object> build, boolean unmask) {
        Object masked = treeMasker.mask(build, unmask);
        return masked instanceof Map ? (Map<String, Object>) masked : Collections.emptyMap();
    }

    private Map<String, Object> mapGitInfo(InfoResponse.GitInfo gitInfo) {
        Map<String, Object> result = new HashMap<>();
        result.put("branch", gitInfo.branch());
        if (gitInfo.commit() != null) {
            Map<String, Object> commit = new HashMap<>();
            commit.put("id", gitInfo.commit().id());
            commit.put("time", gitInfo.commit().time());
            result.put("commit", commit);
        }
        return result;
    }
}
