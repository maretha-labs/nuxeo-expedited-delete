/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.IterableQueryResult;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.ecm.core.query.sql.NXQL;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.api.login.NuxeoLoginContext;
import org.nuxeo.runtime.stream.StreamProcessorTopology;

/**
 * Permanent delete, like {@code Document.Delete}: as system, one commit per batch, on its own stream. Documents Nuxeo
 * refuses (retention, legal hold, proxy target, checked-in base version) are skipped and counted as errors.
 */
public class ExpeditedDeleteAction implements StreamProcessorTopology {

    public static final String ACTION_NAME = "expeditedDelete";

    // = inputStream in OSGI-INF/expedited-delete-bulk-contrib.xml
    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    // options: <option> values of the <streamProcessor>, plus "processorName"; unused here
    @Override
    public Topology getTopology(Map<String, String> options) {
        // one step: read id buckets, report progress to bulk/status
        return Topology.builder()
                       .addComputation(ExpeditedDeleteComputation::new,
                               List.of(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class ExpeditedDeleteComputation extends AbstractBulkComputation {

        private static final Logger log = LogManager.getLogger(ExpeditedDeleteComputation.class);

        protected static final String SELECT_EXISTING = "SELECT ecm:uuid FROM Document, Relation WHERE ecm:uuid IN ('%s')";

        public ExpeditedDeleteComputation() {
            super(ACTION_FULL_NAME);
        }

        // always system; the launcher is kept as originating user
        @Override
        protected NuxeoLoginContext loginSystemOrUser(String username) {
            return Framework.loginSystem(username);
        }

        // whole batch in one transaction; if a document is refused, retry one by one
        @Override
        protected void processBatchOfDocuments(List<String> batch) {
            try {
                super.processBatchOfDocuments(batch);
            } catch (NuxeoException e) {
                if (batch.size() == 1) {
                    refused(batch.getFirst(), e);
                    return;
                }
                log.debug("Command: {} batch refused ({}), deleting one by one", command.getId(), e.getMessage());
                for (String id : batch) {
                    try {
                        super.processBatchOfDocuments(List.of(id));
                    } catch (NuxeoException oneRefused) {
                        refused(id, oneRefused);
                    }
                }
            }
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            // skip ids already gone (e.g. deleted with their folder)
            List<DocumentRef> refs = new ArrayList<>(ids.size());
            try (IterableQueryResult res = session.queryAndFetch(
                    String.format(SELECT_EXISTING, String.join("', '", ids)), NXQL.NXQL)) {
                res.forEach(row -> refs.add(new IdRef((String) row.get(NXQL.ECM_UUID))));
            }
            // parents first; skips children of folders removed in this call
            session.removeDocuments(refs.toArray(DocumentRef[]::new));
            session.save();
        }

        protected void refused(String id, NuxeoException e) {
            log.warn("Command: {} could not delete document: {}: {}", command.getId(), id, e.getMessage());
            delta.inError(1, "Could not delete " + id + ": " + e.getMessage(), e.getStatusCode());
        }
    }
}
