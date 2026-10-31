package zw.co.payport.commerce;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Payport Commerce: payment links, product pages and mini-stores for Payport merchants.
 *
 * It is its own service with its own database (owner, 2026-10-31: Commerce must stay available when Payport Payments
 * is down). Store pages, products and orders are served from Commerce's own data; only taking a payment and reading
 * its outcome need the PSP, through the PSP's Commerce API (Psp). If the PSP cannot be reached the customer's order
 * is kept and they can pay a moment later; payment results arrive from the PSP's event stream (EventReader) and are
 * checked again by reading status (Payments.reconcile), so nothing is lost while either side is down.
 */
@SpringBootApplication
@EnableScheduling
public class CommerceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommerceApplication.class, args);
    }
}
