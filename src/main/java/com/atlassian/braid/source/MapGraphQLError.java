package com.atlassian.braid.source;

import com.atlassian.braid.java.util.BraidObjects;
import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.language.SourceLocation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Collections.unmodifiableSet;
import static java.util.stream.Collectors.toMap;

/**
 * Turns a map representing the data of a GraphQL error into a real GraphQLError
 */
@SuppressWarnings("WeakerAccess")
public class MapGraphQLError implements GraphQLError {


    private final String message;
    private final List<SourceLocation> locations;
    private final ErrorType errorType;
    private final List<Object> path;
    private final Map<String, Object> extensions;

    public MapGraphQLError(Map<String, Object> error) {
        this.message = Optional.ofNullable(error.get("message")).map(String.class::cast).orElse("Unknown error");
        this.locations = Optional.ofNullable(error.get("locations"))
                .map(BraidObjects::<List<Map>>cast)
                .map(errors ->
                        errors.stream()
                                .filter(err -> err.containsKey("line") && err.containsKey("column"))
                                .map(err -> new SourceLocation((Integer) err.get("line"), (Integer) err.get("column")))
                                .collect(Collectors.toList()))
                .orElse(null);
        this.errorType = ErrorType.DataFetchingException;
        this.path = Optional.ofNullable(error.get("path")).map(BraidObjects::<List<Object>>cast).orElse(null);
        this.extensions = Optional.ofNullable(error.get("extensions"))
                .map(BraidObjects::<Map<String, Object>>cast)
                .orElse(null);

    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public List<SourceLocation> getLocations() {
        return locations;
    }

    @Override
    public ErrorType getErrorType() {
        return errorType;
    }

    @Override
    public List<Object> getPath() {
        return path;
    }

    @Override
    public Map<String, Object> getExtensions() {
        return extensions;
    }
}
