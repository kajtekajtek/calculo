package dev.calculo;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled frontend from classpath:/static. Browser routes of the single-page app (for example a
 * reloaded /positions/AAA.DE) get index.html; real files, /api and /actuator never do.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new SpaResolver());
    }

    static final class SpaResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = resourcePath.isEmpty() ? null : super.getResource(resourcePath, location);
            if (file != null) return file;
            if (!isClientRoute(resourcePath)) return null;
            Resource index = location.createRelative("index.html");
            return index.exists() && index.isReadable() ? index : null;
        }

        static boolean isClientRoute(String path) {
            if (path.equals("api") || path.startsWith("api/") || path.equals("actuator") || path.startsWith("actuator/")) {
                return false;
            }
            // Position keys are tickers such as AAA.DE, so that route is allowed to contain a dot.
            if (path.startsWith("positions/")) return true;
            String last = path.substring(path.lastIndexOf('/') + 1);
            return !last.contains(".");
        }
    }
}
