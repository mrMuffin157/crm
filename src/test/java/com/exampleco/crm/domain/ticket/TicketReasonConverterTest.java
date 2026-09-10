package com.exampleco.crm.domain.ticket;

import com.exampleco.core.dto.ticket.TicketReason;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * One migrated row (crm_dev.ticket id 230) carries reason = 0, an id TicketReason has no
 * constant for. Because a throwing AttributeConverter fails the entire result set rather than
 * the single row, that one value made
 * GET /api/tickets/search?sourceSystem=store1_dev answer 400 and return none of its ~6000
 * tickets — including that row itself, which is Closed/Replacement with an order number and no
 * tracking number, exactly what legacy-app's getClosedReplacementTicketsMissingTracking selects.
 *
 * <p>The column has since moved from that numeric id to TicketReason's name() string (doc/0.7.0),
 * leaving that one row's value as the literal string "0" — still unmappable, for the same reason.
 */
class TicketReasonConverterTest {

    private final TicketReasonConverter converter = new TicketReasonConverter();

    @Test
    void readsAnUnmappableValueAsNullInsteadOfFailingTheWholeQuery() {
        assertThatCode(() -> converter.convertToEntityAttribute("0")).doesNotThrowAnyException();
        assertThat(converter.convertToEntityAttribute("0")).isNull();
    }

    @Test
    void roundTripsThroughTheDatabaseColumnValueAsAName() {
        for (TicketReason reason : TicketReason.values()) {
            String column = converter.convertToDatabaseColumn(reason);
            assertThat(column).isEqualTo(reason.name());
            assertThat(converter.convertToEntityAttribute(column)).isEqualTo(reason);
        }
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }

    /**
     * The other three columns are clean today, so their converters stay strict — an unmappable
     * value there would be a real semantic unknown, not a field worth reading as null.
     */
    @Test
    void siblingConvertersRemainStrict() {
        assertThatCode(() -> new TicketStatusConverter().convertToEntityAttribute("NotARealStatus"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new TicketActionConverter().convertToEntityAttribute("NotARealAction"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new RefundTypeConverter().convertToEntityAttribute("NotARealRefundType"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
