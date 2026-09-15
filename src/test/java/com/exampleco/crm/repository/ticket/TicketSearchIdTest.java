package com.exampleco.crm.repository.ticket;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Pins TicketRepositoryImpl#parseTicketId, the rule that lets the ticket list's one free-text
// box double as a ticket-id lookup: search_text never contained the ticket's own id, so the
// query matches t.id separately (see that method's own comment).
class TicketSearchIdTest {

    @Test
    void parsesAnAllDigitSearchAsATicketId() {
        assertThat(TicketRepositoryImpl.parseTicketId("42")).isEqualTo(42L);
    }

    @Test
    void ignoresAnythingThatIsNotPurelyDigits() {
        assertThat(TicketRepositoryImpl.parseTicketId(null)).isNull();
        assertThat(TicketRepositoryImpl.parseTicketId("")).isNull();
        assertThat(TicketRepositoryImpl.parseTicketId("john@example.com")).isNull();
        assertThat(TicketRepositoryImpl.parseTicketId("42a")).isNull();
        assertThat(TicketRepositoryImpl.parseTicketId(" 42")).isNull();
    }

    // TicketController#normalizeSearch strips every '-' before the value reaches the repository,
    // so a hyphenated Amazon order number arrives here as 17 digits and does parse as an id.
    // That's harmless — no ticket carries an id that large, and the search_text LIKE in the same
    // OR still matches the order — but it must not overflow, which is what the length cap below
    // guarantees.
    @Test
    void handlesAHyphenStrippedOrderNumberWithoutOverflowing() {
        assertThat(TicketRepositoryImpl.parseTicketId("11212345671234567")).isEqualTo(11212345671234567L);
        assertThat(TicketRepositoryImpl.parseTicketId("9".repeat(19))).isNull();
    }
}
