package zw.co.payport.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // This bean bypasses Spring Boot's Jackson auto-configuration
        // entirely, which means every spring.jackson.* property in
        // application.properties was silently being ignored here --
        // Jackson's actual default is WRITE_DATES_AS_TIMESTAMPS=true,
        // which serializes LocalDateTime as a numeric array
        // ([2026,8,18,13,19,11,...]), not an ISO string. That's exactly
        // what was breaking every date field across every frontend that
        // tried `new Date(value)` on it. Set explicitly here so this
        // bean actually matches what the properties file always intended.
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        mapper.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return mapper;
    }

    /**
     * Async thread pool for fulfilment jobs and SMS sending.
     * Separate from the main web thread pool to prevent blocking HTTP requests.
     */
    @Bean(name = "fulfilmentExecutor")
    public Executor fulfilmentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("fulfilment-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "smsExecutor")
    public Executor smsExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("sms-");
        executor.initialize();
        return executor;
    }

    /**
     * CORS is configured in SecurityConfig now, not here -- Spring
     * Security's filter chain runs before Spring MVC ever sees a request,
     * so a CORS policy defined only at this level was silently never being
     * applied to preflight (OPTIONS) requests. See
     * SecurityConfig.corsConfigurationSource() for the actual policy.
     */
}
