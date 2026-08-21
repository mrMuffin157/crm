package com.exampleco.crm.dto.ticket;


// Request shape for TicketWriteRequest#address — the Customer Address block, shown only for
// Replacement/ManualHandling tickets (matching the real form's checkAction() logic). A null
// value on the request means "leave whatever address this ticket already has alone"; this
// object present means "create or update it to exactly this."
public record AddressInput(String name, String addressLine1, String addressLine2, String city,
                            String state, String zipCode, String countryCode, String phone) {
}
