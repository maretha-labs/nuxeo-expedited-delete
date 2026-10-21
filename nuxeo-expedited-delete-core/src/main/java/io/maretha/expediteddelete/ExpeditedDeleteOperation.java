/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;

/**
 * Launches {@value ExpeditedDeleteAction#ACTION_NAME}. Admins only: the deletion ignores permissions.
 */
@Operation(id = ExpeditedDeleteOperation.ID, category = Constants.CAT_SERVICES, label = "Expedited Delete", description = "Permanently deletes every document matching the NXQL query, as the system user, on a dedicated stream. "
        + "Administrators only. The query must have a WHERE clause. Returns the bulk command status; poll it with Bulk.WaitForAction.")
public class ExpeditedDeleteOperation {

    public static final String ID = "Bulk.ExpeditedDelete";

    @Context
    protected CoreSession session;

    @Context
    protected BulkService bulkService;

    @Param(name = "query", description = "NXQL query selecting the documents to delete, with a WHERE clause.")
    protected String query;

    @OperationMethod
    public BulkStatus run() {
        if (!session.getPrincipal().isAdministrator()) {
            throw new NuxeoException("Expedited Delete is restricted to administrators", SC_FORBIDDEN);
        }
        // launcher recorded in the status; deletion runs as system
        BulkCommand command = new BulkCommand.Builder(ExpeditedDeleteAction.ACTION_NAME, query,
                session.getPrincipal().getName()).repository(session.getRepositoryName()).build();
        String commandId;
        try {
            commandId = bulkService.submit(command);
        } catch (IllegalArgumentException e) {
            // refused by ExpeditedDeleteValidation
            throw new NuxeoException(e.getMessage(), e, SC_BAD_REQUEST);
        }
        return bulkService.getStatus(commandId);
    }
}
