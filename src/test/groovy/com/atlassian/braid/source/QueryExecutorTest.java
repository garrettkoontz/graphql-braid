package com.atlassian.braid.source;


import com.atlassian.braid.SchemaSource;
import com.google.common.collect.ImmutableMap;
import graphql.language.Argument;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.OperationDefinition;
import graphql.language.SelectionSet;
import graphql.language.StringValue;
import graphql.language.TypeName;
import graphql.parser.Parser;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static com.atlassian.braid.Util.parseRegistry;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class QueryExecutorTest {

    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    @Mock
    private QueryFunction<?> queryFunction;

    @Mock
    private SchemaSource source;

    @Mock
    private DataFetchingEnvironment env;

    @Test
    public void testTrimFields() {
        when(source.getLinks()).thenReturn(emptyList());

        TypeDefinitionRegistry registry = parseRegistry("/com/atlassian/braid/not-null-fields.graphql");
        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, RuntimeWiring.newRuntimeWiring().build());
        when(env.getFieldType()).thenReturn(schema.getObjectType("Bar"));
        when(env.getParentType()).thenReturn(schema.getObjectType("Blah"));
        QueryExecutor<?> queryExecutor = new QueryExecutor<>(queryFunction);

        Document query = new Parser().parseDocument("query {foo(id:fooid){id, title, baz, mylist {name}}}");
        Field fooField = (Field) ((OperationDefinition) query.getDefinitions().get(0)).getSelectionSet().getSelections().stream().filter(d -> d instanceof Field && ((Field) d).getName().equals("foo")).findFirst().get();
        queryExecutor.trimFieldSelection(source, env, fooField);
    }

    @Test
    public void testProcessFragments() {
        FragmentDefinition frag = new FragmentDefinition("Frag", new TypeName("OnType"));
        frag.setSelectionSet(new SelectionSet(singletonList(new Field("foo", singletonList(new Argument("id", new StringValue("fooid")))))));
        when(env.getFragmentsByName()).thenReturn(singletonMap("Frag", frag));
        final GraphQLSchema mockSchema = mock(GraphQLSchema.class);
        final GraphQLObjectType fragmentDefinitionOutputType = mock(GraphQLObjectType.class);
        final GraphQLFieldDefinition mock = mock(GraphQLFieldDefinition.class);
        when(fragmentDefinitionOutputType.getName()).thenReturn("OnType");
        when(fragmentDefinitionOutputType.getFieldDefinition(anyString())).thenReturn(mock);
        when(env.getGraphQLSchema()).thenReturn(mockSchema);
        when(mockSchema.getTypeMap()).thenReturn(ImmutableMap.of("OnType", fragmentDefinitionOutputType));
        Field parent = new Field("parent", new SelectionSet(singletonList(new FragmentSpread("Frag"))));

        QueryExecutor<?> queryExecutor = new QueryExecutor<>(queryFunction);
        FragmentDefinition clonedFrag = (FragmentDefinition) queryExecutor.processForFragments(source, env, parent).iterator().next();
        assertEquals(clonedFrag.getName(), frag.getName());
        assertEquals(clonedFrag.toString(), frag.toString());
        assertTrue(clonedFrag != frag);
    }
}
