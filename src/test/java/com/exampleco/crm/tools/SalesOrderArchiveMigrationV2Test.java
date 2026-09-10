package com.exampleco.crm.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Same failure shape as TicketReasonConverter's {@code reason = 0} fix: a decode
 * method that throws on an unrecognized legacy id would fail the whole chunk it's called from,
 * not just the one order. decodeOrWarn is the guard that keeps one retired status/salesChannel/
 * fulfillmentType/eventType code from aborting the entire migration run.
 */
class SalesOrderArchiveMigrationV2Test {

    @Test
    void decodeOrWarnReturnsTheDecodedValueOnSuccess() {
        String result = SalesOrderArchiveMigrationV2.decodeOrWarn("status", 1L, 5, id -> "Shipped");
        assertThat(result).isEqualTo("Shipped");
    }

    @Test
    void decodeOrWarnDegradesAnUnmappableIdToNullInsteadOfThrowing() {
        assertThatCode(() -> SalesOrderArchiveMigrationV2.decodeOrWarn("status", 1L, 42, id -> {
            throw new IllegalStateException("Unknown OrderStatus id: " + id);
        })).doesNotThrowAnyException();

        String result = SalesOrderArchiveMigrationV2.decodeOrWarn("status", 1L, 42, id -> {
            throw new IllegalStateException("Unknown OrderStatus id: " + id);
        });
        assertThat(result).isNull();
    }

    @Test
    void decodeOrWarnDoesNotSwallowAnUnrelatedRuntimeException() {
        assertThatCode(() -> SalesOrderArchiveMigrationV2.decodeOrWarn("status", 1L, 42, id -> {
            throw new NullPointerException("not an unmappable-id failure");
        })).isInstanceOf(NullPointerException.class);
    }

    @Test
    void formatShipToAddressJoinsNameAndLinesThenCityStateZipOnOneLine() {
        SalesOrderArchiveMigrationV2.AddressRow address = new SalesOrderArchiveMigrationV2.AddressRow();
        address.name = "Jane Doe";
        address.addressLine1 = "123 Main St";
        address.addressLine2 = "Apt 4";
        address.city = "Temecula";
        address.state = "CA";
        address.zipCode = "92591";

        String formatted = SalesOrderArchiveMigrationV2.formatShipToAddress(address);

        assertThat(formatted).isEqualTo("Jane Doe\n123 Main St\nApt 4\nTemecula, CA 92591");
    }

    @Test
    void formatShipToAddressSkipsBlankFieldsRatherThanLeavingEmptyLines() {
        // Real archive data: name/address_line1/address_line2 frequently come back as "" rather
        // than NULL (confirmed against the live store1_dev schema), not just genuinely absent.
        SalesOrderArchiveMigrationV2.AddressRow address = new SalesOrderArchiveMigrationV2.AddressRow();
        address.name = "";
        address.addressLine1 = "";
        address.addressLine2 = "";
        address.city = "Temecula";
        address.state = "CA";
        address.zipCode = "92591";

        String formatted = SalesOrderArchiveMigrationV2.formatShipToAddress(address);

        assertThat(formatted).isEqualTo("Temecula, CA 92591");
    }

    @Test
    void formatShipToAddressReturnsNullForANullOrCompletelyBlankAddress() {
        assertThat(SalesOrderArchiveMigrationV2.formatShipToAddress(null)).isNull();

        SalesOrderArchiveMigrationV2.AddressRow blank = new SalesOrderArchiveMigrationV2.AddressRow();
        assertThat(SalesOrderArchiveMigrationV2.formatShipToAddress(blank)).isNull();
    }

    @Test
    void trackingNumbersOfFiltersOutBlankTrackingNumbers() {
        var withTracking = new com.exampleco.core.dto.order.FulfillmentPackageTO();
        withTracking.setTrackingNumber("1Z999AA10123456784");
        var blank = new com.exampleco.core.dto.order.FulfillmentPackageTO();
        blank.setTrackingNumber("");
        var nullTracking = new com.exampleco.core.dto.order.FulfillmentPackageTO();

        List<String> trackingNumbers = SalesOrderArchiveMigrationV2.trackingNumbersOf(
                List.of(withTracking, blank, nullTracking));

        assertThat(trackingNumbers).containsExactly("1Z999AA10123456784");
    }

    // doc/0.7.0: sales_order_event.event_type decodes through the same decodeOrWarn tolerance as
    // status/salesChannel/fulfillmentType -- these pin the real id<->name pairs confirmed against
    // legacy-app's own SalesOrderEventType enum, not just decodeOrWarn's generic behavior above.
    @Test
    void archivedSalesOrderEventTypeDecodesAllKnownIds() {
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(1)).isEqualTo("EmailReview");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(2)).isEqualTo("EmailReviewAgain");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(3)).isEqualTo("EmailInstruction");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(11)).isEqualTo("Comment");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(12)).isEqualTo("ReviewBlocked");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(21)).isEqualTo("Refund");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(22)).isEqualTo("Replacement");
        assertThat(SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(99)).isEqualTo("Other");
    }

    @Test
    void archivedSalesOrderEventTypeThrowsOnAnUnknownId() {
        assertThatCode(() -> SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType.nameForId(4242))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decodeOrWarnDegradesAnUnknownEventTypeIdToNullInsteadOfThrowing() {
        String result = SalesOrderArchiveMigrationV2.decodeOrWarn("orderEvents.eventType", 1L, 4242,
                SalesOrderArchiveMigrationV2.ArchivedSalesOrderEventType::nameForId);
        assertThat(result).isNull();
    }
}
