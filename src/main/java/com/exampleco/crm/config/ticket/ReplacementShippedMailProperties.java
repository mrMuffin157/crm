package com.exampleco.crm.config.ticket;

import com.exampleco.core.order.SalesChannel;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds app.replacement-shipped-mail.* — the buyer-facing "replacement shipped" mail
// ReplacementShippedMailService publishes, replacing legacy-app's non-existent
// ticketClosed endpoint. Deliberately its own namespace, not under app.mail (the internal
// ProcessFailed digest's own prefix — FailedTicketNotificationMailService), since the two mails
// have unrelated audiences and safety requirements.
//
// enabled is the fail-closed toggle: false in application.properties, true only in
// application-prod.properties — mirrors app.keycloak.enabled's existing precedent. recipientOverride,
// when non-blank, replaces both the buyer's own email and notifyBcc on every mail regardless of
// profile — the only supported way to exercise this outside prod (see
// ReplacementShippedMailService's own gate, which additionally refuses outright on a non-prod
// profile with no override set).
@ConfigurationProperties(prefix = "app.replacement-shipped-mail")
public record ReplacementShippedMailProperties(boolean enabled, String recipientOverride, String notifyBcc,
                                                Channels channels) {

    // One field per "az-*" SalesChannel (exampleco-core), mirroring LegacyApiProperties.Hosts —
    // a channel with no mail config bound (brandeCa/brandeAu/mx/decoCa: az-decoca has no
    // email_template row in legacy-app at all) resolves to a null Channels field via plain
    // constructor-binding, exactly like an unmapped legacy.api.hosts.* entry does.
    public record Channels(ChannelMail us, ChannelMail deco, ChannelMail ca, ChannelMail au,
                            ChannelMail brandeCa, ChannelMail brandeAu, ChannelMail mx, ChannelMail decoCa) {

        // Single source of truth for "which per-channel mail config does this ticket's own
        // sourceSystem use" — same switch-over-SalesChannel shape as
        // LegacyApiProperties.Hosts#hostForSourceSystem. Null for a sourceSystem that isn't a
        // real Amazon channel code (e.g. "crm") or a channel with no mail config bound.
        public ChannelMail forSourceSystem(String sourceSystem) {
            SalesChannel channel = sourceSystem == null ? null : SalesChannel.findByChannelCode(sourceSystem);
            if (channel == null) {
                return null;
            }
            return switch (channel) {
                case AmazonBrandD -> us();
                case AmazonDeco -> deco();
                case AmazonCA -> ca();
                case AmazonAU -> au();
                case AmazonCT -> brandeCa();
                case AmazonAT -> brandeAu();
                case AmazonMX -> mx();
                case AmazonDecoCA -> decoCa();
                default -> null;
            };
        }
    }

    public record ChannelMail(String fromAddress, String subject, String signOff) {
    }
}
