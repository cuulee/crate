/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.crate.analyze.WhereClause;
import io.crate.exceptions.UnauthorizedException;
import io.crate.metadata.doc.DocSchemaInfoFactory;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.table.Operation;
import io.crate.metadata.sys.SysSchemaInfo;
import io.crate.metadata.table.SchemaInfo;
import io.crate.metadata.table.StaticTableInfo;
import io.crate.metadata.table.TableInfo;
import io.crate.operation.udf.UserDefinedFunctionMetaData;
import io.crate.operation.udf.UserDefinedFunctionsMetaData;
import io.crate.operation.user.User;
import io.crate.types.DataTypes;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SchemasTest {

    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Mock
    public ClusterService clusterService;

    @Mock
    public ClusterState clusterState;

    @Mock
    public MetaData metaData;

    private Schemas schemasWithSysTables;

    private TableIdent authorized = new TableIdent("sys", "authorized");

    private TableIdent unauthorized = new TableIdent("sys", "checks");

    private User arthur = new User("arthur", ImmutableSet.of());

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        when(clusterService.state()).thenReturn(clusterState);
        when(clusterState.metaData()).thenReturn(metaData);
        when(metaData.getConcreteAllOpenIndices()).thenReturn(new String[0]);
        when(metaData.templates()).thenReturn(ImmutableOpenMap.<String, IndexTemplateMetaData>of());
        SysSchemaInfo sysSchemaInfo = new SysSchemaInfo(clusterService);

        // register table which requires permission
        sysSchemaInfo.registerSysTable(new StaticTableInfo(authorized,
            ImmutableMap.of(), null, ImmutableList.of()) {

            @Override
            public RowGranularity rowGranularity() {
                return RowGranularity.DOC;
            }

            @Override
            public Routing getRouting(WhereClause whereClause, @Nullable String preference) {
                return null;
            }

            @Override
            public Set<User.Role> requiredUserRoles() {
                return ImmutableSet.of(User.Role.SUPERUSER);
            }
        });
        schemasWithSysTables = new Schemas(Settings.EMPTY, ImmutableMap.of(
            "sys", sysSchemaInfo
        ), null, null);
    }

    @Test
    public void testSystemSchemaIsNotWritable() throws Exception {
        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("The relation \"foo.bar\" doesn't support or allow INSERT " +
                                        "operations, as it is read-only.");

        TableIdent tableIdent = new TableIdent("foo", "bar");
        SchemaInfo schemaInfo = mock(SchemaInfo.class);
        TableInfo tableInfo = mock(TableInfo.class);
        when(tableInfo.ident()).thenReturn(tableIdent);
        when(tableInfo.supportedOperations()).thenReturn(Operation.SYS_READ_ONLY);
        when(schemaInfo.getTableInfo(tableIdent.name())).thenReturn(tableInfo);
        when(schemaInfo.name()).thenReturn(tableIdent.schema());

        Schemas schemas = getReferenceInfos(schemaInfo);
        schemas.getTableInfo(tableIdent, Operation.INSERT, null);
    }

    @Test
    public void testTableAliasIsNotWritable() throws Exception {
        expectedException.expect(UnsupportedOperationException.class);
        expectedException.expectMessage("The relation \"foo.bar\" doesn't support or allow INSERT operations.");

        TableIdent tableIdent = new TableIdent("foo", "bar");
        SchemaInfo schemaInfo = mock(SchemaInfo.class);
        DocTableInfo tableInfo = mock(DocTableInfo.class);
        when(tableInfo.ident()).thenReturn(tableIdent);
        when(schemaInfo.getTableInfo(tableIdent.name())).thenReturn(tableInfo);
        when(schemaInfo.name()).thenReturn(tableIdent.schema());
        when(tableInfo.isAlias()).thenReturn(true);


        Schemas schemas = getReferenceInfos(schemaInfo);
        schemas.getTableInfo(tableIdent, Operation.INSERT, null);
    }

    @Test
    public void testAuthorizationRequiredMissingRoles() throws Exception {
        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage(
            "User \"arthur\" is not authorized to access table \"sys.authorized\"");
        schemasWithSysTables.getTableInfo(authorized, arthur);
    }

    @Test
    public void testAuthorizationRequiredNullUser() throws Exception {
        expectedException.expect(UnauthorizedException.class);
        expectedException.expectMessage(
            "User \"null\" is not authorized to access table \"sys.authorized\"");
        schemasWithSysTables.getTableInfo(authorized, null);
    }

    @Test
    public void testNoAuthorizationRequiredNormalUser() throws Exception {
        TableInfo info = schemasWithSysTables.getTableInfo(unauthorized, arthur);
        assertThat(info, is(notNullValue()));
    }

    @Test
    public void testAuthorizationRequiredAuthorizedUser() throws Exception {
        TableInfo info = schemasWithSysTables.getTableInfo(authorized,
            new User("superuser", EnumSet.of(User.Role.SUPERUSER)));
        assertThat(info, is(notNullValue()));
    }

    @Test
    public void testNoAuthorizationRequiredNullUser() throws Exception {
        TableInfo info = schemasWithSysTables.getTableInfo(unauthorized, null);
        assertThat(info, is(notNullValue()));
    }

    @Test
    public void testSchemasFromUDF() throws Exception {
        MetaData metaData = MetaData.builder()
            .putCustom(
                UserDefinedFunctionsMetaData.TYPE,
                UserDefinedFunctionsMetaData.of(
                    new UserDefinedFunctionMetaData("new_schema", "my_function", ImmutableList.of(), DataTypes.STRING,
                        "burlesque", "Hello, World!Q")
                )
            ).build();
        assertThat(Schemas.getNewCurrentSchemas(metaData), contains("new_schema"));
    }

    private Schemas getReferenceInfos(SchemaInfo schemaInfo) {
        Map<String, SchemaInfo> builtInSchema = new HashMap<>();
        builtInSchema.put(schemaInfo.name(), schemaInfo);
        return new Schemas(Settings.EMPTY, builtInSchema, clusterService, mock(DocSchemaInfoFactory.class));
    }
}
