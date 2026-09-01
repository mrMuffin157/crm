package com.exampleco.crm.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

// One row per revision (Hibernate's unit of "a transaction touched an audited entity"). Envers'
// own DefaultRevisionEntity is final in this Hibernate version, so this is a from-scratch custom
// revision entity instead of a subclass: id/timestamp are the same rev/revtstamp columns Envers
// would generate on its own (@RevisionNumber/@RevisionTimestamp mark which fields those are);
// username is this app's own addition, populated by CrmRevisionListener from whoever
// AuditActorContext says is making the change. See doc/0.6.0/create_ticket_audit_tables.sql for
// the hand-written DDL this maps to (ddl-auto=none, same as every other table here).
@Entity
@Table(name = "revinfo")
@RevisionEntity(CrmRevisionListener.class)
@Getter
@Setter
public class CrmRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private int id;

    @RevisionTimestamp
    @Column(name = "revtstamp")
    private long timestamp;

    @Column(name = "username")
    private String username;
}
