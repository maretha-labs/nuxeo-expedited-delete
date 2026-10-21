/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import static io.maretha.expediteddelete.ExpeditedDeleteAction.ACTION_NAME;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.EVERYONE;
import static org.nuxeo.ecm.core.api.security.SecurityConstants.READ;
import static org.nuxeo.ecm.core.bulk.message.BulkStatus.State.COMPLETED;

import java.time.Duration;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.impl.ACLImpl;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;
import org.nuxeo.ecm.core.bulk.BulkAdminService;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

@RunWith(FeaturesRunner.class)
@Features(AutomationFeature.class)
@Deploy("io.maretha.expediteddelete.core")
public class TestExpeditedDelete {

    protected static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automation;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected BulkAdminService bulkAdmin;

    @Inject
    protected TransactionalFeature txFeature;

    @Test
    public void testActionIsRegisteredOnItsOwnStream() {
        assertTrue(bulkAdmin.getActions().contains(ACTION_NAME));
        assertEquals("bulk/expeditedDelete", bulkAdmin.getInputStream(ACTION_NAME));
        assertEquals(100, bulkAdmin.getBucketSize(ACTION_NAME));
        assertEquals(50, bulkAdmin.getBatchSize(ACTION_NAME));
        assertEquals("repository", bulkAdmin.getDefaultScroller(ACTION_NAME));
        assertEquals(Long.valueOf(0), bulkAdmin.getQueryLimit(ACTION_NAME));
        assertEquals(Duration.ZERO, bulkAdmin.getBatchTransactionTimeout(ACTION_NAME));
        assertFalse(bulkAdmin.isHttpEnabled(ACTION_NAME));
        assertFalse(bulkAdmin.isSequentialScroll(ACTION_NAME));
        assertFalse(bulkAdmin.isSequentialProcessing(ACTION_NAME));
        assertFalse(bulkAdmin.isExclusive(ACTION_NAME));
        assertTrue(bulkAdmin.getActionValidation(ACTION_NAME) instanceof ExpeditedDeleteValidation);
    }

    @Test
    public void testValidationAppliesToEveryLaunch() {
        // direct submit, like REST and Bulk.RunAction: validation still runs
        BulkCommand noWhere = new BulkCommand.Builder(ACTION_NAME, "SELECT * FROM Document",
                "Administrator").repository(session.getRepositoryName()).build();
        assertThrows(IllegalArgumentException.class, () -> bulkService.submit(noWhere));

        BulkCommand withParam = new BulkCommand.Builder(ACTION_NAME, "SELECT * FROM File WHERE dc:title = 'x'",
                "Administrator").repository(session.getRepositoryName()).param("unknown", "x").build();
        assertThrows(IllegalArgumentException.class, () -> bulkService.submit(withParam));
    }

    @Test
    public void testDeletesEveryMatchingDocument() throws Exception {
        DocumentModel folder = createDocument("/", "Folder", "folder");
        // 230 docs: 3 buckets, 5 batches
        for (int i = 0; i < 230; i++) {
            createDocument(folder.getPathAsString(), "File", "file" + i);
        }
        DocumentModel kept = createDocument("/", "File", "kept");
        txFeature.nextTransaction();

        String query = "SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'";
        BulkStatus status = expeditedDelete(query);

        assertEquals(COMPLETED, status.getState());
        assertEquals(230, status.getTotal());
        assertEquals(230, status.getProcessed());
        assertEquals(0, status.getErrorCount());
        assertEquals(0, session.query(query).size());
        assertTrue(session.exists(kept.getRef()));
    }

    @Test
    public void testFolderAndItsChildrenInTheSameQuery() throws Exception {
        DocumentModel folder = createDocument("/", "Folder", "folder");
        DocumentModel child = createDocument(folder.getPathAsString(), "File", "child");
        txFeature.nextTransaction();

        BulkStatus status = expeditedDelete(String.format(
                "SELECT * FROM Document WHERE ecm:uuid = '%s' OR ecm:ancestorId = '%s'", folder.getId(),
                folder.getId()));

        assertEquals(COMPLETED, status.getState());
        assertEquals(0, status.getErrorCount());
        assertFalse(session.exists(folder.getRef()));
        assertFalse(session.exists(child.getRef()));
    }

