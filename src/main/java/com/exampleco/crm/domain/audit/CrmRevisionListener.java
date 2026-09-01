package com.exampleco.crm.domain.audit;

import org.hibernate.envers.RevisionListener;

import com.exampleco.crm.service.audit.AuditActorContext;

// Hibernate calls newRevision() synchronously, on whatever thread is flushing the audited
// change -- see AuditActorContext's own comment for why that thread always already knows who
// the acting user is.
public class CrmRevisionListener implements RevisionListener {

    @Override
    public void newRevision(Object revisionEntity) {
        ((CrmRevisionEntity) revisionEntity).setUsername(AuditActorContext.getCurrentUsername());
    }
}
