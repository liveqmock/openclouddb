/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese 
 * opensource volunteers. you can redistribute it and/or modify it under the 
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 * 
 * Any questions about this component can be directed to it's project Web address 
 * https://code.google.com/p/opencloudb/.
 *
 */
package com.akiban.sql.parser;

/**
 * An UpdateNode represents an UPDATE statement.    It is the top node of the
 * query tree for that statement.
 * For positioned update, there may be no from table specified.
 * The from table will be derived from the cursor specification of
 * the named cursor.
 *
 */

public final class UpdateNode extends DMLModStatementNode
{

    /**
     * Initializer for an UpdateNode.
     *
     * @param targetTableName The name of the table to update
     * @param resultSet The ResultSet that will generate
     *                                  the rows to update from the given table
     */

    public void init(Object targetTableName,
                     Object resultSet,
                     Object returningList) {
        super.init(resultSet);
        this.targetTableName = (TableName)targetTableName;
        this.returningColumnList = (ResultColumnList)returningList;
    }

    public String statementToString() {
        return "UPDATE";
    }
    
    /**
     * Return the type of statement, something from
     * StatementType.
     *
     * @return the type of statement
     */
    protected final int getStatementType() {
        return StatementType.UPDATE;
    }

}