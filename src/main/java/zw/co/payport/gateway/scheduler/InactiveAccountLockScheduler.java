package zw.co.payport.gateway.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.entity.User;
import zw.co.payport.gateway.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Locks staff (OPS/ADMIN/FINANCE) accounts that haven't logged in for 30+
 * continuous days. Same permanent lock as the 3-failed-attempts case --
 * only an ADMIN can clear it, via /api/v1/admin/users/{id}/unlock.
 *
 * Deliberately staff-only: customers who don't transact for a month are
 * completely normal and shouldn't be locked out of buying airtime.
 *
 * A staff account that has never logged in at all (lastLoginAt is null) is
 * measured from its registration date instead -- otherwise a newly created
 * account no one has used yet would never qualify for this check.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InactiveAccountLockScheduler {

    private final UserRepository userRepository;

    private static final int INACTIVITY_DAYS = 30;

    @Scheduled(cron = "0 0 3 * * *") // once a day, 3am
    public void lockInactiveStaffAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(INACTIVITY_DAYS);
        List<User> staff = userRepository.findAll().stream()
                .filter(u -> !"CUSTOMER".equals(u.getRole()))
                .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                .filter(u -> !Boolean.TRUE.equals(u.getAccountLocked()))
                .toList();

        int locked = 0;
        for (User u : staff) {
            LocalDateTime lastActivity = u.getLastLoginAt() != null ? u.getLastLoginAt() : u.getRegisteredAt();
            if (lastActivity != null && lastActivity.isBefore(cutoff)) {
                u.setAccountLocked(true);
                userRepository.save(u);
                locked++;
                log.warn("[AUTO_LOCK] Staff account locked after {}+ days inactive: {}",
                        INACTIVITY_DAYS, u.getEmail() != null ? u.getEmail() : u.getPhone());
            }
        }
        if (locked > 0) {
            log.info("[AUTO_LOCK] Locked {} inactive staff account(s)", locked);
        }
    }
}
