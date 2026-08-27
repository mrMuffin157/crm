package com.exampleco.crm.controller.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import com.exampleco.crm.domain.ticket.Customer;
import com.exampleco.crm.dto.ticket.UpdateCustomerNameRequest;
import com.exampleco.crm.exception.ticket.CustomerNotFoundException;
import com.exampleco.crm.repository.ticket.CustomerRepository;

// The one customer-facing endpoint the M2M API needs — kept as its own small
// controller rather than folded into TicketController, since it's a genuinely separate resource
// (crm_dev.customer, not ticket) with exactly one operation, not a growing feature surface.
@RestController
@CrossOrigin(origins = "${app.cors.allowed-origins}")
public class CustomerController {

    private final CustomerRepository customerRepository;

    public CustomerController(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    // legacy-app's "customer.name == 'n/a'" backfill: the customer-merge migration
    // (doc/0.2.0/migrate_tickets_to_crm_dev.sql) could only ever see a customer referenced by a
    // ticket, so its `name` can be a placeholder until legacy-app later resolves the real one from
    // an order lookup and writes it back here.
    @PatchMapping(value = "/api/customers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Customer> updateCustomerName(@PathVariable String id, @RequestBody UpdateCustomerNameRequest request) {
        return Mono.fromCallable(() -> {
            Customer customer = customerRepository.findById(id)
                    .orElseThrow(() -> new CustomerNotFoundException(id));
            customer.setName(request.name());
            return customerRepository.save(customer);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @ExceptionHandler(CustomerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(CustomerNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