    @Test
    public void testSkipsDocumentUnderLegalHoldAndDeletesTheRest() throws Exception {
        DocumentModel folder = createDocument("/", "Folder", "folder");
        DocumentModel held = createDocument(folder.getPathAsString(), "File", "held");
        for (int i = 0; i < 4; i++) {
            createDocument(folder.getPathAsString(), "File", "file" + i);
        }
        session.makeRecord(held.getRef());
        session.setLegalHold(held.getRef(), true, "test");
        txFeature.nextTransaction();

        String query = "SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'";
        BulkStatus status = expeditedDelete(query);

        assertEquals(COMPLETED, status.getState());
        assertEquals(5, status.getProcessed());
        assertEquals(1, status.getErrorCount());
        assertTrue(status.getErrorMessage(), status.getErrorMessage().contains(held.getId()));
        assertEquals(1, session.query(query).size());
        assertTrue(session.exists(held.getRef()));
    }

    @Test
    public void testDeletesAsSystemWhoeverLaunchedTheCommand() throws Exception {
        // bob can only read; the action ignores permissions
        DocumentModel folder = createDocument("/", "Folder", "folder");
        ACP acp = new ACPImpl();
        ACLImpl acl = new ACLImpl();
        acl.add(new ACE("bob", READ, true));
        acl.add(new ACE(EVERYONE, "Everything", false));
        acp.addACL(acl);
        folder.setACP(acp, true);
        DocumentModel file = createDocument(folder.getPathAsString(), "File", "file");
        txFeature.nextTransaction();

        // direct submit, no admin check
        BulkCommand command = new BulkCommand.Builder(ACTION_NAME,
                "SELECT * FROM File WHERE ecm:uuid = '" + file.getId() + "'", "bob").repository(
                        session.getRepositoryName()).build();
        BulkStatus status = await(bulkService.submit(command));

        assertEquals(COMPLETED, status.getState());
        assertEquals(1, status.getProcessed());
        assertEquals(0, status.getErrorCount());
        assertFalse(session.exists(file.getRef()));
    }

    @Test
    public void testOperationIsForAdministratorsOnly() {
        CoreSession bobSession = CoreInstance.getCoreSession(session.getRepositoryName(), "bob");
        OperationContext ctx = new OperationContext(bobSession);
        NuxeoException e = assertThrows(NuxeoException.class, () -> automation.run(ctx, ExpeditedDeleteOperation.ID,
                Map.of("query", "SELECT * FROM File WHERE dc:title = 'x'")));
        assertEquals(403, e.getStatusCode());
    }

    @Test
    public void testOperationRejectsQueryWithoutWhereClause() {
        OperationContext ctx = new OperationContext(session);
        NuxeoException e = assertThrows(NuxeoException.class,
                () -> automation.run(ctx, ExpeditedDeleteOperation.ID, Map.of("query", "SELECT * FROM Document")));
        assertEquals(400, e.getStatusCode());
    }

    protected DocumentModel createDocument(String parentPath, String type, String name) {
        return session.createDocument(session.createDocumentModel(parentPath, name, type));
    }

    protected BulkStatus expeditedDelete(String query) throws Exception {
        OperationContext ctx = new OperationContext(session);
        BulkStatus submitted = (BulkStatus) automation.run(ctx, ExpeditedDeleteOperation.ID, Map.of("query", query));
        return await(submitted.getId());
    }

    protected BulkStatus await(String commandId) throws InterruptedException {
        assertTrue("bulk command timed out", bulkService.await(commandId, TIMEOUT));
        // also waits for Nuxeo's own bulk commands (folder children)
        txFeature.nextTransaction();
        return bulkService.getStatus(commandId);
    }
}
