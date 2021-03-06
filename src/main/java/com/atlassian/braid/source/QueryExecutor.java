package com.atlassian.braid.source;

import com.atlassian.braid.BatchLoaderFactory;
import com.atlassian.braid.BraidContext;
import com.atlassian.braid.BraidContexts;
import com.atlassian.braid.GraphQLQueryVisitor;
import com.atlassian.braid.Link;
import com.atlassian.braid.SchemaSource;
import com.atlassian.braid.document.DocumentMapper.MappedDocument;
import com.atlassian.braid.java.util.BraidObjects;
import graphql.ExecutionInput;
import graphql.GraphQLError;
import graphql.execution.DataFetcherResult;
import graphql.language.AbstractNode;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Definition;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.InputValueDefinition;
import graphql.language.Node;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.OperationDefinition;
import graphql.language.OperationDefinition.Operation;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLModifiedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import org.dataloader.BatchLoader;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.atlassian.braid.BatchLoaderUtils.getTargetIdsFromEnvironment;
import static com.atlassian.braid.TypeUtils.findQueryFieldDefinitions;
import static com.atlassian.braid.graphql.language.GraphQLNodes.printNode;
import static com.atlassian.braid.java.util.BraidCollectors.SingletonCharacteristics.ALLOW_MULTIPLE_OCCURRENCES;
import static com.atlassian.braid.java.util.BraidCollectors.nullSafeToMap;
import static com.atlassian.braid.java.util.BraidCollectors.singleton;
import static graphql.introspection.Introspection.TypeNameMetaFieldDef;
import static graphql.language.OperationDefinition.Operation.MUTATION;
import static graphql.language.OperationDefinition.Operation.QUERY;
import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.toList;

/**
 * Executes a query against the data source
 */
class QueryExecutor<C> implements BatchLoaderFactory {

    private final QueryFunction<C> queryFunction;

    QueryExecutor(QueryFunction<C> queryFunction) {
        this.queryFunction = requireNonNull(queryFunction);
    }

    @Override
    public BatchLoader<DataFetchingEnvironment, DataFetcherResult<Object>> newBatchLoader(SchemaSource schemaSource,
                                                                                          @Nullable Link link) {
        return new QueryExecutorBatchLoader<>(BraidObjects.cast(schemaSource), link, queryFunction);
    }

    private static class QueryExecutorBatchLoader<C> implements BatchLoader<DataFetchingEnvironment, DataFetcherResult<Object>> {

        private final QueryExecutorSchemaSource schemaSource;

        @Nullable
        private final Link link;

        private final QueryFunction<C> queryFunction;

        private QueryExecutorBatchLoader(QueryExecutorSchemaSource schemaSource, @Nullable Link link, QueryFunction<C> queryFunction) {
            this.schemaSource = requireNonNull(schemaSource);
            this.link = link;
            this.queryFunction = requireNonNull(queryFunction);
        }

