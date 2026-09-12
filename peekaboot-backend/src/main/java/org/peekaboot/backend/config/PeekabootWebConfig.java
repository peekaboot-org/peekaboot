package org.peekaboot.backend.config;

import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

public class PeekabootWebConfig implements WebMvcConfigurer {

    /**
     * The bundled webfont, matched ahead of the rest of the UI by being the more specific
     * pattern. The wildcards spell out a hyphen and a dotted version ahead of {@code .woff2}:
     * a year of immutable caching is only honest for a URL whose bytes cannot change, and what
     * guarantees that here is the upstream version in the file name. An unversioned face
     * dropped in beside them ({@code Geist-Italic.woff2}, say) does not match and revalidates
     * like the rest of the UI, as do the VERSION and licence files.
     *
     * <p>The file name has to come from a wildcard rather than be spelled out. Spring resolves
     * a resource against the part of the path its pattern matched with a wildcard, so naming
     * the files exactly leaves nothing to resolve and serves a 404.
     */
    private static final String FONT_PATTERN = PeekabootPaths.BASE_PATH + "/ui/vendor/geist/*-*.*.*.woff2";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // The dev toolbar rides on every page of the host application, so a font that
        // revalidated would cost a conditional request per page load. An upgrade changes the
        // URL instead of the bytes behind it, which is what the year below rests on.
        registry.addResourceHandler(FONT_PATTERN)
                .addResourceLocations("classpath:" + PeekabootPaths.CLASSPATH_ROOT + "/ui/vendor/geist/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());
        registry.addResourceHandler(PeekabootPaths.BASE_PATH + "/ui/**")
                .addResourceLocations("classpath:" + PeekabootPaths.CLASSPATH_ROOT + "/ui/")
                .setCacheControl(CacheControl.noCache());
    }

    /**
     * Registered as a custom converter, which the builder places ahead of every converter
     * an application configures, so a Peekaboot type never reaches the application's own
     * JSON converter.
     */
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new PeekabootJsonMessageConverter());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        String dashboard = PeekabootPaths.BASE_PATH + "/ui/dashboard/index.html";
        registry.addRedirectViewController(PeekabootPaths.BASE_PATH, dashboard);
        registry.addRedirectViewController(PeekabootPaths.BASE_PATH + "/", dashboard);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ApiSecurityHeadersInterceptor())
                .addPathPatterns(PeekabootPaths.BASE_PATH + "/api/**");
    }
}
