/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import static io.maretha.expediteddelete.ExpeditedDeleteAction.ACTION_NAME;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.http.HttpStatus.SC_ACCEPTED;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_FORBIDDEN;
import static org.apache.http.HttpStatus.SC_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.core.MediaType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.bulk.BulkService;
import org.nuxeo.ecm.core.bulk.message.BulkStatus;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.restapi.test.RestServerFeature;
import org.nuxeo.http.test.HttpClientTestRule;
import org.nuxeo.http.test.handler.HttpStatusCodeHandler;
import org.nuxeo.http.test.handler.JsonNodeHandler;
import org.nuxeo.runtime.stream.StreamService;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * docs/curl.md calls on an embedded REST API (paths relative to /api/v1/).
 */
@RunWith(FeaturesRunner.class)
@Features(RestServerFeature.class)
@Deploy("org.nuxeo.ecm.platform.search.core")
@Deploy("org.nuxeo.ecm.platform.restapi.server.search")
@Deploy("io.maretha.expediteddelete.core")
@Deploy("io.maretha.expediteddelete.core:OSGI-INF/test-scroller-contrib.xml")
public class TestExpeditedDeleteRest {

    protected static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Inject
    protected RestServerFeature restServerFeature;

    @Inject
    protected CoreSession session;

    @Inject
    protected BulkService bulkService;

    @Inject
    protected StreamService streamService;

    @Inject
    protected UserManager userManager;

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
    public void testLaunchWithQueryParamsThenReadStatus() throws Exception {
        DocumentModel folder = createFolderWithFiles(3);
        DocumentModel kept = createDocument("/", "File", "kept");
        txFeature.nextTransaction();

        JsonNode submitted = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                                       .addQueryParameter("query", "SELECT * FROM File WHERE ecm:parentId = ?")
                                       .addQueryParameter("queryParams", folder.getId())
                                       .execute(new JsonNodeHandler(SC_ACCEPTED));
        assertEquals("bulkStatus", submitted.get("entity-type").asText());
        String commandId = submitted.get("commandId").asText();
        await(commandId);

        JsonNode status = httpClient.buildGetRequest("bulk/" + commandId).execute(new JsonNodeHandler(SC_OK));
        assertEquals("COMPLETED", status.get("state").asText());
        assertEquals(3, status.get("total").asInt());
        assertEquals(3, status.get("processed").asInt());
        assertEquals(0, status.get("errorCount").asInt());
        status = httpClient.buildGetRequest("management/bulk/" + commandId).execute(new JsonNodeHandler(SC_OK));
        assertEquals("COMPLETED", status.get("state").asText());
        // completed: cannot abort
        assertEquals(SC_BAD_REQUEST, deleteStatus("management/bulk/" + commandId));

        assertEquals(0, countChildren(folder));
        assertTrue(session.exists(kept.getRef()));
    }

