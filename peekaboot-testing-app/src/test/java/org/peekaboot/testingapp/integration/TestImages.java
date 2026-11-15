package org.peekaboot.testingapp.integration;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toUnmodifiableMap;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/** The container images the ITs start, read from compose.yml so the demo's tags are the only ones. */
final class TestImages {

    private static final Pattern SERVICE_IMAGE = Pattern.compile("services\\.([^.]+)\\.image");

    private static final Map<String, String> COMPOSE_IMAGES = readComposeImages();

    static final String MYSQL = image("mysql");

    static final String REDIS = image("redis");

    static final String HTTPBIN = image("httpbin");

    private TestImages() {}

    /** Service name to image, for every service of compose.yml that names one. */
    static Map<String, String> composeImages() {
        return COMPOSE_IMAGES;
    }

    private static String image(String service) {
        String image = COMPOSE_IMAGES.get(service);
        if (image == null) {
            throw new IllegalStateException("compose.yml names no image for " + service);
        }
        return image;
    }

    private static Map<String, String> readComposeImages() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("compose.yml"));
        Properties compose = requireNonNull(yaml.getObject(), "compose.yml read as nothing");
        return compose.stringPropertyNames().stream()
                .map(SERVICE_IMAGE::matcher)
                .filter(Matcher::matches)
                .collect(toUnmodifiableMap(match -> match.group(1), match -> compose.getProperty(match.group())));
    }
}
