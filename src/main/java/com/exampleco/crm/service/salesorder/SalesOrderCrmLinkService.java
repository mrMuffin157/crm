package com.exampleco.crm.service.salesorder;

import com.exampleco.crm.domain.ticket.Address;
import com.exampleco.crm.domain.ticket.Customer;
import com.exampleco.crm.domain.ticket.Ticket;
import com.exampleco.crm.dto.salesorder.SalesOrderLookupResult;
import com.exampleco.crm.repository.ticket.AddressRepository;
import com.exampleco.crm.repository.ticket.CustomerRepository;
import com.exampleco.crm.repository.ticket.TicketRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Finds a crm_dev ticket already created for the same order/tracking number a Sales Order
// lookup was made by, and resolves that ticket's own linked customer/address rows -- now that
// crm_dev's customer/address migration reflects real (not placeholder) data from the 4 legacy
// DBs. There's no reverse index from customer/address back to an order: the only join path is
// order/tracking number -> Ticket -> Ticket.customerId/customerAddressId, so this only produces
// a result when a ticket already exists for that order. Plain JPA reads, so this needs the
// reactive-shell boundedElastic wrapping (unlike LegacyApiClient's own plain WebClient calls).
@Service
public class SalesOrderCrmLinkService {

    private final TicketRepository ticketRepository;
    private final CustomerRepository customerRepository;
    private final AddressRepository addressRepository;

    public SalesOrderCrmLinkService(TicketRepository ticketRepository, CustomerRepository customerRepository,
                                     AddressRepository addressRepository) {
        this.ticketRepository = ticketRepository;
        this.customerRepository = customerRepository;
        this.addressRepository = addressRepository;
    }

    public Mono<SalesOrderLookupResult.CrmLink> lookup(String amazonOrderId, String trackingNumber) {
        return Mono.fromCallable(() -> {
            List<Ticket> matches = amazonOrderId != null
                    ? ticketRepository.findByOrderNumber(amazonOrderId)
                    : ticketRepository.findByTrackingNumber(trackingNumber);

            // orderNumber/trackingNumber aren't unique -- take the most recently created match
            // as the one representative row, same tolerance the /by-order endpoint documents.
            Optional<Ticket> ticket = matches.stream()
                    .max(Comparator.comparing(Ticket::getDateCreated));

            if (ticket.isEmpty()) {
                return SalesOrderLookupResult.CrmLink.EMPTY;
            }

            Ticket t = ticket.get();
            Customer customer = t.getCustomerId() == null ? null
                    : customerRepository.findById(t.getCustomerId()).orElse(null);
            Address address = t.getCustomerAddressId() == null ? null
                    : addressRepository.findById(t.getCustomerAddressId()).orElse(null);

            SalesOrderLookupResult.CrmAddress crmAddress = address == null ? null
                    : new SalesOrderLookupResult.CrmAddress(address.getName(), address.getPhone(),
                            address.getAddressLine1(), address.getAddressLine2(), address.getCity(),
                            address.getState(), address.getZipCode(), address.getCountryCode());

            return new SalesOrderLookupResult.CrmLink(t.getId(),
                    customer == null ? null : customer.getName(),
                    customer == null ? null : customer.getEmail(),
                    crmAddress);
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
