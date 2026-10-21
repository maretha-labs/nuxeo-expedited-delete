/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import static io.maretha.expediteddelete.ExpeditedDeleteAction.ACTION_NAME;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Duration;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.core.search.client.opensearch1.OpenSearchCoreSearchFeature;
import org.nuxeo.ecm.restapi.test.RestServerFeature;
import org.nuxeo.http.test.HttpClientTestRule;
import org.nuxeo.http.test.handler.JsonNodeHandler;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * REST "scroll=elastic": the deprecated OpenSearch 1 scroller, on an embedded OpenSearch.
 */
@RunWith(FeaturesRunner.class)
@Features({ RestServerFeature.class, OpenSearchCoreSearchFeature.class })
@Deploy("org.nuxeo.ecm.platform.search.core")
@Deploy("org.nuxeo.ecm.platform.restapi.server.search")
@Deploy("io.maretha.expediteddelete.core")
public class TestExpeditedDeleteElasticScroller {

    @Inject
    protected RestServerFeature restServerFeature;

    @Inject
    protected CoreSession session;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected TransactionalFeature txFeature;

    @Rule
    public final HttpClientTestRule httpClient = HttpClientTestRule.builder()
                                                                   .url(() -> restServerFeature.getRestApiUrl())
                                                                   .adminCredentials()
                                                                   .accept(MediaType.APPLICATION_JSON)
                                                                   .contentType(MediaType.APPLICATION_JSON)
                                                                   .build();

    @Test
    public void testLaunchWithElasticScroller() throws Exception {
        DocumentModel folder = session.createDocument(session.createDocumentModel("/", "folder", "Folder"));
        for (int i = 0; i < 3; i++) {
            session.createDocument(session.createDocumentModel(folder.getPathAsString(), "file" + i, "File"));
        }
        // commits and waits for indexing
        txFeature.nextTransaction();

        JsonNode submitted = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                                       .addQueryParameter("query", "SELECT * FROM File WHERE ecm:parentId = ?")
                                       .addQueryParameter("queryParams", folder.getId())
                                       .addQueryParameter("scroll", "elastic")
                                       .execute(new JsonNodeHandler(SC_ACCEPTED));
        String commandId = submitted.get("commandId").asText();
        assertTrue("bulk command timed out", bulkService.await(commandId, Duration.ofSeconds(60)));
        txFeature.nextTransaction();

        BulkStatus status = bulkService.getStatus(commandId);
        assertEquals(3, status.getTotal());
        assertEquals(3, status.getProcessed());
        assertEquals(0, status.getErrorCount());
        assertEquals(0, session.query("SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'").size());
    }
}
