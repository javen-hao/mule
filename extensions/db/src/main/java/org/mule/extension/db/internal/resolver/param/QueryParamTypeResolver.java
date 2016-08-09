/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.extension.db.internal.resolver.param;

import org.mule.extension.db.internal.domain.connection.DbConnection;
import org.mule.extension.db.internal.domain.query.Query;
import org.mule.extension.db.internal.domain.type.DbType;
import org.mule.extension.db.internal.domain.type.DbTypeManager;
import org.mule.extension.db.internal.domain.type.ResolvedDbType;
import org.mule.extension.db.internal.domain.type.UnknownDbType;
import org.mule.extension.db.internal.domain.type.UnknownDbTypeException;

import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves parameter types for standard queries
 */
public class QueryParamTypeResolver implements ParamTypeResolver
{

    private final DbTypeManager dbTypeManager;

    public QueryParamTypeResolver(DbTypeManager dbTypeManager)
    {
        this.dbTypeManager = dbTypeManager;
    }

    @Override
    public Map<Integer, DbType> getParameterTypes(DbConnection connection, Query query) throws SQLException
    {
        Map<Integer, DbType> paramTypes = new HashMap<>();

        PreparedStatement statement = connection.prepareStatement(query.getDefinition().getSql());

        ParameterMetaData parameterMetaData = statement.getParameterMetaData();

        for (int index = 1; index <= query.getDefinition().getParameters().size(); index++)
        {
            int parameterTypeId = parameterMetaData.getParameterType(index);
            String parameterTypeName = parameterMetaData.getParameterTypeName(index);
            DbType dbType;
            if (parameterTypeName == null)
            {
                // Use unknown data type
                dbType = UnknownDbType.getInstance();
            }
            else
            {
                try
                {
                    dbType = dbTypeManager.lookup(connection, parameterTypeId, parameterTypeName);
                }
                catch (UnknownDbTypeException e)
                {
                    // Type was not found in the type manager, but the DB knows about it
                    dbType = new ResolvedDbType(parameterTypeId, parameterTypeName);
                }
            }

            paramTypes.put(index, dbType);
            index++;
        }

        return paramTypes;
    }
}