        @Override
        public CompletionStage<List<DataFetcherResult<Object>>> load(List<DataFetchingEnvironment> environments) {
            final C context = checkAndGetContext(environments);
            final Operation operationType = checkAndGetOperationType(environments).orElse(QUERY);
            final GraphQLOutputType fieldOutputType = checkAndGetFieldOutputType(environments);

            Document doc = new Document();

            OperationDefinition queryOp = newQueryOperationDefinition(fieldOutputType, operationType);

            doc.getDefinitions().add(queryOp);

            Map<String, Object> variables = new HashMap<>();
            Map<DataFetchingEnvironment, List<FieldKey>> clonedFields = new HashMap<>();

            // start at 99 so that we can find variables already counter-namespaced via startsWith()
            AtomicInteger counter = new AtomicInteger(99);

            // this is to gather data we don't need to fetch through batch loaders, e.g. when on the the variable used in
            // the query is fetched
            final Map<FieldKey, Object> shortCircuitedData = new HashMap<>();

            // build batch queryResult
            for (DataFetchingEnvironment environment : environments) {
                List<FieldRequest> fields = new ArrayList<>();
                List<Integer> usedCounterIds = new ArrayList<>();

                final OperationDefinition operationDefinition = getOperationDefinition(environment);

                // add variable and argument for linked field identifier
                if (link != null) {
                    final List targetIds = getTargetIdsFromEnvironment(link, environment);

                    Field cloneOfCurrentField = environment.getField().deepCopy();
                    boolean fieldQueryOnlySelectingVariable = isFieldQueryOnlySelectingVariable(cloneOfCurrentField, link);
                    for (Object targetId : targetIds) {
                        final FieldRequest field = cloneField(schemaSource, counter, usedCounterIds, environment);
                        if (isTargetIdNullAndCannotQueryLinkWithNull(targetId, link)) {
                            shortCircuitedData.put(new FieldKey(field.field.getAlias()), null);
                        } else if (fieldQueryOnlySelectingVariable) {
                            shortCircuitedData.put(new FieldKey(field.field.getAlias()), new HashMap<String, Object>() {{
                                put(link.getTargetVariableQueryField(), targetId);
                            }});
                        } else {
                            addQueryVariable(queryOp, variables, counter, targetId, field);
                            addFieldToQuery(doc, queryOp, variables, environment, operationDefinition, field);
                        }

                        fields.add(field);
                    }
                } else {
                    FieldRequest field = cloneField(schemaSource, counter, usedCounterIds, environment);
                    fields.add(field);
                    addFieldToQuery(doc, queryOp, variables, environment, operationDefinition, field);
                }
                clonedFields.put(environment, fields.stream()
                        .map(f -> f.field.getAlias())
                        .map(FieldKey::new)
                        .collect(toList()));
            }

            final MappedDocument mappedDocument = schemaSource.getDocumentMapper().apply(doc);

            CompletableFuture<DataFetcherResult<Map<String, Object>>> queryResult = executeQuery(context, mappedDocument.getDocument(), queryOp, variables);
            return queryResult
                    .thenApply(result -> {
                        final HashMap<FieldKey, Object> data = new HashMap<>();
                        Map<FieldKey, Object> dataByKey = result.getData().entrySet().stream()
                                .collect(nullSafeToMap(e -> new FieldKey(e.getKey()), Map.Entry::getValue));
                        data.putAll(dataByKey);
                        data.putAll(shortCircuitedData);
                        return new DataFetcherResult<Map<FieldKey, Object>>(data, result.getErrors());
                    })
                    .thenApply(result -> {
                        final Function<Map<String, Object>, Map<String, Object>> mapper = mappedDocument.getResultMapper();
                        final Map<String, Object> data = new HashMap<>();
                        result.getData().forEach((key, value) -> data.put(key.value, value));

                        final Map<String, Object> newData = mapper.apply(data);

                        final Map<FieldKey, Object> resultData = new HashMap<>();
                        newData.forEach((key, value) -> resultData.put(new FieldKey(key), value));
                        return new DataFetcherResult<>(resultData, result.getErrors());
                    })
                    .thenApply(result -> transformBatchResultIntoResultList(environments, clonedFields, result));
        }

        private static <C> C checkAndGetContext(Collection<DataFetchingEnvironment> environments) {
            return environments.stream().map(BraidContexts::<C>get).collect(singleton(ALLOW_MULTIPLE_OCCURRENCES));
        }

        private static Optional<Operation> checkAndGetOperationType(Collection<DataFetchingEnvironment> environments) {
            return environments.stream()
                    .map(QueryExecutorBatchLoader::getOperationType)
                    .collect(singleton(ALLOW_MULTIPLE_OCCURRENCES));
        }

        private static Optional<Operation> getOperationType(DataFetchingEnvironment env) {
            final GraphQLType graphQLType = env.getParentType();
            final GraphQLSchema graphQLSchema = env.getGraphQLSchema();
            if (Objects.equals(graphQLSchema.getQueryType(), graphQLType)) {
                return Optional.of(QUERY);
            } else if (Objects.equals(graphQLSchema.getMutationType(), graphQLType)) {
                return Optional.of(MUTATION);
            } else {
                return Optional.empty();
            }
        }

        /**
         * Checks the field type for all environments is the same and returns it
         *
         * @param environments the collection of environments to check
         * @return the found {@link GraphQLOutputType}
         */
        private static GraphQLOutputType checkAndGetFieldOutputType(List<DataFetchingEnvironment> environments) {
            return environments.stream()
                    .map(DataFetchingEnvironment::getFieldDefinition)
                    .map(GraphQLFieldDefinition::getType)
                    .collect(singleton(ALLOW_MULTIPLE_OCCURRENCES));
        }

