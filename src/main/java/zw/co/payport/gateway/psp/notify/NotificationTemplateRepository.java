package zw.co.payport.gateway.psp.notify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {
    Optional<NotificationTemplate> findByCodeAndChannelAndLocaleAndActiveTrue(String code, String channel, String locale);
}
