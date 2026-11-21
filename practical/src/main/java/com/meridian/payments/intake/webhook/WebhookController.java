package com.meridian.payments.intake.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.meridian.payments.intake.provider.Provider;

/**
 * Webhook endpoints (contract: docs/api/webhooks.openapi.yaml). The body is received as raw
 * bytes, so the signature is verified over exactly what the provider sent. Every response has
 * an empty body.
 */
@RestController
public class WebhookController {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final IntakeService intake;

    public WebhookController(IntakeService intake) {
        this.intake = intake;
    }

    @PostMapping("/webhooks/{provider}")
    public ResponseEntity<Void> receive(@PathVariable("provider") String providerSegment,
                                        @RequestHeader HttpHeaders headers,
                                        @RequestBody(required = false) byte[] rawBody) {
        return Provider.fromPathSegment(providerSegment)
                .map(provider -> ResponseEntity.status(intake.handle(provider, headers,
                        rawBody == null ? new byte[0] : rawBody, headers.getFirst(CORRELATION_ID_HEADER))).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Any unexpected error means the notification may not be stored: answer 503 so the provider
     * retries (NFR3). Only the exception type is logged, because its message could contain data.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> unexpected(Exception e) {
        log.error("Unexpected error while receiving a notification: {}", e.getClass().getName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