        private void addFieldToQuery(Document doc, OperationDefinition queryOp, Map<String, Object> variables, DataFetchingEnvironment environment, OperationDefinition operationDefinition, FieldRequest field) {
            final GraphQLQueryVisitor variableNameSpacer =
                    new VariableNamespacingGraphQLQueryVisitor(field.counter, operationDefinition, variables, environment, queryOp);
            processForFragments(schemaSource, environment, field.field).forEach(d -> {
                variableNameSpacer.visit(d);
                doc.getDefinitions().add(d);
            });

            variableNameSpacer.visit(field.field);
            queryOp.getSelectionSet().getSelections().add(field.field);
        }

        private CompletableFuture<DataFetcherResult<Map<String, Object>>> executeQuery(C context, Document doc, OperationDefinition queryOp, Map<String, Object> variables) {
            final CompletableFuture<DataFetcherResult<Map<String, Object>>> queryResult;
            if (queryOp.getSelectionSet().getSelections().isEmpty()) {
                queryResult = completedFuture(new DataFetcherResult<>(emptyMap(), emptyList()));
            } else {
                ExecutionInput input = executeBatchQuery(doc, queryOp.getName(), variables);
                queryResult = queryFunction.query(input, context);
            }
            return queryResult;
        }

        private void addQueryVariable(OperationDefinition queryOp, Map<String, Object> variables, AtomicInteger counter, Object targetId, FieldRequest field) {
            final String variableName = link.getArgumentName() + counter;

            field.field.setName(link.getTargetQueryField());
            field.field.setArguments(linkQueryArgumentAsList(link, variableName));

            queryOp.getVariableDefinitions().add(linkQueryVariableDefinition(link, variableName, schemaSource));
            variables.put(variableName, targetId);
        }

        private FieldRequest cloneField(SchemaSource schemaSource, AtomicInteger counter, List<Integer> usedCounterIds,
                                        DataFetchingEnvironment environment) {
            final Field field = cloneFieldBeingFetchedWithAlias(environment, createFieldAlias(counter.incrementAndGet()));
            usedCounterIds.add(counter.get());
            trimFieldSelection(schemaSource, environment, field);
            return new FieldRequest(field, counter.get());
        }
    }

    private static OperationDefinition getOperationDefinition(DataFetchingEnvironment environment) {
        return environment.<BraidContext>getContext().getExecutionContext().getOperationDefinition();
    }

    private static boolean isTargetIdNullAndCannotQueryLinkWithNull(Object targetId, Link link) {
        return targetId == null && !link.isNullable();
    }

    private static boolean isFieldQueryOnlySelectingVariable(Field field, Link link) {
        final List<Selection> selections = field.getSelectionSet().getSelections();
        return selections.stream().allMatch(s -> s instanceof Field) &&// this means that any fragment will make this return false
                selections.stream()
                        .map(BraidObjects::<Field>cast)
                        .allMatch(f -> f.getName().equals(link.getTargetVariableQueryField()));
    }

    private static VariableDefinition linkQueryVariableDefinition(Link link, String variableName, SchemaSource schemaSource) {
        return new VariableDefinition(variableName, findArgumentType(schemaSource, link));
    }

    private static List<Argument> linkQueryArgumentAsList(Link link, String variableName) {
        return singletonList(new Argument(link.getArgumentName(), new VariableReference(variableName)));
    }

    private static Function<Field, String> createFieldAlias(int counter) {
        return field -> field.getName() + counter;
    }

    private static OperationDefinition newQueryOperationDefinition(GraphQLOutputType fieldType,
                                                                   Operation operationType) {
        return new OperationDefinition(newBulkOperationName(fieldType), operationType, new SelectionSet());
    }

    private static String newBulkOperationName(GraphQLOutputType fieldType) {
        String type;
        if (fieldType instanceof GraphQLList) {
            type = ((GraphQLList) fieldType).getWrappedType().getName();
        } else {
            type = fieldType.getName();
        }
        return "Bulk_" + type;
    }

    private static Field cloneFieldBeingFetchedWithAlias(DataFetchingEnvironment environment, Function<Field, String> alias) {
        Field field = environment.getField().deepCopy();
        field.setAlias(alias.apply(field));
        return field;
    }


