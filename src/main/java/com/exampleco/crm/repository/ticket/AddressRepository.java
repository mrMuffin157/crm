package com.exampleco.crm.repository.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import com.exampleco.crm.domain.ticket.Address;

public interface AddressRepository extends JpaRepository<Address, String> {
}
