package com.atlassian.braid.source;

import com.atlassian.braid.BraidContext;
import com.atlassian.braid.GraphQLQueryVisitor;
import graphql.language.Argument;
import graphql.language.ArrayValue;
import graphql.language.Directive;
import graphql.language.Field;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.OperationDefinition;
import graphql.language.Type;
import graphql.language.Value;
import graphql.language.VariableDefinition;
import graphql.language.VariableReference;
import graphql.schema.DataFetchingEnvironment;

import java.util.Map;

import static java.util.stream.Collectors.toList;

public class VariableNamespacingGraphQLQueryVisitor extends GraphQLQueryVisitor {
    private final int counter;
    private final OperationDefinition queryType;
    private final Map<String, Object> variables;
    private final DataFetchingEnvironment environment;
    private final OperationDefinition queryOp;

    public VariableNamespacingGraphQLQueryVisitor(int counter,
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
