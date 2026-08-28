package com.exampleco.crm.config.salesorder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// hostForSourceSystem() is the single source of truth LegacyLineItemResolutionClient relies on to
// pick the one host that matches a ticket's own sourceSystem — worth its own pure-function test
// since that caller is otherwise not easy to unit test without a live legacy-app host or a full
// Spring context.
class LegacyApiPropertiesHostsTest {

    private final LegacyApiProperties.Hosts hosts =
            new LegacyApiProperties.Hosts("https://us.example", "https://deco.example", "https://ca.example", "https://au.example",
                    null, null, null, null);

    @Test
    void mapsEachConfiguredChannelCodeToItsOwnHost() {
        assertEquals("https://us.example", hosts.hostForSourceSystem("az-us"));
        assertEquals("https://deco.example", hosts.hostForSourceSystem("az-deco"));
        assertEquals("https://ca.example", hosts.hostForSourceSystem("az-ca"));
        assertEquals("https://au.example", hosts.hostForSourceSystem("az-au"));
    }

    @Test
    void returnsNullForAChannelWithNoHostConfiguredYet() {
        assertNull(hosts.hostForSourceSystem("az-brande-ca"));
        assertNull(hosts.hostForSourceSystem("az-brande-au"));
        assertNull(hosts.hostForSourceSystem("az-mx"));
        assertNull(hosts.hostForSourceSystem("az-decoca"));
    }

    @Test
    void returnsNullForTheCrmPlaceholderSourceSystem() {
        assertNull(hosts.hostForSourceSystem("crm"));
    }

    @Test
    void returnsNullForNullSourceSystem() {
        assertNull(hosts.hostForSourceSystem(null));
    }

    @Test
    void returnsNullForAnUnrecognizedSourceSystem() {
        assertNull(hosts.hostForSourceSystem("not-a-real-channel"));
    }
}
