package zw.co.payport.gateway.psp.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import zw.co.payport.gateway.psp.common.Refs;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Templated notifications with at-least-once delivery. Each message is
 * stored first (deduplicated by a business key, so a replayed event never
 * texts a customer twice), then sent; failures retry with back-off.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");
    private static final int MAX_ATTEMPTS = 6;

    private final NotificationRepository notifications;
    private final NotificationTemplateRepository templates;
    private final List<IntegrationSpis.MessageChannel> channels;
    private final TransactionTemplate newTx;

    public NotificationService(NotificationRepository notifications, NotificationTemplateRepository templates,
                               List<IntegrationSpis.MessageChannel> channels, PlatformTransactionManager txManager) {
        this.notifications = notifications;
        this.templates = templates;
        this.channels = channels;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queues a message. Returns false when there is no active template (the event is simply not notified on that channel). */
    public boolean queue(String templateCode, String channel, String recipient, Map<String, ?> vars, String dedupeKey) {
        if (recipient == null || recipient.isBlank()) return false;
        Optional<NotificationTemplate> t = templates.findByCodeAndChannelAndLocaleAndActiveTrue(templateCode, channel, "en");
        if (t.isEmpty()) return false;
        String key = (dedupeKey + ":" + channel + ":" + recipient);
        if (key.length() > 160) key = key.substring(0, 160);
        final String k = key;
        try {
            newTx.executeWithoutResult(s -> {
                Notification n = new Notification();
                n.setNotificationRef(Refs.newRef("ntf"));
                n.setDedupeKey(k);
                n.setChannel(channel);
                n.setRecipient(recipient);
                n.setTemplateCode(templateCode);
                n.setSubject(t.get().getSubject() == null ? null : render(t.get().getSubject(), vars));
                n.setBody(render(t.get().getBody(), vars));
                n.setStatus("QUEUED");
                n.setAttempts(0);
                n.setNextAttemptAt(LocalDateTime.now());
                notifications.saveAndFlush(n);
            });
        } catch (DataIntegrityViolationException duplicate) {
            return true;
        }
        return true;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 20_000)
    public void deliver() {
        for (Notification n : notifications.findByStatusInAndNextAttemptAtBeforeOrderByIdAsc(List.of("QUEUED", "RETRY"),
                LocalDateTime.now(), PageRequest.of(0, 100))) {
            String providerRef = null;
            String error = null;
            try {
                IntegrationSpis.MessageChannel ch = channels.stream().filter(c -> c.channel().equals(n.getChannel())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No channel " + n.getChannel()));
                providerRef = ch.send(n.getRecipient(), "EMAIL".equals(n.getChannel()) ? n.getSubject() : n.getTemplateCode(), n.getBody());
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String pr = providerRef, err = error;
            newTx.executeWithoutResult(s -> {
                Notification fresh = notifications.findById(n.getId()).orElseThrow();
                fresh.setAttempts(fresh.getAttempts() + 1);
                if (err == null) {
                    fresh.setStatus("SENT");
                    fresh.setSentAt(LocalDateTime.now());
                    fresh.setProviderRef(pr);
                } else {
                    fresh.setLastError(err.length() > 1000 ? err.substring(0, 1000) : err);
                    if (fresh.getAttempts() >= MAX_ATTEMPTS) {
                        fresh.setStatus("FAILED");
                        log.warn("[NOTIFY] {} to {} failed permanently: {}", fresh.getTemplateCode(), mask(fresh.getRecipient()), err);
                    } else {
                        fresh.setStatus("RETRY");
                        fresh.setNextAttemptAt(LocalDateTime.now().plusSeconds(30L << Math.min(fresh.getAttempts(), 8)));
                    }
                }
                notifications.save(fresh);
            });
        }
    }

    static String render(String template, Map<String, ?> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars == null ? null : vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : String.valueOf(v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String money(long minor, String currency) {
        return currency + " " + (minor < 0 ? "-" : "") + Math.abs(minor) / 100 + "." + String.format("%02d", Math.abs(minor) % 100);
    }

    static String mask(String r) {
        return r == null || r.length() < 6 ? "***" : r.substring(0, 4) + "***" + r.substring(r.length() - 2);
    }
}