    private static ExecutionInput executeBatchQuery(Document doc, String operationName, Map<String, Object> variables) {
        return ExecutionInput.newExecutionInput()
                .query(printNode(doc))
                .operationName(operationName)
                .variables(variables)
                .build();
    }

    private static List<DataFetcherResult<Object>> transformBatchResultIntoResultList(
            List<DataFetchingEnvironment> environments,
            Map<DataFetchingEnvironment, List<FieldKey>> clonedFields,
            DataFetcherResult<Map<FieldKey, Object>> result) {
        List<DataFetcherResult<Object>> queryResults = new ArrayList<>();
        Map<FieldKey, Object> data = result.getData();
        for (DataFetchingEnvironment environment : environments) {
            List<FieldKey> fields = clonedFields.get(environment);
            Object fieldData;

            if (!fields.isEmpty()) {
                FieldKey field = fields.get(0);
                fieldData = BraidObjects.cast(data.getOrDefault(field, null));

                if (environment.getFieldType() instanceof GraphQLList && !(fieldData instanceof List)) {
                    fieldData = fields.stream()
                            .map(f -> BraidObjects.cast(data.getOrDefault(f, null)))
                            .collect(toList());
                } else if (fields.size() > 1) {
                    throw new IllegalStateException("Can't query for multiple fields if the target type isn't a list");
                }
                queryResults.add(new DataFetcherResult<>(
                        fieldData,
                        buildDataFetcherResultErrors(result, fields)
                ));
            } else if (environment.getSource() instanceof Map &&
                    environment.<Map<String, Object>>getSource().get(environment.getFieldDefinition().getName()) instanceof List) {
                queryResults.add(new DataFetcherResult<>(
                        emptyList(),
                        buildDataFetcherResultErrors(result, fields)
                ));
            } else {
                queryResults.add(new DataFetcherResult<>(
                        null,
                        buildDataFetcherResultErrors(result, fields)
                ));
            }
        }
        return queryResults;
    }

    private static List<GraphQLError> buildDataFetcherResultErrors(DataFetcherResult<Map<FieldKey, Object>> result, List<FieldKey> fields) {
        return result.getErrors().stream()
                .filter(e -> e.getPath() == null || e.getPath().isEmpty()
                        || fields.contains(new FieldKey(String.valueOf(e.getPath().get(0)))))
                .map(RelativeGraphQLError::new)
                .collect(toList());
    }

    private static Type findArgumentType(SchemaSource schemaSource, Link link) {
        return findQueryFieldDefinitions(schemaSource.getPrivateSchema())
                .orElseThrow(IllegalStateException::new)
                .stream()
                .filter(f -> f.getName().equals(link.getTargetQueryField()))
                .findFirst()
                .map(f -> f.getInputValueDefinitions().stream()
                        .filter(iv -> iv.getName().equals(link.getArgumentName()))
                        .findFirst()
                        .map(InputValueDefinition::getType)
                        .orElseThrow(IllegalArgumentException::new))
                .orElseThrow(IllegalArgumentException::new);
    }


    private static Predicate<Field> isFieldMatchingFieldDefinition(GraphQLFieldDefinition fieldDefinition) {
        return field -> Objects.equals(fieldDefinition.getName(), field.getName());
    }

