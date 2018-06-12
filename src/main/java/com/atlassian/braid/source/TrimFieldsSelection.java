package com.atlassian.braid.source;

import com.atlassian.braid.GraphQLQueryVisitor;
import com.atlassian.braid.Link;
import com.atlassian.braid.SchemaSource;
import graphql.language.AbstractNode;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.InlineFragment;
import graphql.language.Node;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeName;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLModifiedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static graphql.introspection.Introspection.TypeNameMetaFieldDef;
import static java.lang.String.format;

public class TrimFieldsSelection {


    public static void trimFieldSelection(SchemaSource schemaSource, DataFetchingEnvironment environment, AbstractNode root) {
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

    private static Predicate<Field> isFieldMatchingFieldDefinition(GraphQLFieldDefinition fieldDefinition) {
        return field -> Objects.equals(fieldDefinition.getName(), field.getName());
    }

    private static Optional<Link> getLinkWithDifferentFromField(Collection<Link> links, String typeName, String fieldName) {
        return links.stream()
                .filter(l -> l.getSourceType().equals(typeName)
                        && l.getSourceField().equals(fieldName)
                        && !l.getSourceFromField().equals(fieldName))
                .findFirst();
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

    private static Optional<Link> getLink(Collection<Link> links, String typeName, String fieldName) {
        return links.stream()
                .filter(l -> l.getSourceType().equals(typeName))
                .filter(l -> l.getSourceFromField().equals(fieldName))
                .findFirst();
    }

}