    @Test
    public void testRequestAsSentByCurl() throws Exception {
        DocumentModel folder = createFolderWithFiles(2);
        txFeature.nextTransaction();

        // as curl --url-query sends it: space = "+"
        String query = URLEncoder.encode("SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'", UTF_8);
        String url = restServerFeature.getRestApiUrl().replaceAll("/$", "") + "/search/bulk/" + ACTION_NAME
                + "?query=" + query;
        // test server admin; password = name
        String admin = HttpClientTestRule.ADMINISTRATOR;
        String credentials = Base64.getEncoder().encodeToString((admin + ":" + admin).getBytes(UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                                         .header("Authorization", "Basic " + credentials)
                                         .header("Content-Type", MediaType.APPLICATION_JSON)
                                         .POST(HttpRequest.BodyPublishers.noBody())
                                         .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(url, query.contains("+"));
        assertEquals(response.body(), SC_ACCEPTED, response.statusCode());

        String commandId = new ObjectMapper().readTree(response.body()).get("commandId").asText();
        assertEquals(2, await(commandId).getProcessed());
        assertEquals(0, countChildren(folder));
    }

    @Test
    public void testLaunchWithNamedParamScrollAndQueryLimit() throws Exception {
        DocumentModel folder = createFolderWithFiles(3);
        txFeature.nextTransaction();

        JsonNode submitted = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                                       .addQueryParameter("query", "SELECT * FROM File WHERE ecm:parentId = :parentId")
                                       .addQueryParameter("parentId", folder.getId())
                                       .addQueryParameter("scroll", "repository")
                                       .addQueryParameter("queryLimit", "2")
                                       .execute(new JsonNodeHandler(SC_ACCEPTED));
        BulkStatus status = await(submitted.get("commandId").asText());

        assertEquals(2, status.getTotal());
        assertTrue(status.isQueryLimitReached());
        assertEquals(1, countChildren(folder));
    }

    @Test
    public void testLaunchWithOtherScrollers() throws Exception {
        // the custom scroller example of expedited-delete-bulk-contrib.xml
        // ("elastic": TestExpeditedDeleteElasticScroller)
        for (String scroller : List.of("expeditedDeleteRepository")) {
            DocumentModel folder = createFolderWithFiles(2);
            txFeature.nextTransaction();

            JsonNode submitted = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                                           .addQueryParameter("query", "SELECT * FROM File WHERE ecm:parentId = ?")
                                           .addQueryParameter("queryParams", folder.getId())
                                           .addQueryParameter("scroll", scroller)
                                           .execute(new JsonNodeHandler(SC_ACCEPTED));
            BulkStatus status = await(submitted.get("commandId").asText());

            assertEquals(scroller, 2, status.getProcessed());
            assertEquals(scroller, 0, countChildren(folder));
        }
    }

    @Test
    public void testAbortRoutes() throws Exception {
        DocumentModel folder = createFolderWithFiles(2);
        txFeature.nextTransaction();
        String query = "SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'";
        // processor stopped: commands wait, so they can be aborted
        assertTrue(streamService.stopProcessor(ACTION_NAME));
        try {
            String first = launch(query);
            JsonNode aborted = httpClient.buildPutRequest("bulk/" + first + "/abort")
                                         .execute(new JsonNodeHandler(SC_OK));
            assertEquals("ABORTED", aborted.get("state").asText());

            String second = launch(query);
            aborted = httpClient.buildDeleteRequest("management/bulk/" + second).execute(new JsonNodeHandler(SC_OK));
            assertEquals("ABORTED", aborted.get("state").asText());
        } finally {
            assertTrue(streamService.startProcessor(ACTION_NAME));
        }
        txFeature.nextTransaction();
        assertEquals(2, countChildren(folder));
    }

    @Test
    public void testLaunchWithoutQueryIsRefused() {
        DocumentModel file = createDocument("/", "File", "file");
        txFeature.nextTransaction();
        // no "query" = "SELECT * FROM Document": refused
        assertEquals(SC_BAD_REQUEST, postStatus("search/bulk/" + ACTION_NAME, null, null));
        txFeature.nextTransaction();
        assertTrue(session.exists(file.getRef()));
    }

    @Test
    public void testLaunchWithParametersIsRefused() {
        assertEquals(SC_BAD_REQUEST, postStatus("search/bulk/" + ACTION_NAME, "SELECT * FROM File WHERE dc:title = 'x'",
                "{\"unknown\": \"x\"}"));
    }

    @Test
    public void testLaunchWithUnknownScrollerIsRefused() {
        int status = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                               .addQueryParameter("query", "SELECT * FROM File WHERE dc:title = 'x'")
                               .addQueryParameter("scroll", "unknown")
                               .execute(new HttpStatusCodeHandler());
        assertEquals(SC_BAD_REQUEST, status);
    }

    @Test
    public void testUnknownActionIsRefused() {
        assertEquals(SC_BAD_REQUEST, postStatus("search/bulk/unknown", "SELECT * FROM File WHERE dc:title = 'x'", null));
    }

    @Test
    public void testNonAdministratorIsRefused() {
        createUser("bob");
        int status = httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                               .credentials("bob", "bob")
                               .addQueryParameter("query", "SELECT * FROM File WHERE dc:title = 'x'")
                               .execute(new HttpStatusCodeHandler());
        assertEquals(SC_FORBIDDEN, status);
        status = httpClient.buildPostRequest("automation/" + ExpeditedDeleteOperation.ID)
                           .credentials("bob", "bob")
                           .entity("{\"params\": {\"query\": \"SELECT * FROM File WHERE dc:title = 'x'\"}}")
                           .execute(new HttpStatusCodeHandler());
        assertEquals(SC_FORBIDDEN, status);
    }

    @Test
    public void testOperation() throws Exception {
        DocumentModel file = createDocument("/", "File", "file");
        txFeature.nextTransaction();

        JsonNode submitted = httpClient.buildPostRequest("automation/" + ExpeditedDeleteOperation.ID)
                                       .entity("{\"params\": {\"query\": \"SELECT * FROM File WHERE ecm:uuid = '"
                                               + file.getId() + "'\"}}")
                                       .execute(new JsonNodeHandler(SC_OK));
        // Automation wraps the status in "value"
        assertEquals("bulkStatus", submitted.get("entity-type").asText());
        await(submitted.get("value").get("commandId").asText());
        assertFalse(session.exists(file.getRef()));

        int status = httpClient.buildPostRequest("automation/" + ExpeditedDeleteOperation.ID)
                               .entity("{\"params\": {\"query\": \"SELECT * FROM Document\"}}")
                               .execute(new HttpStatusCodeHandler());
        assertEquals(SC_BAD_REQUEST, status);
    }

    protected String launch(String query) {
        return httpClient.buildPostRequest("search/bulk/" + ACTION_NAME)
                         .addQueryParameter("query", query)
                         .execute(new JsonNodeHandler(SC_ACCEPTED))
                         .get("commandId")
                         .asText();
    }

    protected int postStatus(String path, String query, String body) {
        HttpClientTestRule.RequestBuilder request = httpClient.buildPostRequest(path);
        if (query != null) {
            request.addQueryParameter("query", query);
        }
        if (body != null) {
            request.entity(body);
        }
        return request.execute(new HttpStatusCodeHandler());
    }

    protected int deleteStatus(String path) {
        return httpClient.buildDeleteRequest(path).execute(new HttpStatusCodeHandler());
    }

    protected DocumentModel createFolderWithFiles(int count) {
        DocumentModel folder = createDocument("/", "Folder", "folder");
        for (int i = 0; i < count; i++) {
            createDocument(folder.getPathAsString(), "File", "file" + i);
        }
        return folder;
    }

    protected DocumentModel createDocument(String parentPath, String type, String name) {
        return session.createDocument(session.createDocumentModel(parentPath, name, type));
    }

    protected int countChildren(DocumentModel folder) {
        return session.query("SELECT * FROM File WHERE ecm:parentId = '" + folder.getId() + "'").size();
    }

    protected void createUser(String name) {
        DocumentModel user = userManager.getBareUserModel();
        user.setPropertyValue("user:username", name);
        user.setPropertyValue("user:password", name);
        userManager.createUser(user);
        txFeature.nextTransaction();
    }

    protected BulkStatus await(String commandId) throws InterruptedException {
        assertTrue("bulk command timed out", bulkService.await(commandId, TIMEOUT));
        txFeature.nextTransaction();
        return bulkService.getStatus(commandId);
    }
}
