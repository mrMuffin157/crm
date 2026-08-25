package com.exampleco.crm.config.salesorder;

import com.exampleco.core.order.SalesChannel;
import org.springframework.boot.context.properties.ConfigurationProperties;

// Binds legacy.api.* — the legacy-app storefront hosts to fan a sales-order lookup out to, plus the
// refresh token used to mint a Bearer access token against them. See LegacyApiClient. Only
// us/deco/ca/au are actually configured today (the 4 storefronts CRM's ticket data covers); the
// other Amazon channel hosts are wired below with no default so they stay null until this app
// actually talks to those storefronts.
@ConfigurationProperties(prefix = "legacy.api")
public record LegacyApiProperties(String refreshToken, Hosts hosts) {

    // One field per "az-*" SalesChannel (exampleco-core), so a channel with no host configured
    // yet still has a slot ready — see hostForSourceSystem below.
    public record Hosts(String us, String deco, String ca, String au,
                         String brandeCa, String brandeAu, String mx, String decoCa) {

        // Single source of truth for "which legacy-app storefront host does this ticket's own
        // sourceSystem belong to" — shared by every per-channel (not fan-out-to-all) outbound
        // call, e.g. LegacyLineItemResolutionClient's resolveLineItems call. (The
        // buyer-facing replacement-shipped mail, no longer calls a legacy-app host at all —
        // ReplacementShippedMailProperties.Channels has its own, unrelated per-channel switch.)
        // Switches over the SalesChannel enum (not the raw channel-code string) so every "az-*"
        // channel exampleco-core knows about has a case here; one with no host configured yet
        // (e.g. brandeCa/brandeAu/mx/decoCa above) resolves to null exactly like an unmapped channel
        // would, until its legacy.api.hosts.* property is actually set. Null for a sourceSystem that isn't a real
        // Amazon channel code at all (e.g. "crm", the brand-new-ticket placeholder — see
        // TicketController.SOURCE_SYSTEM), since there is no legacy-app host to call for those.
        public String hostForSourceSystem(String sourceSystem) {
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
}