    /**
     * Ensures we only ask for fields the data source supports
     */
    static void trimFieldSelection(SchemaSource schemaSource, DataFetchingEnvironment environment, AbstractNode root) {
        new GraphQLQueryVisitor() {
            private GraphQLOutputType parentType = null;
            private GraphQLOutputType lastFieldType = null;


            @Override
            protected void visitFragmentDefinition(FragmentDefinition node) {
                if (node == root) {
                    this.parentType = this.lastFieldType = getFragmentOutputType(environment, node::getTypeCondition);
                }
                super.visitFragmentDefinition(node);
            }

            @Override
            protected void visitField(Field node) {
                GraphQLType type;
                if (node == root) {
                    final Field field = (Field) root;
                    type = environment.getFieldType();
                    parentType = (GraphQLObjectType) environment.getParentType();
                    Optional<Link> linkWithDifferentFromField = getLinkWithDifferentFromField(schemaSource.getLinks(), parentType.getName(), field.getName());
                    if (linkWithDifferentFromField.isPresent() && environment.getSource() == null) {
                        field.setSelectionSet(null);
                        field.setName(linkWithDifferentFromField.get().getSourceFromField());
                    }
                } else {
                    getLink(schemaSource.getLinks(), parentType.getName(), node.getName())
                            .ifPresent(l -> node.setSelectionSet(null));

                    if (isTypeNameMetaField(node)) {
                        type = TypeNameMetaFieldDef.getType();
                    } else if (parentType instanceof GraphQLFieldsContainer) {
                        type = GraphQLFieldsContainer.class.cast(parentType).getFieldDefinition(node.getName()).getType();
                    } else {
                        throw new IllegalStateException(
                                format("Could not find definition for field %s, with parent of type: %s",
                                        node.getName(), parentType));
                    }
                }

                while (type instanceof GraphQLModifiedType) {
                    type = ((GraphQLModifiedType) type).getWrappedType();
                }
                lastFieldType = (GraphQLOutputType) type;
                super.visitField(node);
            }

            @Override
            protected void visitInlineFragment(InlineFragment node) {
                this.parentType = this.lastFieldType = getFragmentOutputType(environment, node::getTypeCondition);
                super.visitInlineFragment(node);
            }

            @Override
            protected void visitSelectionSet(final SelectionSet node) {
                if (node == null) {
                    return;
                }

                if (!node.getChildren().isEmpty()) {
                    GraphQLOutputType lastParentType = parentType;
                    parentType = lastFieldType;
                    for (final Node child : node.getChildren()) {

                        // process child to handle cases where the source from root is different than the source root
                        if (child instanceof Field) {
                            Optional<Link> linkWithDifferentFromField = getLinkWithDifferentFromField(schemaSource.getLinks(), parentType.getName(), ((Field) child).getName());
                            if (linkWithDifferentFromField.isPresent()) {
                                removeSourceFieldIfDifferentThanFromField(node, linkWithDifferentFromField.get());
                                addFromFieldToQueryIfMissing(node, linkWithDifferentFromField.get());
                            }
                        }
                        visit(child);
                    }
                    parentType = lastParentType;
                }
            }

            private void addFromFieldToQueryIfMissing(SelectionSet node, Link link) {
                Optional<Selection> fromField = node.getSelections().stream()
                        .filter(s -> s instanceof Field
                                && ((Field) s).getName().equals(link.getSourceFromField()))
                        .findFirst();
                if (!fromField.isPresent()) {
                    node.getSelections().add(new Field(link.getSourceFromField()));
                }
            }

            private void removeSourceFieldIfDifferentThanFromField(SelectionSet node, Link link) {
                node.getSelections().stream()
                        .filter(s -> s instanceof Field
                                && ((Field) s).getName().equals(link.getSourceField()))
                        .findAny()
                        .ifPresent(s -> node.getSelections().remove(s));
            }
        }.visit(root);
    }

    private static boolean isTypeNameMetaField(Field node) {
        return isFieldMatchingFieldDefinition(TypeNameMetaFieldDef).test(node);
    }

    private static GraphQLOutputType getFragmentOutputType(DataFetchingEnvironment env, Supplier<TypeName> getTypeCondition) {
        final GraphQLType type = env.getGraphQLSchema()
                .getTypeMap()
                .get(getTypeCondition.get().getName());

        if (!(type instanceof GraphQLOutputType)) {
            throw new IllegalStateException("Unexpected GraphQL type: " + type.getClass());
        }
        return GraphQLOutputType.class.cast(type);
    }

    /**
     * Ensures any referenced fragments are included in the query
     */
    static Collection<Definition> processForFragments(SchemaSource source, DataFetchingEnvironment environment, Field field) {
        Map<String, Definition> result = new HashMap<>();
        new GraphQLQueryVisitor() {
            @Override
            protected void visitFragmentSpread(FragmentSpread node) {
                FragmentDefinition fragmentDefinition = environment.getFragmentsByName().get(node.getName()).deepCopy();
                trimFieldSelection(source, environment, fragmentDefinition);
                result.put(node.getName(), fragmentDefinition);
                super.visitFragmentSpread(node);
            }
        }.visit(field);
        return result.values();
    }

    private static Optional<Link> getLink(Collection<Link> links, String typeName, String fieldName) {
        return links.stream()
                .filter(l -> l.getSourceType().equals(typeName))
                .filter(l -> l.getSourceFromField().equals(fieldName))
                .findFirst();
    }

