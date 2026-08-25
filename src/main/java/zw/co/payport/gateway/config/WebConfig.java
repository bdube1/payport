package zw.co.payport.gateway.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private RequestLoggingInterceptor requestLoggingInterceptor;

    @Value("${payport.upload.logo-directory:/var/payport/uploads/logos}")
    private String logoUploadDirectory;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requestLoggingInterceptor).addPathPatterns("/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Ensure the path ends with a slash
        String directory = logoUploadDirectory;
        if (!directory.endsWith("/") && !directory.endsWith("\\")) {
            directory = directory + "/";
        }

        // Convert backslashes to forward slashes for file URLs
        directory = directory.replace("\\", "/");

        // Create directory if it doesn't exist
        File dirFile = new File(logoUploadDirectory);
        if (!dirFile.exists()) {
            boolean created = dirFile.mkdirs();
            if (created) {
                System.out.println("[WebConfig] Created logo directory: " + logoUploadDirectory);
            }
        }

        registry.addResourceHandler("/uploads/logos/**")
                .addResourceLocations("file:" + directory)
                .setCachePeriod(3600);
    }
}
