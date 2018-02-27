package com.atlassian.braid.mapper;

import java.util.Map;

import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

final class ListOperation implements MapperOperation {

    private final String key;
    private final Mapper mapper;

    ListOperation(String key, Mapper mapper) {
        this.key = requireNonNull(key);
        this.mapper = requireNonNull(mapper);
    }

    @Override
    public void accept(Map<String, Object> input, Map<String, Object> output) {
        output.put(key, singletonList(mapper.apply(input)));
    }
}