    private static Optional<Link> getLinkWithDifferentFromField(Collection<Link> links, String typeName, String fieldName) {
        return links.stream()
                .filter(l -> l.getSourceType().equals(typeName)
                        && l.getSourceField().equals(fieldName)
                        && !l.getSourceFromField().equals(fieldName))
                .findFirst();
    }


    private static class VariableNamespacingGraphQLQueryVisitor extends GraphQLQueryVisitor {
        private final int counter;
        private final OperationDefinition queryType;
        private final Map<String, Object> variables;
        private final DataFetchingEnvironment environment;
        private final OperationDefinition queryOp;

        VariableNamespacingGraphQLQueryVisitor(int counter,
                                               OperationDefinition operationDefinition,
                                               Map<String, Object> variables,
                                               DataFetchingEnvironment environment,
                                               OperationDefinition queryOp) {
            this.counter = counter;
            this.queryType = operationDefinition;
            this.variables = variables;
            this.environment = environment;
            this.queryOp = queryOp;
        }

        @Override
        protected void visitField(Field node) {
            node.setArguments(node.getArguments().stream().map(this::namespaceReferences).collect(toList()));
            node.setDirectives(node.getDirectives().stream().map(this::namespaceReferences).collect(toList()));
            super.visitField(node);
        }

        private Argument namespaceReferences(Argument arg) {
            return new Argument(arg.getName(), namespaceReferences(arg.getValue()));
        }

        private Directive namespaceReferences(Directive original) {
            return new Directive(original.getName(), original.getArguments().stream().map(this::namespaceReferences).collect(toList()));
        }

        private Value namespaceReferences(Value value) {
            final Value transformedValue;
            if (value instanceof VariableReference) {
                transformedValue = maybeNamespaceReference((VariableReference) value);
            } else if (value instanceof ObjectValue) {
                transformedValue = namespaceReferencesForObjectValue((ObjectValue) value);
            } else if (value instanceof ArrayValue) {
                transformedValue = namespaceReferencesForArrayValue((ArrayValue) value);
            } else {
                transformedValue = value;
            }
            return transformedValue;
        }

        private ObjectValue namespaceReferencesForObjectValue(ObjectValue value) {
            return new ObjectValue(
                    value.getChildren().stream()
                            .map(ObjectField.class::cast)
                            .map(o -> new ObjectField(o.getName(), namespaceReferences(o.getValue())))
                            .collect(toList()));
        }

        private ArrayValue namespaceReferencesForArrayValue(ArrayValue value) {
            return new ArrayValue(
                    value.getChildren().stream()
                            .map(Value.class::cast)
                            .map(this::namespaceReferences)
                            .collect(toList()));
        }

        private VariableReference maybeNamespaceReference(VariableReference value) {
            return isVariableAlreadyNamespaced(value) ? value : namespaceVariable(value);
        }

        private VariableReference namespaceVariable(VariableReference varRef) {
            final String newName = varRef.getName() + counter;

            final VariableReference value = new VariableReference(newName);
            final Type type = findVariableType(varRef, queryType);

            variables.put(newName, environment.<BraidContext>getContext().getExecutionContext().getVariables().get(varRef.getName()));
            queryOp.getVariableDefinitions().add(new VariableDefinition(newName, type));
            return value;
        }

        private boolean isVariableAlreadyNamespaced(VariableReference varRef) {
            return varRef.getName().endsWith(String.valueOf(counter));
        }

        private static Type findVariableType(VariableReference varRef, OperationDefinition queryType) {
            return queryType.getVariableDefinitions()
                    .stream()
                    .filter(d -> d.getName().equals(varRef.getName()))
                    .map(VariableDefinition::getType)
                    .findFirst()
                    .orElseThrow(IllegalArgumentException::new);
        }
    }

    private static class FieldRequest {
        private final Field field;
        private final int counter;

        private FieldRequest(Field field, int counter) {
            this.field = field;
            this.counter = counter;
        }
    }

    // The unique key of a id that is being requested
    private static class FieldKey {
        private final String value;

        private FieldKey(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FieldKey fieldKey = (FieldKey) o;
            return Objects.equals(value, fieldKey.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }
    }
}
