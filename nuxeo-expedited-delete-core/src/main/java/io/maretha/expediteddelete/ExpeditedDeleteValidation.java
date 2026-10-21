/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.expediteddelete;

import java.util.List;

import org.nuxeo.ecm.core.bulk.AbstractBulkActionValidation;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.query.QueryParseException;
import org.nuxeo.ecm.core.query.sql.SQLQueryParser;
import org.nuxeo.ecm.core.query.sql.model.SQLQuery;

/**
 * The action's {@code validationClass}: runs in {@code BulkService.submit}, so on every launch route.
 */
public class ExpeditedDeleteValidation extends AbstractBulkActionValidation {

    // no parameters allowed
    @Override
    protected List<String> getParametersToValidate() {
        return List.of();
    }

    // WHERE required: "SELECT * FROM Document" would delete everything
    @Override
    protected void validateCommand(BulkCommand command) {
        SQLQuery query;
        try {
            query = SQLQueryParser.parse(command.getQuery());
        } catch (QueryParseException e) {
            throw new IllegalArgumentException("Invalid NXQL query: " + e.getMessage(), e);
        }
        if (query.where == null || query.where.predicate == null) {
            throw new IllegalArgumentException("The query must have a WHERE clause");
        }
    }
}
