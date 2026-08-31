package com.exampleco.crm.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Same failure shape as TicketReasonConverter's {@code reason = 0} fix: a decode
 * method that throws on an unrecognized legacy id would fail the whole chunk it's called from,
 * not just the one order. decodeOrWarn is the guard that keeps one retired status/salesChannel/
 * fulfillmentType code from aborting the entire migration run.
 */
class SalesOrderArchiveMigrationTest {

    @Test
    void decodeOrWarnReturnsTheDecodedValueOnSuccess() {
        String result = SalesOrderArchiveMigration.decodeOrWarn("status", 1L, 5, id -> "Shipped");
        assertThat(result).isEqualTo("Shipped");
    }

    @Test
    void decodeOrWarnDegradesAnUnmappableIdToNullInsteadOfThrowing() {
        assertThatCode(() -> SalesOrderArchiveMigration.decodeOrWarn("status", 1L, 42, id -> {
            throw new IllegalStateException("Unknown OrderStatus id: " + id);
        })).doesNotThrowAnyException();

        String result = SalesOrderArchiveMigration.decodeOrWarn("status", 1L, 42, id -> {
            throw new IllegalStateException("Unknown OrderStatus id: " + id);
        });
        assertThat(result).isNull();
    }

    @Test
    void decodeOrWarnDoesNotSwallowAnUnrelatedRuntimeException() {
        assertThatCode(() -> SalesOrderArchiveMigration.decodeOrWarn("status", 1L, 42, id -> {
            throw new NullPointerException("not an unmappable-id failure");
        })).isInstanceOf(NullPointerException.class);
    }

    @Test
    void formatShipToAddressJoinsNameAndLinesThenCityStateZipOnOneLine() {
        SalesOrderArchiveMigration.AddressRow address = new SalesOrderArchiveMigration.AddressRow();
        address.name = "Jane Doe";
        address.addressLine1 = "123 Main St";
        address.addressLine2 = "Apt 4";
        address.city = "Temecula";
        address.state = "CA";
        address.zipCode = "92591";

        String formatted = SalesOrderArchiveMigration.formatShipToAddress(address);

        assertThat(formatted).isEqualTo("Jane Doe\n123 Main St\nApt 4\nTemecula, CA 92591");
    }

    @Test
    void formatShipToAddressSkipsBlankFieldsRatherThanLeavingEmptyLines() {
        // Real archive data: name/address_line1/address_line2 frequently come back as "" rather
        // than NULL (confirmed against the live store1_dev schema), not just genuinely absent.
        SalesOrderArchiveMigration.AddressRow address = new SalesOrderArchiveMigration.AddressRow();
        address.name = "";
        address.addressLine1 = "";
        address.addressLine2 = "";
        address.city = "Temecula";
        address.state = "CA";
        address.zipCode = "92591";

        String formatted = SalesOrderArchiveMigration.formatShipToAddress(address);

        assertThat(formatted).isEqualTo("Temecula, CA 92591");
    }

    @Test
    void formatShipToAddressReturnsNullForANullOrCompletelyBlankAddress() {
        assertThat(SalesOrderArchiveMigration.formatShipToAddress(null)).isNull();

        SalesOrderArchiveMigration.AddressRow blank = new SalesOrderArchiveMigration.AddressRow();
        assertThat(SalesOrderArchiveMigration.formatShipToAddress(blank)).isNull();
    }

    @Test
    void trackingNumbersOfFiltersOutBlankTrackingNumbers() {
        var withTracking = new com.exampleco.core.dto.order.FulfillmentPackageTO();
        withTracking.setTrackingNumber("1Z999AA10123456784");
        var blank = new com.exampleco.core.dto.order.FulfillmentPackageTO();
        blank.setTrackingNumber("");
        var nullTracking = new com.exampleco.core.dto.order.FulfillmentPackageTO();

        List<String> trackingNumbers = SalesOrderArchiveMigration.trackingNumbersOf(
                List.of(withTracking, blank, nullTracking));

        assertThat(trackingNumbers).containsExactly("1Z999AA10123456784");
    }
}
