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

import com.akiban.sql.StandardException;
import com.akiban.sql.types.AliasInfo;
import com.akiban.sql.types.RoutineAliasInfo;
import com.akiban.sql.types.CharacterTypeAttributes;
import com.akiban.sql.types.DataTypeDescriptor;
import com.akiban.sql.types.TypeId;

import java.sql.ParameterMetaData;

import java.sql.Types;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.*;

class SQLGrammar implements SQLGrammarConstants {

    /* SAVEPOINT variations */
    // TODO: I think these should be constants in the savepoint node. ***
    private static final int SAVEPOINT_UNIQUE = 0;
    private static final int SAVEPOINT_RETAIN_LOCKS = 1;
    private static final int SAVEPOINT_RETAIN_CURSORS = 2;
    private static final int SAVEPOINT_NCLAUSES = 3;

    /* Corresponding clauses for error messages. */
    private static final String[] SAVEPOINT_CLAUSE_NAMES = {
        "UNIQUE", "ON ROLLBACK RETAIN LOCKS", "ON ROLLBACK RETAIN CURSORS"
    };

    /* Keep in synch with CreateAliasNode's index constants */
    private static final String[] ROUTINE_CLAUSE_NAMES = { null,
        "SPECIFIC",
        "RESULT SET",
        "LANGUAGE",
        "EXTERNAL NAME",
        "PARAMETER STYLE",
        "SQL",
        "DETERMINISTIC",
        "ON NULL INPUT",
        "RETURN TYPE",
        "EXTERNAL SECURITY",
        "AS",
    };

    /* Possible clauses to CREATE TEMPORARY TABLE. */
    private static final int TEMPORARY_TABLE_NOT_LOGGED = 0;
    private static final int TEMPORARY_TABLE_ON_COMMIT = 1;
    private static final int TEMPORARY_TABLE_ON_ROLLBACK = 2;
    private static final int TEMPORARY_TABLE_NCLAUSES = 3;

    /* Corresponding clauses for error messages. */
    private static final String[] TEMPORARY_TABLE_CLAUSE_NAMES = {
        "NOT LOGGED", "ON COMMIT", "ON ROLLBACK"
    };

    /* Possible JOIN clauses. */
    private static final int JOIN_ON = 0;
    private static final int JOIN_USING = 1;
    private static final int JOIN_NCLAUSES = 2;

    /* Possible TABLE optional clauses. */
    private static final int OPTIONAL_TABLE_PROPERTIES = 0;
    private static final int OPTIONAL_TABLE_DERIVED_RCL = 1;
    private static final int OPTIONAL_TABLE_CORRELATION_NAME = 2;
    private static final int OPTIONAL_TABLE_MYSQL_INDEX_HINTS = 3;
    private static final int OPTIONAL_TABLE_NCLAUSES = 4;

    /* Possible CREATE SEQUENCE optional clauses. */
    private static final int SEQUENCE_DATA_TYPE = 0;
    private static final int SEQUENCE_START_WITH = 1;
    private static final int SEQUENCE_INCREMENT_BY = 2;
    private static final int SEQUENCE_MAX_VALUE = 3;
    private static final int SEQUENCE_MIN_VALUE = 4;
    private static final int SEQUENCE_CYCLE = 5;
    private static final int SEQUENCE_NCLAUSES = 6;

    /* Constants for set operator types. */
    private static final int NO_SET_OP = 0;
    private static final int UNION_OP = 1;
    private static final int UNION_ALL_OP = 2;
    private static final int EXCEPT_OP = 3;
    private static final int EXCEPT_ALL_OP = 4;
    private static final int INTERSECT_OP = 5;
    private static final int INTERSECT_ALL_OP = 6;

    /* The default length of a [VAR]CHAR or BIT if the length is omitted. */
    private static final int DEFAULT_STRING_COLUMN_LENGTH = 1;

    /* TODO: Is there any need for these limits? */
    public static final int MAX_DECIMAL_PRECISION_SCALE = TypeId.DECIMAL_PRECISION;
    public static final int DEFAULT_DECIMAL_PRECISION = TypeId.DEFAULT_DECIMAL_PRECISION;
    public static final int DEFAULT_DECIMAL_SCALE = TypeId.DEFAULT_DECIMAL_SCALE;
    public static final int MAX_FLOATINGPOINT_LITERAL_LENGTH = 30;

    /* Different kinds of string delimiters. */
    static final String SINGLEQUOTES = "\u005c'\u005c'";
    static final String DOUBLEQUOTES = "\u005c"\u005c"";
    static final String BACKQUOTES = "``";

    // TODO: Probably not right.
    static final String IBM_SYSTEM_FUN_SCHEMA_NAME = "SYSFUN";

    /**
     * The system authorization ID is defined by the SQL2003 spec as the grantor
     * of privileges to object owners.
     */
    public static final String SYSTEM_AUTHORIZATION_ID = "_SYSTEM";

    /**
     * The public authorization ID is defined by the SQL2003 spec as implying all users.
     */
    public static final String PUBLIC_AUTHORIZATION_ID = "PUBLIC";

    /* The owner / user-visible parser. */
    private SQLParserContext parserContext;

    /* Creator of AST nodes. */
    private NodeFactory nodeFactory;

    /* The statement being parsed. */
    private String statementSQLText;

    /* The number of the next ? parameter */
    private int parameterNumber;

    /* The list of ? parameters */
    private List<ParameterNode> parameterList;

    /* Remember if the last identifier or keyword was a delimited identifier.
         This is used for Java references. */
    private Boolean lastTokenDelimitedIdentifier = Boolean.FALSE,
                    nextToLastTokenDelimitedIdentifier = Boolean.FALSE;

    /* Remember the last token we got that was an identifier. */
    private Token lastIdentifierToken, nextToLastIdentifierToken;

    private DataTypeDescriptor getType(int type, int precision, int scale, int length)
            throws StandardException {
        return new DataTypeDescriptor(TypeId.getBuiltInTypeId(type),
                                      precision,
                                      scale,
                                      true, /* assume nullable for now, change it if not nullable */
                                      length);
    }

    private DataTypeDescriptor getJavaClassDataTypeDescriptor(TableName typeName)
            throws StandardException {
        return new DataTypeDescriptor(TypeId.getUserDefinedTypeId(typeName.getSchemaName(),
                                                                  typeName.getTableName(),
                                                                  null),
                                      true);
    }

    private void forbidNextValueFor() {
        // TODO: Needed for bind phase.
    }

    /**
     * Check to see if the required claues have been added to a
     * procedure or function defintion.
     *   
     * @param clauses the array of declared clauses.
    */
    private void checkRequiredRoutineClause(Object[] clauses)
            throws StandardException {
        String language = (String)clauses[CreateAliasNode.LANGUAGE];
        if (language == null) {
            throw new StandardException("Missing required " + ROUTINE_CLAUSE_NAMES[CreateAliasNode.LANGUAGE]);
        }
        int[] required;
        if (language.equalsIgnoreCase("JAVA")) {
            required = new int[] {
                CreateAliasNode.PARAMETER_STYLE,
                CreateAliasNode.EXTERNAL_NAME
            };
        }
        else {
            required = new int[] {
                CreateAliasNode.PARAMETER_STYLE,
                CreateAliasNode.INLINE_DEFINITION
            };
        }
        for (int i = 0; i < required.length; i++) {
            int re = required[i];
            if (clauses[re] == null) {
                throw new StandardException("Missing required " + ROUTINE_CLAUSE_NAMES[re]);
            }
        }
    }

    /**
     * Remove first and last quotes and compress adjacent ones in the
     * middle.
     */
    // TODO: Need to support backslash escaping for compatible double-quoted string.
    // TODO: This looks pretty inefficient.
    private static String trimAndCompressQuotes(String source, String quotes, boolean backslash) {
        String result = source.substring(1, source.length() - 1);

        /* Find the first occurrence of adjacent quotes. */
        int index = result.indexOf(quotes);

        /* Replace each occurrence with a single quote and begin the
         * search for the next occurrence from where we left off.
         */
        while (index != -1) {
            result = result.substring(0, index + 1) + result.substring(index + 2);
            index = result.indexOf(quotes, index + 1);
        }

        return result;
    }

    private String sliceSQLText(int beginOffset, int endOffset, boolean trim) {
        // NOTE: endOffset is inclusive.
        String retval = statementSQLText.substring(beginOffset, endOffset + 1);

        if (trim)
            retval = retval.trim();

        return retval;
    }

    private String SQLToIdentifierCase(String s) {
        switch (parserContext.getIdentifierCase()) {
        case UPPER:
            // Always use the ENGLISH locale.
            return s.toUpperCase(Locale.ENGLISH);
        case LOWER:
            return s.toLowerCase(Locale.ENGLISH);
        case PRESERVE:
        default:
            return s;
        }
    }

    /** Is the given feature enabled for this parser? */
    public boolean hasFeature(SQLParserFeature feature) {
        return parserContext.hasFeature(feature);
    }

    /**
     * Is this token a date / time function name?
     */
    private static boolean isDATETIME(int tokKind) {
        if (tokKind == DATE || tokKind == TIME || tokKind == TIMESTAMP)
            return true;
        else
            return false;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of an aggregateNode()() rule.    aggregateNodes() start with one
     * of the built-in aggregate names, or with an identifier followed
     * by "( DISTINCT". A non-distinct user-defined aggregate invocation
     * is treated as a staticMethodInvocationAlias() by the parser,
     * and the binding phase figures out what it really is by looking
     * at the data dictionary.
     *
     * We check only for the punctuation here, because identifiers are
     * very hard to check for in semantic lookahead.
     *
     * @return TRUE iff the next set of tokens is the beginning of a aggregateNode()
     */
    private boolean aggregateFollows() {
        boolean retval = false;

        switch (getToken(1).kind) {
        case MAX:
        case AVG:
        case MIN:
        case SUM:
        case GROUP_CONCAT:
            // This is a built-in aggregate
            retval = true;
            break;

        case COUNT:
            // COUNT is not a reserved word
            // This may eclipse use of COUNT as a function or a procedure that is probably what we want
            if (getToken(2).kind == LEFT_PAREN)
                retval = true;
        default:
            // Not a built-in aggregate - assume the first token is an
            // identifier, and see whether it is followed by " ( DISTINCT "
            if (getToken(2).kind == LEFT_PAREN && getToken(3).kind == DISTINCT)
                retval = true;
            break;
        }

        return retval;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning of a
     * window or aggregate function.
     * @return TRUE iff the next set of tokens is the beginning of a
     *                          window or aggregate function
     */
    private boolean windowOrAggregateFunctionFollows() {
        boolean retval = false;

        switch (getToken(1).kind) {
        case ROWNUMBER:

        // case RANK:
        // case DENSE_RANK:
        // case PERCENT_RANK:
        // case CUME_DIST:

            retval = true;
            break;

        default:
            retval = aggregateFollows();
            break;
        }

        return retval;
    }

    private boolean divOperatorFollows() {
        if (!hasFeature(SQLParserFeature.DIV_OPERATOR))
            return false;
        return getToken(1).kind == DIV;
    }

    private boolean mysqlLeftRightFuncFollows()
    {
        if (!hasFeature(SQLParserFeature.MYSQL_LEFT_RIGHT_FUNC))
            return false;

        switch(getToken(1).kind)
        {
            case LEFT:
            case RIGHT:
                if (getToken(2).kind == LEFT_PAREN)
                    return true;

            default:
                return false;
        }
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a miscBuiltins().
     *
     * We check only for the punctuation here, because identifiers are
     * very hard to check for in semantic lookahead.
     *
     * @return TRUE iff the next set of tokens is the beginning of a aggregateNode()
     */
    private boolean miscBuiltinFollows() {
        boolean retval = false;
        int tokKind = getToken(1).kind;

        if (getToken(0).kind == CALL)
            retval = true;

        switch (tokKind) {
        case GET_CURRENT_CONNECTION:
        case CURRENT_DATE:
        case CURRENT_TIME:
        case CURRENT_TIMESTAMP:
            retval = true;
            break;

        case CURRENT:
            if (isDATETIME(getToken(2).kind))
                retval = true;
            break;

        case CAST:
        case LEFT_PAREN:
            retval = false;
            break;

        default:
            if (getToken(2).kind == LEFT_PAREN)
                retval = true;
            break;
        }

        return retval;
    }

    /**
     * Determine whether the next tokens are a call to one of the
     * functions in miscBuiltinsCore().
     */
    private boolean miscBuiltinCoreFollows() {
        // NOTE: If you add rule to miscBuiltinsCore(), you must add the
        // appropriate token(s) here.
        switch (getToken(1).kind) {
        case LEFT:
        case RIGHT:
        case GET_CURRENT_CONNECTION:
        case ABS:
        case ABSVAL:
        case SQRT:
        case MOD:
        case COALESCE:
        case VALUE:
        case IDENTITY_VAL_LOCAL:
        case SUBSTRING:
        case SUBSTR:
        case UPPER:
        case LOWER:
        case UCASE:
        case LCASE:
        case LTRIM:
        case RTRIM:
        case TRIM:
        case DATE:
        case TIME:
        case TIMESTAMP:
        case TIMESTAMPADD:
        case TIMESTAMPDIFF:
        case DOUBLE:
        case CHAR:
        case VARCHAR:
        case INTEGER:
        case MEDIUMINT:
        case TINYINT:
        case INT:
        case SMALLINT:
        case LONGINT:
        case YEAR:
        case MONTH:
        case DAY:
        case HOUR:
        case MINUTE:
        case SECOND:
        case CHAR_LENGTH:
        case CHARACTER_LENGTH:
        case OCTET_LENGTH:
        case LENGTH:
        case LOCATE:
        case POSITION:
        case XMLPARSE:
        case XMLSERIALIZE:
        case XMLEXISTS:
        case XMLQUERY:
        case EXTRACT:
            break;
        default:
            return false;
        }
        return (getToken(2).kind == LEFT_PAREN);
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a subquery. A subquery can begin with an arbitrary number of
     * left parentheses, followed by either SELECT or VALUES.
     *
     * @return TRUE iff the next set of tokens is the beginning of a subquery.
     */
    private boolean subqueryFollows() {
        boolean retval = false;

        for (int i = 1; true; i++) {
            int tokKind = getToken(i).kind;
            if (tokKind == LEFT_PAREN) {
                // A subquery can start with an arbitrary number of left
                // parentheses.
                continue;
            }
            else if (tokKind == SELECT || tokKind == VALUES) {
                // If the first token we find after all the left parentheses
                // is SELECT or VALUES, it's a subquery.
                retval = true;
                break;
            }
            else {
                // If the first token we find after all the left parentheses
                // is neither SELECT nor VALUES, it's not a subquery.
                break;
            }
        }

        return retval;
    }

    /**
     * Check that an opening parenthese AND Subquery are the next tokens.
     * 
     * Identical to subqueryFollows(), but the function can't be used
     * because we don't want to discard the left paren before hand.
     * 
     */
    private boolean leftParenAndSubqueryFollows()
    {
        int i = 1;
        if (getToken(i++).kind != LEFT_PAREN)
            return false;

        for( ; true; ++i)
        {
            int tokKind = getToken(i).kind;
            if (tokKind == LEFT_PAREN)
            {
                // A subquery can start with an arbitrary number of left
                // parentheses.
                continue;
            }
            else if (tokKind == SELECT || tokKind == VALUES)
            {
                // If the first token we find after all the left parentheses
                // is SELECT or VALUES, it's a subquery.
                return true;
            }
            else
            {
                // If the first token we find after all the left parentheses
                // is neither SELECT nor VALUES, it's not a subquery.
                return false;
            }
        }
    }

    /**
     * Determine if we are seeing an offsetClause or the identifier OFFSET
     * (Derby does not make it a reserved word).    "n" must be an integer
     * literal or a dynamic parameter specification.
     *
     * @return true if it is an offsetClause.
     */
    private boolean seeingOffsetClause() {
        int nesting = 0;

        // Token number, i == 1: OFFSET
        int i = 2;

        int tokKind = getToken(i).kind;

        // check for integer literal or ? followed by ROW(S)
        if (tokKind == PLUS_SIGN ||
            tokKind == MINUS_SIGN) {

            tokKind = getToken(++i).kind;

            if (tokKind == EXACT_NUMERIC) {

                tokKind = getToken(++i).kind;

                return (tokKind == ROW ||
                                tokKind == ROWS);
            }
        }
        else if (tokKind == EXACT_NUMERIC ||
                 tokKind == QUESTION_MARK) {

            tokKind = getToken(++i).kind;

            return (tokKind == ROW ||
                            tokKind == ROWS);
        }

        return false;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a rowValueConstructorList. A rowValueConstructorList is a comma-
     * separated list of expressions enclosed in parentheses. This presents
     * special problems, because an expression be nested within an
     * arbitrary number of parentheses. To determine whether a left
     * parenthesis introduces a rowValueConstructorList or an expression,
     * we need to find the closing parenthesis, and determine whether
     * the next token is a comma.
     *
     * For example, the following is a rowValueConstructorList:
     *
     *  (((1)), 2)
     *
     * and the following is just an expression:
     *
     *  (((1)))
     *
     * @return TRUE iff the next set of tokens is the beginning of a subquery.
     */
    private boolean rowValueConstructorListFollows() {
        boolean retval = false;

        // A rowValueConstructorList starts with a left parenthesis
        if (getToken(1).kind == LEFT_PAREN) {
            // Keep track of the nesting of parens while looking ahead
            int nesting = 1;
            for (int i = 2; true; i++) {
                int tokKind = getToken(i).kind;

                // Special case for NULL/DEFAULT because they are not allowed in
                // a parenthesized expression, so (null)/(default) must be seen
                // as a rowValueConstructorList with one element.
                if (i == 2 && (tokKind == NULL || tokKind == _DEFAULT)) {
                    retval = true;
                    break;
                }

                // (SELECT ... ) shouldn't be considered a row-constructor
                if (tokKind == SELECT)
                    return false;

                // There must be a COMMA at nesting level 1 (i.e. outside of
                // the first expression) for it to be a rowValueConstructorList
                if (nesting == 1 && tokKind == COMMA) {
                    retval = true;
                    break;
                }

                // If we run out of tokens before finding the last closing
                // parenthesis, it's not a rowValueConstructorList (it's
                // probably a syntax error, in fact)
                if (tokKind == EOF) {
                    break;
                }

                // Increase the nesting for each (, and decrease it for each )
                if (tokKind == LEFT_PAREN) {
                    nesting++;
                }
                else if (tokKind == RIGHT_PAREN) {
                    nesting--;
                }

                // Don't look any farther than the last closing parenthesis
                if (nesting == 0) {
                    break;
                }
            }
        }

        return retval;
    }


    /**
     * Determine whether the next token is the beginning of a propertyList(). 
     * A properties list is the comment "--derby-properties" followed by a 
     * dot-separated list, followed by an =, followed by a value all on that 
     * comment line. This means that the comment should start with the word
     * "derby-properties".
     *
     * @return TRUE iff the next token is derby-properties 
     */
    private boolean derbyPropertiesListFollows() {
        return getToken(1).kind == DERBYDASHPROPERTIES;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a newInvocation(). A newInvocation() begins with the word "new"
     * followed by a dot-separated list of identifiers, followed
     * by a left parenthesis.
     *
     * @param startToken Token to look for new at
     *
     * @return TRUE iff the next set of tokens is the beginning of a newInvocation().
     */
    private boolean newInvocationFollows(int startToken) {
        boolean retval = false;

        // newInvocation() starts with the word "new"
        if (getToken(startToken).kind == NEW) {
            // Look at every other token. Ignore the identifiers, because
            // they are hard to test for.
            for (int i = 2 + startToken; true; i += 2) {
                int tokKind = getToken(i).kind;

                // If we find a left parenthesis without any intervening
                // cruft, we have found a newInvocation()
                if (tokKind == LEFT_PAREN) {
                    retval = true;
                    break;
                }
                else if (tokKind != PERIOD) {
                    // Anything other than a PERIOD is "cruft"
                    break;
                }
            }
        }

        return retval;
    }

    /**
     * Determine whether the next sequence of tokens is a class name
     *
     * @return TRUE iff the next set of tokens is the Java class name
     */
    private boolean javaClassFollows() {
        boolean retval = false;

        // Look at every other token. Ignore the identifiers, because
        // they are hard to test for.
        for (int i = 2; true; i += 2) {
            int tokKind = getToken(i).kind;

            // If we find a '::' without any intervening
            // cruft, we have found a javaClass
            if (tokKind == DOUBLE_COLON) {
                retval = true;
                break;
            }
            else if (tokKind != PERIOD) {
                // Anything other than a PERIOD is "cruft"
                break;
            }
        }

        return retval;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a FROM newInvocation(). A FROM newInvocation() begins with the words "from new"
     * followed by a dot-separated list of identifiers, followed
     * by a left parenthesis.
     *
     * @return TRUE iff the next set of tokens is the beginning of a FROM newInvocation().
     */
    private boolean fromNewInvocationFollows() {
        // FROM newInvocation() starts with the words "from new"
        return (getToken(1).kind == FROM && newInvocationFollows(2));
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a joinedTableExpression(). A joinedTableExpression() begins
     * with one of:
     *
     *  JOIN
     *  INNER JOIN
     *  CROSS JOIN
     *  LEFT OUTER JOIN
     *  RIGHT OUTER JOIN
     *  FULL OUTER JOIN
     *  NATURAL [ { RIGHT | LEFT } [ OUTER ] | INNER ] JOIN
     *
     * @return TRUE iff the next set of tokens is the beginning of a joinedTableExpression().
     */
    private boolean joinedTableExpressionFollows() {
        boolean retval = false;

        int tokKind1 = getToken(1).kind;
        int tokKind2 = getToken(2).kind;

        if (tokKind1 == JOIN) {
            retval = true;
        }
        else if (tokKind1 == INNER && tokKind2 == JOIN) {
            retval = true;
        }
        else if (tokKind1 == CROSS && tokKind2 == JOIN) {
            retval = true;
        }
        else if (tokKind1 == NATURAL) {
            retval = true;
        }
        else if ((tokKind1 == LEFT || tokKind1 == RIGHT || tokKind1 == FULL) && tokKind2 == OUTER) {
            if (getToken(3).kind == JOIN) {
                retval = true;
            }
        }
        else if ((tokKind1 == LEFT || tokKind1 == RIGHT || tokKind1 == FULL) && tokKind2 == JOIN) {
            retval = true;
        }
        else if (hasFeature(SQLParserFeature.MYSQL_HINTS) && tokKind1 == STRAIGHT_JOIN) {
            retval = true;
        }

        return retval;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of another element in a PROPERTY list. These elements are of the
     * form:
     *
     * COMMA dot.separated.list = ...
     *
     * Look for the COMMA, the dots in the dot-separated list, and the =
     *
     * @return TRUE iff the next set of tokens is the beginning of a 
     *                          another element in a PROPERTY list.
     */
    private boolean anotherPropertyFollows() {
        boolean retval = false;

        // Element must start with COMMA
        if (getToken(1).kind == COMMA) {
            // Rest of element is dot-separated list with = at end
            int i = 3;
            int tokKind;
            do {
                tokKind = getToken(i).kind;

                // If we've found nothing but PERIODs until the EQUALS_OPERATOR
                // it is the beginning of another property list element.
                if (tokKind == EQUALS_OPERATOR) {
                    retval = true;
                    break;
                }

                i += 2;
            } while (tokKind == PERIOD);
        }

        return retval;
    }

    private boolean ansiTrimSpecFollows() {
        switch (getToken(2).kind) {
        case LEADING:
        case TRAILING:
        case BOTH:
            return true;
        default:
            return false;
        }
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a remainingPredicate() rule.
     *
     * @return TRUE iff the next set of tokens is the beginning of a
     *               remainingPredicate()
     */
    private boolean remainingPredicateFollows() {
        boolean retval = false;

        switch (getToken(1).kind) {
        case EQUALS_OPERATOR:
        case NOT_EQUALS_OPERATOR:
        case NOT_EQUALS_OPERATOR2:
        case LESS_THAN_OPERATOR:
        case GREATER_THAN_OPERATOR:
        case LESS_THAN_OR_EQUALS_OPERATOR:
        case GREATER_THAN_OR_EQUALS_OPERATOR:
        case IN:
        case LIKE:
        case BETWEEN:
        case DUMMY:
            retval = true;
            break;

        case NOT:
            switch (getToken(2).kind) {
            case IN:
            case LIKE:
            case BETWEEN:
            case DUMMY:
                retval = true;
            }
            break;
        }

        return retval;
    }

    /**
     * Determine whether the next sequence of tokens can be the beginning
     * of a escapedValueFunction().
     *
     * We check only for the punctuation here, because identifiers are
     * very hard to check for in semantic lookahead.
     *
     * @return TRUE iff the next set of tokens is the beginning of a
     *               escapedValueFunction()
     */
    private boolean escapedValueFunctionFollows() {
        if (getToken(1).kind != LEFT_BRACE) {
            return false;
        }
        return getToken(2).kind == FN;
    }

    // TODO: Need to make this less dependent on engine.

    /**
     * List of JDBC escape functions that map directly onto a function
     * in the SYSFUN schema.
    */
    private static final String[] ESCAPED_SYSFUN_FUNCTIONS = {
        "ACOS", "ASIN", "ATAN", "ATAN2", "COS", "SIN", "TAN", "PI",
        "DEGREES", "RADIANS", "EXP", "LOG", "LOG10", "CEILING", "FLOOR",
        "SIGN", "RAND", "COT"
    };

    /**
         Convert a JDBC escaped function name to a function
         name in the SYSFUN schema. Returns null if no such
         function exists.
    */
    private String getEscapedSYSFUN(String name) {
        name = SQLToIdentifierCase(name);

        for (int i = 0; i < ESCAPED_SYSFUN_FUNCTIONS.length; i++) {
            if (ESCAPED_SYSFUN_FUNCTIONS[i].equals(name))
                return name;
        }
        return null;
    }

    /* Is this a keyword to GRANT / REVOKE. */
    private boolean isPrivilegeKeywordExceptTrigger(int tokenKind) {
        return (tokenKind == SELECT ||
                        tokenKind == DELETE ||
                        tokenKind == INSERT ||
                        tokenKind == UPDATE ||
                        tokenKind == REFERENCES ||
                        tokenKind == EXECUTE ||
                        tokenKind == USAGE ||
                        tokenKind == ALL);
    }

    /**
     * Determine whether the next sequence of tokens represents one of
     * the common (built-in) datatypes.
     *
     * @param checkFollowingToken true if additonal token for NATIONAL
     *              or LONG should be checked
     * @return TRUE iff the next set of tokens names a common datatype
     */
    private boolean commonDatatypeName(boolean checkFollowingToken) {
        return commonDatatypeName(1, checkFollowingToken);
    }

    /**
     * Determine whether the next sequence of tokens represents 
     * a datatype (could be a common datatype or a schema qualified UDT name).
     *
     * @return TRUE iff the next set of tokens names a datatype
     */
    private boolean dataTypeCheck(int start) {
        if (commonDatatypeName(start, false)) {
            return true;
        }

        boolean retval = true;

        switch (getToken(start).kind) {
        case COMMA:
        case LEFT_PAREN:
        case RIGHT_PAREN:
            retval = false;
            break;
        }

        return retval;
    }

    /**
     * Determine whether a sequence of tokens represents one of
     * the common (built-in) datatypes.
     *
     * @param checkFollowingToken true if additonal token for NATIONAL
     *              or LONG should be checked
     * @param start starting token index of the sequence
     * @return TRUE iff the next set of tokens names a common datatype
     */
    private boolean commonDatatypeName(int start, boolean checkFollowingToken) {
        boolean retval = false;

        switch (getToken(start).kind) {
        case CHARACTER:
        case CHAR:
        case VARCHAR:
        case NVARCHAR:
        case NCHAR:
        case BIT:
        case NUMERIC:
        case DECIMAL:
        case DEC:
        case INTEGER:
        case MEDIUMINT:
        case TINYINT:
        case INT:
        case SMALLINT:
        case LONGINT:
        case FLOAT:
        case REAL:
        case DATE:
        case TIME:
        case TIMESTAMP:
        case BOOLEAN:
        case DOUBLE:
        case BLOB:
        case CLOB:
        case NCLOB:
        case TEXT:
        case MEDIUMBLOB:
        case MEDIUMTEXT:
        case TINYBLOB:
        case TINYTEXT:
        case LONGBLOB:
        case LONGTEXT:
        case BINARY: // LARGE OBJECT
        case XML:
        case INTERVAL:
        case DATETIME:
        case YEAR:
            retval = true;
            break;

        case LONG:
            if (checkFollowingToken) {
                switch (getToken(start+1).kind) {
                case VARCHAR:
                case NVARCHAR:
                case BINARY:
                case VARBINARY:
                case BIT:
                    retval = true;
                    break;
                }
                break;
            }
            else {
                retval = true;
                break;
            }

        case NATIONAL:
            if (checkFollowingToken) {
                switch (getToken(start+1).kind) {
                case CHAR:
                case CHARACTER:
                    retval = true;
                    break;
                }
                break;
            }
            else {
                retval = true;
                break;
            }
        }

        return retval;
    }

    /*
     * Generate a multiplicative operator node, if necessary.
     *
     * If there are two operands, generate the multiplicative operator
     * that corresponds to the multiplicativeOperator parameter.    If there
     * is no left operand, just return the right operand.
     *
     * @param leftOperand The left operand, null if no operator
     * @param rightOperand The right operand
     * @param multiplicativeOperator An identifier from BinaryOperatorNode
     *              telling what operator to generate.
     *
     * @return The multiplicative operator, or the right operand if there is
     *              no operator.
     *
     * @exception StandardException Thrown on error
     */

    private ValueNode multOp(ValueNode leftOperand, ValueNode rightOperand,
                             BinaryOperatorNode.OperatorType multiplicativeOperator)
            throws StandardException {
        if (leftOperand == null) {
            return rightOperand;
        }

        switch (multiplicativeOperator) {
        case MOD:
            return (ValueNode)nodeFactory.getNode(NodeTypes.MOD_OPERATOR_NODE,
                                                  leftOperand,
                                                  rightOperand,
                                                  parserContext);
        case TIMES:
            return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_TIMES_OPERATOR_NODE,
                                                  leftOperand,
                                                  rightOperand,
                                                  parserContext);
        case DIV:
            return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_DIV_OPERATOR_NODE,
                                                  leftOperand,
                                                  rightOperand,
                                                  parserContext);
        case DIVIDE:
            return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_DIVIDE_OPERATOR_NODE,
                                                  leftOperand,
                                                  rightOperand,
                                                  parserContext);
        case CONCATENATE:
            return (ValueNode)nodeFactory.getNode(NodeTypes.CONCATENATION_OPERATOR_NODE,
                                                  leftOperand,
                                                  rightOperand,
                                                  parserContext);

        default:
            assert false : "Unexpected multiplicative operator " + multiplicativeOperator;
            return null;
        }
    }

    /**
     * Makes a new unnamed ParameterNode and chains it onto parameterList.
     *
     * @return the new unnamed parameter.
     *
     * @exception StandardException
     */
    private ParameterNode makeParameterNode(int number) throws StandardException {
        ParameterNode parm = (ParameterNode)
            nodeFactory.getNode(NodeTypes.PARAMETER_NODE,
                                number,
                                null,
                                parserContext);

        if (parameterList != null)
            parameterList.add(parm);
        return parm;
    }

    /**
     * Make string to be parsed from digits token and optional sign.
     */
    private String getNumericString(Token tok, String sign) {
        // Cannot just concatentate and parse because leading + is not
        // accepted by Java parsers. Cannot parse and negate because
        // Long.MIN_VALUE does not have a long negative.
        String num = tok.image;
        if ("-".equals(sign))
            num = sign.concat(num);
        return num;
    }

    /**
     * Translate a String containing a number into the appropriate type
     * of Numeric node.
     *
     * @param num the string containing the number
     * @param intsOnly accept only Integers (not Decimal)
     *
     * @exception Exception Thrown on error
     */
    NumericConstantNode getNumericNode(String num, boolean intsOnly)
            throws StandardException {
        // First try Integer.
        try {
            return (NumericConstantNode)nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                                            new Integer(num),
                                                            parserContext);
        }
        catch (NumberFormatException nfe) {
        }

        // Then Long.
        try {
            return (NumericConstantNode)nodeFactory.getNode(NodeTypes.LONGINT_CONSTANT_NODE,
                                                            new Long(num),
                                                            parserContext);
        }
        catch (NumberFormatException nfe) {
            if (intsOnly) {
                throw nfe;
            }
        }

        // Then Decimal.
        return (NumericConstantNode)nodeFactory.getNode(NodeTypes.DECIMAL_CONSTANT_NODE,
                                                        num,
                                                        parserContext);
    }

    // TODO: Make this less dependent on implementation.

    /**
     * Translate a token for the name of a built-in aggregate to a String
     * containing an aggregate name.
     */
    private static String aggName(Token token) {
        String retval = null;

        switch (token.kind) {
        case MAX:
            retval = "MAX";
            break;

        case AVG:
            retval = "AVG";
            break;

        case MIN:
            retval = "MIN";
            break;

        case SUM:
            retval = "SUM";
            break;

        case COUNT:
            retval = "COUNT";
            break;

        case GROUP_CONCAT:
            retval = "GROUP_CONCAT";
            break;
        default:
            assert false : "Unexpected token type in aggName: " + token.kind;
            break;
        }

        return retval;
    }

    /**
     * Translate a token for the name of a built-in aggregate to an
     * aggregate definition class.
     */
    private static String aggClass(Token token) {
        String retval = null;

        switch (token.kind) {
        case MAX:
        case MIN:
            retval = "MaxMinAggregateDefinition";
            break;

        case AVG:
        case SUM:
            retval = "SumAvgAggregateDefinition";
            break;

        case COUNT:
            retval = "CountAggregateDefinition";
            break;

        case GROUP_CONCAT:
            retval = "GroupConcatAggregateDefinition";
            break;
        default:
            assert false : "Unexpected token type in aggClass: " + token.kind;
            break;
        }

        return retval;
    }

    /**
     * Get a DELETE node given the pieces.
     *
     * @exception StandardException
     */
    StatementNode getDeleteNode(FromTable fromTable, TableName tableName,
                                ValueNode whereClause,
                                ResultColumnList returningList)
            throws StandardException {
        FromList fromList = (FromList)nodeFactory.getNode(NodeTypes.FROM_LIST,
                                                          parserContext);

        fromList.addFromTable(fromTable);

        SelectNode resultSet = (SelectNode)nodeFactory.getNode(NodeTypes.SELECT_NODE,
                                                               null,
                                                               null, /* AGGREGATE list */
                                                               fromList, /* FROM list */
                                                               whereClause, /* WHERE clause */
                                                               null, /* GROUP BY list */
                                                               null, /* having clause */
                                                               null, /* window list */
                                                               parserContext);

        StatementNode retval = (StatementNode)nodeFactory.getNode(NodeTypes.DELETE_NODE,
                                                                  tableName,
                                                                  resultSet,
                                                                  returningList,
                                                                  parserContext);

        return retval;
    }

    /**
     * Get an UPDATE node given the pieces.
     *
     * @exception StandardException
     */
    StatementNode getUpdateNode(FromTable fromTable, TableName tableName,
                                ResultColumnList setClause, ValueNode whereClause,
                                ResultColumnList returningList)
            throws StandardException {
        FromList fromList = (FromList)nodeFactory.getNode(NodeTypes.FROM_LIST,
                                                          parserContext);

        fromList.addFromTable(fromTable);

        SelectNode resultSet = (SelectNode)nodeFactory.getNode(NodeTypes.SELECT_NODE,
                                                               setClause,
                                                               null,
                                                               fromList, /* FROM list */
                                                               whereClause, /* WHERE clause */
                                                               null, /* GROUP BY list */
                                                               null, /* having clause */
                                                               null, /* window list */
                                                               parserContext);

        StatementNode retval =
            (StatementNode)nodeFactory.getNode(NodeTypes.UPDATE_NODE,
                                               tableName,
                                               resultSet,
                                               returningList,
                                               parserContext);

        return retval;
    }

    /**
     * Generate a trim operator node
     * @param trimSpec one of Leading, Trailing or Both.
     * @param trimChar the character to trim. Can be null in which case it defaults
     * to ' '.
     * @param trimSource expression to be trimmed.
     */
    ValueNode getTrimOperatorNode(BinaryOperatorNode.OperatorType trimType,
                                  ValueNode trimChar,
                                  ValueNode trimSource)
            throws StandardException {
        if (trimChar == null) {
            trimChar = (CharConstantNode)nodeFactory.getNode(NodeTypes.CHAR_CONSTANT_NODE,
                                                             " ",
                                                             parserContext);
        }
        return (ValueNode)nodeFactory.getNode(NodeTypes.TRIM_OPERATOR_NODE,
                                              trimSource, // left
                                              trimChar,   // right
                                              trimType,
                                              parserContext);
    }

    /**
     * Get one of the several types of create alias nodes.
     *
     * @param aliasName The name of the alias
     * @param fullStaticMethodName The full path/method name
     * @param aliasSpecificInfo Information specific to the type of alias being created.
     * @param aliasType The type of alias to create
     *
     * @return A CreateAliasNode matching the given parameters
     *
     * @exception StandardException Thrown on error
     */
    StatementNode getCreateAliasNode(Object aliasName, String fullStaticMethodName,
                                     Object aliasSpecificInfo, AliasInfo.Type aliasType,
                                     Boolean createOrReplace)
            throws StandardException {

        StatementNode aliasNode = (StatementNode)
            nodeFactory.getCreateAliasNode(aliasName,
                                           fullStaticMethodName,
                                           aliasSpecificInfo,
                                           aliasType,
                                           createOrReplace,
                                           parserContext);

        return aliasNode;
    }

    /** Create a node for the drop alias/procedure call. */
    StatementNode dropAliasNode(TableName aliasName, AliasInfo.Type type, ExistenceCheck cond) throws StandardException {

        StatementNode stmt = (StatementNode)nodeFactory.getNode(NodeTypes.DROP_ALIAS_NODE,
                                                                aliasName,
                                                                type,
                                                                cond,
                                                                parserContext);

        return stmt;
    }

    /**
     * Get a substring node from
     *          - the string
     *          - the start position
     *          - the length
     *          - a boolean values for specifying the kind of substring function
     * @exception StandardException  Thrown on error
     */
    ValueNode getSubstringNode(ValueNode stringValue, ValueNode startPosition,
                               ValueNode length, Boolean boolVal)
            throws StandardException {
        return (ValueNode)nodeFactory.getNode(NodeTypes.SUBSTRING_OPERATOR_NODE,
                                              stringValue,
                                              startPosition,
                                              length,
                                              TernaryOperatorNode.OperatorType.SUBSTRING,
                                              null,
                                              parserContext);
    }

    ValueNode getJdbcIntervalNode(int intervalType) throws StandardException {
        return (ValueNode)nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                              intervalType,
                                              parserContext);
    }

    /**
     * Construct a TableElementNode of type
     * NodeTypes.MODIFY_COLUMN_DEFAULT_NODE.
     *
     * @param defaultNode the new default value node
     * @param columnName    the name of the column to be altered
     * @param autoIncrementInfo autoincrement information collected, if any.
     *
     * @return the new node constructed
     * @exception StandardException standard error policy
     */
    TableElementNode wrapAlterColumnDefaultValue(ValueNode defaultNode,
                                                 String columnName,
                                                 long[] autoIncrementInfo)
            throws StandardException {

        if (autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_IS_AUTOINCREMENT_INDEX] == 0) {
            autoIncrementInfo = null;
        }
        return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_DEFAULT_NODE,
                                                     columnName,
                                                     defaultNode,
                                                     null,
                                                     autoIncrementInfo,
                                                     parserContext);
    }

    /**
     * Construct a new join node.
     *
     * @param leftRSN the left side of the join
     * @param rightRSN the right side of the join
     * @param onClause the ON clause, or null if there is no ON clause
     * @param usingClause the USING clause, or null if there is no USING clause
     * @param joinType the type of the join (one of the constants INNERJOIN,
     *        LEFTOUTERJOIN or RIGHTOUTERJOIN in JoinNode)
     * @return a new join node
     */
    private JoinNode newJoinNode(ResultSetNode leftRSN, ResultSetNode rightRSN,
                                 ValueNode onClause, ResultColumnList usingClause,
                                 JoinNode.JoinType joinType)
            throws StandardException {
        switch(joinType) {
        case INNER:
            return (JoinNode)nodeFactory.getNode(NodeTypes.JOIN_NODE,
                                                 leftRSN,
                                                 rightRSN,
                                                 onClause,
                                                 usingClause,
                                                 null,
                                                 null,
                                                 null,
                                                 parserContext);

        case LEFT_OUTER:
            return (JoinNode)nodeFactory.getNode(NodeTypes.HALF_OUTER_JOIN_NODE,
                                                 leftRSN,
                                                 rightRSN,
                                                 onClause,
                                                 usingClause,
                                                 Boolean.FALSE,
                                                 null,
                                                 parserContext);

        case RIGHT_OUTER:
            return (JoinNode)nodeFactory.getNode(NodeTypes.HALF_OUTER_JOIN_NODE,
                                                 leftRSN,
                                                 rightRSN,
                                                 onClause,
                                                 usingClause,
                                                 Boolean.TRUE,
                                                 null,
                                                 parserContext);

        case FULL_OUTER:
            return (JoinNode)nodeFactory.getNode(NodeTypes.FULL_OUTER_JOIN_NODE,
                                                 leftRSN,
                                                 rightRSN,
                                                 onClause,
                                                 usingClause,
                                                 null,
                                                 parserContext);

        case STRAIGHT:
            {
                Properties joinOrderStrategyProperties = new Properties();
                joinOrderStrategyProperties.setProperty("STRAIGHT", "TRUE");
                return (JoinNode)nodeFactory.getNode(NodeTypes.JOIN_NODE,
                                                     leftRSN,
                                                     rightRSN,
                                                     onClause,
                                                     usingClause,
                                                     null,
                                                     null,
                                                     joinOrderStrategyProperties,
                                                     parserContext);
            }

        default:
            assert false : "Unexpected joinType: " + joinType;
            return null;
        }
    }

    // TODO: What is this about?
    private boolean isTableValueConstructor(ResultSetNode expression) {
        return expression instanceof RowResultSetNode ||
            (expression instanceof UnionNode && ((UnionNode)expression).tableConstructor());
    }

    /* Common default argument pattern. */
    TableName qualifiedName() throws ParseException, StandardException {
        return qualifiedName(NodeTypes.TABLE_NAME);
    }

    ValueNode additiveExpression() throws ParseException, StandardException {
        return additiveExpression(null, null);
    }

    boolean groupConstructFollows(int tokenKind) {
        return hasFeature(SQLParserFeature.GROUPING) &&
            getToken(1).kind == tokenKind;
    }

    boolean unsignedFollows() {
        return hasFeature(SQLParserFeature.UNSIGNED) &&
            getToken(1).kind == UNSIGNED;
    }

    boolean indexHintFollows(int offset) {
        if (!hasFeature(SQLParserFeature.MYSQL_HINTS))
            return false;
        int kind1 = getToken(offset).kind;
        int kind2 = getToken(offset+1).kind;
        return (((kind1 == USE) || (kind1 == IGNORE) || (kind1 == FORCE)) &&
                ((kind2 == INDEX) || (kind2 == KEY)));
    }

    boolean straightJoinFollows() {
        return hasFeature(SQLParserFeature.MYSQL_HINTS) &&
            getToken(1).kind == STRAIGHT_JOIN;
    }

    boolean infixModFollows() {
        if (!hasFeature(SQLParserFeature.INFIX_MOD))
            return false;
        switch(getToken(1).kind) {
        case MOD:
        case PERCENT:   return true;
        default:        return false;
        }
    }

    boolean parensFollow()
    {
        if (!hasFeature(SQLParserFeature.MYSQL_COLUMN_AS_FUNCS))
           return false;

        if (getToken(1).kind == LEFT_PAREN && getToken(2).kind == RIGHT_PAREN)
            return true;
        return false;
    }

    boolean infixBitFollows() {
        if (!hasFeature(SQLParserFeature.INFIX_BIT_OPERATORS))
            return false;
        switch(getToken(1).kind) {
        case AMPERSAND:
        case VERTICAL_BAR:
        case CARET:
        case DOUBLE_LESS:
        case DOUBLE_GREATER:
            return true;
        default:
            return false;
        }
    }

    boolean unaryBitFollows() {
        if (!hasFeature(SQLParserFeature.INFIX_BIT_OPERATORS))
            return false;
        switch(getToken(1).kind) {
        case TILDE:
            return true;
        default:
            return false;
        }
    }

    // This LOOKAHEAD is required because a + or - sign can come
    // before any expression, and also can be part of a literal. If it
    // comes before a number, we want it to be considered part of the
    // literal, because the literal() rule knows how to handle the
    // minimum value for an int without changing it to a long.
    boolean unaryArithmeticFollows() {
        switch (getToken(1).kind) {
        case PLUS_SIGN:
        case MINUS_SIGN:
            break;
        default:
            return false;
        }
        switch (getToken(2).kind) {
        case EXACT_NUMERIC:
        case APPROXIMATE_NUMERIC:
            return false;
        default:
            return true;
        }
    }

    boolean simpleLiteralInListFollows() {
        int tkpos = 1;
        switch (getToken(tkpos).kind) {
        case SINGLEQUOTED_STRING:
        case EXACT_NUMERIC:
        case APPROXIMATE_NUMERIC:
        case TRUE:
        case FALSE:
        case NULL:
        case HEX_STRING:
            break;
        case DATE:
        case TIME:
        case TIMESTAMP:
            tkpos++;
            switch (getToken(tkpos).kind) {
            case SINGLEQUOTED_STRING:
                break;
            default:
                return false;
            }
            break;
        case PLUS_SIGN:
        case MINUS_SIGN:
            tkpos++;
            switch (getToken(tkpos).kind) {
            case EXACT_NUMERIC:
            case APPROXIMATE_NUMERIC:
                break;
            default:
                return false;
            }
            break;
        default:
            return false;
        }
        tkpos++;
        switch (getToken(tkpos).kind) {
        case COMMA:
        case RIGHT_PAREN:
            break;
        default:
            return false;
        }
        return true;
    }

    boolean mysqlIntervalFollows() {
        if (!hasFeature(SQLParserFeature.MYSQL_INTERVAL))
            return false;
        switch (getToken(1).kind) {
        case MICROSECOND:
        case WEEK:
        case QUARTER:
        case SECOND_MICROSECOND:
        case MINUTE_MICROSECOND:
        case MINUTE_SECOND:
        case HOUR_MICROSECOND:
        case HOUR_SECOND:
        case HOUR_MINUTE:
        case DAY_MICROSECOND:
        case DAY_SECOND:
        case DAY_MINUTE:
        case DAY_HOUR:
        case YEAR_MONTH:
            return true;
        default:
            return false;
        }
    }

    /**
     * check if the next token  is an alter-table action
     * (ie., ADD, DROP, ALTER, UPDATE, RENAME
     */
    boolean notAlterActionFollows()
    {
        switch(getToken(1).kind)
        {
            case ADD:
            case DROP:
            case ALTER:
            case UPDATE:
            case RENAME:
                return false;
            default:
                return true;
         }
    }

    void setParserContext(SQLParserContext parserContext) {
        this.parserContext = parserContext;
        this.nodeFactory = parserContext.getNodeFactory();
    }

    StatementNode parseStatement(String statementSQLText,
                                 List<ParameterNode> parameterList)
            throws ParseException, StandardException {
        this.statementSQLText = statementSQLText;
        this.parameterList = parameterList;
        this.parameterNumber = 0;
        return Statement();
    }

    List<StatementNode> parseStatements(String statementSQLText)
            throws ParseException, StandardException {
        List<StatementNode> result = new ArrayList<StatementNode>();
        this.statementSQLText = statementSQLText;
        StatementList(result);
        return result;
    }

  final public StatementNode Statement() throws ParseException, StandardException {
    StatementNode statementNode;
    statementNode = StatementPart(null);
    jj_consume_token(0);
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public void StatementList(List<StatementNode> list) throws ParseException, StandardException {
    Token[] tokenHolder = new Token[1];
    statementListElement(list, tokenHolder);
    label_1:
    while (true) {
      switch (jj_nt.kind) {
      case SEMICOLON:
        ;
        break;
      default:
        jj_la1[0] = jj_gen;
        break label_1;
      }
      jj_consume_token(SEMICOLON);
      if (jj_2_1(1)) {
        statementListElement(list, tokenHolder);
      } else {
        ;
      }
    }
    jj_consume_token(0);
  }

  final public void statementListElement(List<StatementNode> list, Token[] tokenHolder) throws ParseException, StandardException {
    StatementNode statementNode;
    parameterNumber = 0;
    statementNode = StatementPart(tokenHolder);
        statementNode.setBeginOffset(tokenHolder[0].beginOffset);
        statementNode.setEndOffset(getToken(0).endOffset);
        list.add(statementNode);
  }

  final public StatementNode proceduralStatement(Token[] tokenHolder) throws ParseException, StandardException {
    StatementNode statementNode;
    tokenHolder[0] = getToken(1);
    switch (jj_nt.kind) {
    case INSERT:
      statementNode = insertStatement();
      break;
    case UPDATE:
      statementNode = preparableUpdateStatement();
      break;
    case DELETE:
      statementNode = preparableDeleteStatement();
      break;
    case SELECT:
    case VALUES:
    case LEFT_PAREN:
      statementNode = preparableSelectStatement();
      break;
    case CALL:
    case LEFT_BRACE:
    case QUESTION_MARK:
    case DOLLAR_N:
      statementNode = callStatement();
      break;
    default:
      jj_la1[1] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode StatementPart(Token[] tokenHolder) throws ParseException, StandardException {
    StatementNode statementNode;
    if (tokenHolder != null) {
        tokenHolder[0] = getToken(1); // Remember preceding token.
    }
    switch (jj_nt.kind) {
    case RENAME:
      statementNode = spsRenameStatement();
      break;
    case LOCK:
      statementNode = lockStatement();
      break;
    case CREATE:
      statementNode = createStatements();
      break;
    case DROP:
      statementNode = dropStatements();
      break;
    case ALTER:
      statementNode = spsAlterStatement();
      break;
    case DELETE:
    case INSERT:
    case SELECT:
    case UPDATE:
    case VALUES:
    case CALL:
    case LEFT_BRACE:
    case LEFT_PAREN:
    case QUESTION_MARK:
    case DOLLAR_N:
      statementNode = preparableSQLDataStatement();
      break;
    case CLOSE:
    case DEALLOCATE:
    case DECLARE:
    case FETCH:
    case PREPARE:
      statementNode = cursorStatement();
      break;
    case EXECUTE:
      statementNode = executeStatement();
      break;
    default:
      jj_la1[2] = jj_gen;
      if (jj_2_2(1)) {
        statementNode = spsSetStatement();
      } else {
        switch (jj_nt.kind) {
        case TRUNCATE:
          statementNode = truncateStatement();
          break;
        default:
          jj_la1[3] = jj_gen;
          if (jj_2_3(1)) {
            statementNode = grantStatement();
          } else if (jj_2_4(1)) {
            statementNode = revokeStatement();
          } else {
            switch (jj_nt.kind) {
            case EXPLAIN:
              statementNode = explainStatement();
              break;
            case BEGIN:
            case COMMIT:
            case ROLLBACK:
              statementNode = transactionControlStatement();
              break;
            case COPY:
              statementNode = copyStatement();
              break;
            default:
              jj_la1[4] = jj_gen;
              jj_consume_token(-1);
              throw new ParseException();
            }
          }
        }
      }
    }
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public ExistenceCheck createCondition() throws ParseException, StandardException {
    if (getToken(1).kind == IF) {
      jj_consume_token(IF);
      jj_consume_token(NOT);
      jj_consume_token(EXISTS);
        {if (true) return ExistenceCheck.IF_NOT_EXISTS;}
    } else {
        {if (true) return ExistenceCheck.NO_CONDITION;}
    }
    throw new Error("Missing return statement in function");
  }

  final public ExistenceCheck dropCondition() throws ParseException, StandardException {
    if (getToken(1).kind == IF) {
      jj_consume_token(IF);
      jj_consume_token(EXISTS);
        {if (true) return ExistenceCheck.IF_EXISTS;}
    } else {
        {if (true) return ExistenceCheck.NO_CONDITION;}
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode createStatements() throws ParseException, StandardException {
    StatementNode statementNode;
    Token beginToken;
    Boolean createOrReplace = Boolean.FALSE;
    beginToken = jj_consume_token(CREATE);
    if (getToken(1).kind == OR &&
                        getToken(2).kind == REPLACE &&
                      ( getToken(3).kind == SYNONYM ||
                        getToken(3).kind == PROCEDURE ||
                        getToken(3).kind == FUNCTION ||
                        getToken(3).kind == TYPE )) {
      jj_consume_token(OR);
      jj_consume_token(REPLACE);
                       createOrReplace = Boolean.TRUE;
    } else {
      ;
    }
    switch (jj_nt.kind) {
    case SCHEMA:
      statementNode = schemaDefinition();
      break;
    case VIEW:
      statementNode = viewDefinition(beginToken);
      break;
    case TRIGGER:
      statementNode = triggerDefinition();
      break;
    case SYNONYM:
      statementNode = synonymDefinition(createOrReplace);
      break;
    case ROLE:
      statementNode = roleDefinition();
      break;
    case SEQUENCE:
      statementNode = sequenceDefinition();
      break;
    case TABLE:
      statementNode = tableDefinition();
      break;
    case PROCEDURE:
      statementNode = procedureDefinition(createOrReplace);
      break;
    case FUNCTION:
      statementNode = functionDefinition(createOrReplace);
      break;
    case TYPE:
      statementNode = udtDefinition(createOrReplace);
      break;
    case INDEX:
    case UNIQUE:
      statementNode = indexDefinition();
      break;
    default:
      jj_la1[5] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropStatements() throws ParseException, StandardException {
    StatementNode statementNode;
    jj_consume_token(DROP);
    switch (jj_nt.kind) {
    case SCHEMA:
      statementNode = dropSchemaStatement();
      break;
    case TABLE:
      statementNode = dropTableStatement();
      break;
    case INDEX:
      statementNode = dropIndexStatement();
      break;
    case FUNCTION:
    case PROCEDURE:
    case SYNONYM:
    case TYPE:
      statementNode = dropAliasStatement();
      break;
    case VIEW:
      statementNode = dropViewStatement();
      break;
    case TRIGGER:
      statementNode = dropTriggerStatement();
      break;
    case ROLE:
      statementNode = dropRoleStatement();
      break;
    case SEQUENCE:
      statementNode = dropSequenceStatement();
      break;
    case GROUP:
      statementNode = dropGroupStatement();
      break;
    default:
      jj_la1[6] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode spsAlterStatement() throws ParseException, StandardException {
    StatementNode statementNode;
    jj_consume_token(ALTER);
    statementNode = alterStatement();
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode spsSetStatement() throws ParseException, StandardException {
    StatementNode statementNode;
    if (getToken(1).kind == SET && getToken(2).kind != CURRENT) {
      jj_consume_token(SET);
      if (jj_2_5(1)) {
        statementNode = setIsolationStatement();
      } else if (jj_2_6(1)) {
        statementNode = setSchemaStatement();
      } else {
        switch (jj_nt.kind) {
        case MESSAGE_LOCALE:
          statementNode = setMessageLocaleStatement();
          break;
        case ROLE:
          statementNode = setRoleStatement();
          break;
        case SESSION:
        case TRANSACTION:
          statementNode = setTransactionStatement();
          break;
        case IDENTIFIER:
          statementNode = setConfigurationStatement();
          break;
        default:
          jj_la1[7] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
        {if (true) return statementNode;}
    } else if (getToken(1).kind == SET && getToken(2).kind == CURRENT) {
      jj_consume_token(SET);
      if (jj_2_7(1)) {
        statementNode = setSchemaStatement();
      } else if (jj_2_8(1)) {
        statementNode = setIsolationStatement();
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return statementNode;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

/*
 * preparableSQLDataStatement differs from directSQLDataStatement in
 * that it supports positioned update and delete and a preparable
 * select (with FOR UPDATE) instead of a direct select (without FOR
 * UPDATE)
 */
  final public StatementNode preparableSQLDataStatement() throws ParseException, StandardException {
    StatementNode    dmlStatement;
    switch (jj_nt.kind) {
    case DELETE:
      dmlStatement = preparableDeleteStatement();
      break;
    case SELECT:
    case VALUES:
    case LEFT_PAREN:
      dmlStatement = preparableSelectStatement();
      break;
    case INSERT:
      dmlStatement = insertStatement();
      break;
    case UPDATE:
      dmlStatement = preparableUpdateStatement();
      break;
    case CALL:
    case LEFT_BRACE:
    case QUESTION_MARK:
    case DOLLAR_N:
      dmlStatement = callStatement();
      break;
    default:
      jj_la1[8] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return dmlStatement;}
    throw new Error("Missing return statement in function");
  }

// This may be a search or positioned delete statement.
  final public StatementNode preparableDeleteStatement() throws ParseException, StandardException {
    StatementNode qtn;
    jj_consume_token(DELETE);
    qtn = deleteBody();
        {if (true) return qtn;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode deleteBody() throws ParseException, StandardException {
    JavaToSQLValueNode javaToSQLNode = null;
    String correlationName = null;
    TableName tableName = null;
    ValueNode whereClause = null;
    FromTable fromTable = null;
    QueryTreeNode retval;
    Properties targetProperties = null;
    Token whereToken = null;
    ResultColumnList returningList = null;
    if (fromNewInvocationFollows()) {
      jj_consume_token(FROM);
      javaToSQLNode = newInvocation();
      switch (jj_nt.kind) {
      case WHERE:
        whereToken = jj_consume_token(WHERE);
        whereClause = whereClause(whereToken);
        break;
      default:
        jj_la1[9] = jj_gen;
        ;
      }
        fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_VTI,
                                                   javaToSQLNode.getJavaValueNode(),
                                                   (String)null,
                                                   null,
                                                   (Properties)null,
                                                   parserContext);

        {if (true) return getDeleteNode(fromTable, tableName, whereClause, returningList);}
    } else {
      switch (jj_nt.kind) {
      case FROM:
        jj_consume_token(FROM);
        tableName = qualifiedName();
        if ((getToken(1).kind != EOF) && (getToken(1).kind != SEMICOLON) &&
                                (getToken(1).kind != WHERE) &&
                                (getToken(1).kind != RETURNING) &&
                                             !derbyPropertiesListFollows()) {
          switch (jj_nt.kind) {
          case AS:
            jj_consume_token(AS);
            break;
          default:
            jj_la1[10] = jj_gen;
            ;
          }
          correlationName = identifier();
        } else {
          ;
        }
        switch (jj_nt.kind) {
        case DERBYDASHPROPERTIES:
          targetProperties = propertyList(false);
          jj_consume_token(CHECK_PROPERTIES);
          break;
        default:
          jj_la1[11] = jj_gen;
          ;
        }
        switch (jj_nt.kind) {
        case WHERE:
          whereToken = jj_consume_token(WHERE);
          if ((getToken(1).kind == CURRENT) &&    (getToken(2).kind == OF)) {
            fromTable = currentOfClause(correlationName);
          } else if (jj_2_9(1)) {
            whereClause = whereClause(whereToken);
          } else {
            jj_consume_token(-1);
            throw new ParseException();
          }
          break;
        default:
          jj_la1[12] = jj_gen;
          ;
        }
        switch (jj_nt.kind) {
        case RETURNING:
          jj_consume_token(RETURNING);
          returningList = selectList();
          break;
        default:
          jj_la1[13] = jj_gen;
          ;
        }
        /* Fabricate a ResultSetNode (SelectNode) under the DeleteNode.
         * For a searched delete, The FromList is simply the table that we
         * are deleting from.
         * (NOTE - we mark the table as the one that we are deleting
         * from.)    For a positioned delete, the FromList is a
         * CurrentOfNode holding the cursor name.    The select list will be
         * null for now.    We will generate it at bind time, in keeping
         * with the design decision that the parser's output should look
         * like the language.
         */
        if (fromTable == null)
            fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_BASE_TABLE,
                                                       tableName,
                                                       correlationName,
                                                       FromBaseTable.UpdateOrDelete.DELETE,
                                                       parserContext);

        if (targetProperties != null) {
            ((FromBaseTable)fromTable).setTableProperties(targetProperties);
        }

        {if (true) return getDeleteNode(fromTable, tableName, whereClause, returningList);}
        break;
      default:
        jj_la1[14] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public FromTable currentOfClause(String correlationName) throws ParseException, StandardException {
    String cursorName = null;
    jj_consume_token(CURRENT);
    jj_consume_token(OF);
    cursorName = identifier();
        {if (true) return (FromTable)nodeFactory.getNode(NodeTypes.CURRENT_OF_NODE,
                                              correlationName,
                                              cursorName,
                                              null,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
 *  The preparable select statement is a superset of the
 *  directSelectStatementMultipleRows in that it allows both the
 *  preparable single row select statement (a query expression that
 *  returns one row, although it is also handled like a cursor) and
 *  the preparable multiple row select statement, which allows not
 *  only an order by clause but also a for update clause.
 */
  final public CursorNode preparableSelectStatement() throws ParseException, StandardException {
    ResultSetNode queryExpression;
    List<String> updateColumns = new ArrayList<String>();
    CursorNode.UpdateMode forUpdateState = CursorNode.UpdateMode.UNSPECIFIED;
    IsolationLevel isolationLevel = IsolationLevel.UNSPECIFIED_ISOLATION_LEVEL;
    CursorNode retval;
    OrderByList orderCols = null;
    ValueNode[] offsetAndFetchFirst = new ValueNode[2];
    queryExpression = queryExpression(null, NO_SET_OP);
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[15] = jj_gen;
      ;
    }
    label_2:
    while (true) {
      switch (jj_nt.kind) {
      case FETCH:
      case OFFSET:
      case LIMIT:
        ;
        break;
      default:
        jj_la1[16] = jj_gen;
        break label_2;
      }
      offsetOrFetchFirstClause(offsetAndFetchFirst);
    }
    switch (jj_nt.kind) {
    case FOR:
      jj_consume_token(FOR);
      forUpdateState = forUpdateClause(updateColumns);
      break;
    default:
      jj_la1[17] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case WITH:
      isolationLevel = atIsolationLevel();
      break;
    default:
      jj_la1[18] = jj_gen;
      ;
    }
        // Note: if ORDER BY is specified, the FOR UPDATE clause must be
        // READ ONLY or empty, and the cursor is implicitly READ_ONLY.

        retval = (CursorNode)nodeFactory.getNode(NodeTypes.CURSOR_NODE,
                                                 "SELECT",
                                                 queryExpression,
                                                 null,
                                                 orderCols,
                                                 offsetAndFetchFirst[0],
                                                 offsetAndFetchFirst[1],
                                                 forUpdateState,
                                                 (forUpdateState == CursorNode.UpdateMode.READ_ONLY ? null : updateColumns),
                                                 parserContext);

        /* Set the isolation levels for the scans if specified */
        if (isolationLevel != IsolationLevel.UNSPECIFIED_ISOLATION_LEVEL) {
            retval.setScanIsolationLevel(isolationLevel);
        }

        {if (true) return retval;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode insertStatement() throws ParseException, StandardException {
    StatementNode insertNode;
    QueryTreeNode targetTable;
    jj_consume_token(INSERT);
    jj_consume_token(INTO);
    targetTable = targetTable();
    insertNode = insertColumnsAndSource(targetTable);
        {if (true) return insertNode;}
    throw new Error("Missing return statement in function");
  }

  final public QueryTreeNode targetTable() throws ParseException, StandardException {
    JavaToSQLValueNode javaToSQLNode;
    TableName tableName;
    if (newInvocationFollows(1)) {
      javaToSQLNode = newInvocation();
        {if (true) return nodeFactory.getNode(NodeTypes.FROM_VTI,
                                   javaToSQLNode.getJavaValueNode(),
                                   null,
                                   null,
                                   (Properties)null,
                                   parserContext);}
    } else if (jj_2_10(1)) {
      tableName = qualifiedName();
        {if (true) return tableName;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode preparableUpdateStatement() throws ParseException, StandardException {
    StatementNode qtn;
    jj_consume_token(UPDATE);
    qtn = updateBody();
        {if (true) return qtn;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode updateBody() throws ParseException, StandardException {
    ResultColumnList columnList;
    String correlationName = null;
    JavaToSQLValueNode javaToSQLNode = null;
    TableName tableName = null;
    ValueNode whereClause = null;
    FromTable fromTable = null;
    Properties targetProperties = null;
    Token whereToken = null;
    ResultColumnList returningList = null;
    if (newInvocationFollows(1)) {
      javaToSQLNode = newInvocation();
      jj_consume_token(SET);
      columnList = setClauseList();
      switch (jj_nt.kind) {
      case WHERE:
        whereToken = jj_consume_token(WHERE);
        whereClause = whereClause(whereToken);
        break;
      default:
        jj_la1[19] = jj_gen;
        ;
      }
        fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_VTI,
                                                   javaToSQLNode.getJavaValueNode(),
                                                   (String)null,
                                                   null,
                                                   (Properties)null,
                                                   parserContext);

        {if (true) return getUpdateNode(fromTable, tableName, columnList, whereClause, returningList);}
    } else if (jj_2_12(1)) {
      tableName = qualifiedName();
      if ((getToken(1).kind != SET) && !derbyPropertiesListFollows()) {
        switch (jj_nt.kind) {
        case AS:
          jj_consume_token(AS);
          break;
        default:
          jj_la1[20] = jj_gen;
          ;
        }
        correlationName = identifier();
      } else {
        ;
      }
      switch (jj_nt.kind) {
      case DERBYDASHPROPERTIES:
        targetProperties = propertyList(false);
        jj_consume_token(CHECK_PROPERTIES);
        break;
      default:
        jj_la1[21] = jj_gen;
        ;
      }
      jj_consume_token(SET);
      columnList = setClauseList();
      switch (jj_nt.kind) {
      case WHERE:
        whereToken = jj_consume_token(WHERE);
        if (jj_2_11(1)) {
          whereClause = whereClause(whereToken);
        } else {
          switch (jj_nt.kind) {
          case CURRENT:
            fromTable = currentOfClause(correlationName);
            break;
          default:
            jj_la1[22] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
        }
        break;
      default:
        jj_la1[23] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case RETURNING:
        jj_consume_token(RETURNING);
        returningList = selectList();
        break;
      default:
        jj_la1[24] = jj_gen;
        ;
      }
        /* Fabricate a ResultSetNode (SelectNode) under the UpdateNode.
         * For a searched update,
         * The FromList is simply the table that we are updating.
         * For a positioned update,
         * the FromList is a CurrentOfNode holding the cursor name.
         * (NOTE - we mark the table as the one that we are updating.)
         * The select list is the columns in the SET clause.    At bind time,
            * we will prepend the CurrentRowLocation() in keeping with the design 
         * decision that the parser's output should look like the language.
         */
        if (fromTable == null)
            fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_BASE_TABLE,
                                                       tableName,
                                                       correlationName,
                                                       FromBaseTable.UpdateOrDelete.UPDATE,
                                                       parserContext);

        if (targetProperties != null) {
            ((FromBaseTable)fromTable).setTableProperties(targetProperties);
        }
        {if (true) return getUpdateNode(fromTable, tableName, columnList, whereClause, returningList);}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode callStatement() throws ParseException, StandardException {
    StatementNode retval;
    switch (jj_nt.kind) {
    case CALL:
    case QUESTION_MARK:
    case DOLLAR_N:
      retval = bareCallStatement();
      break;
    case LEFT_BRACE:
      jj_consume_token(LEFT_BRACE);
      retval = bareCallStatement();
      jj_consume_token(RIGHT_BRACE);
      break;
    default:
      jj_la1[25] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return retval;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode bareCallStatement() throws ParseException, StandardException {
    ParameterNode returnParam;
    ValueNode value;
    ResultSetNode resultSetNode;
    switch (jj_nt.kind) {
    case CALL:
      jj_consume_token(CALL);
      value = primaryExpression();
        if (! (value instanceof JavaToSQLValueNode) ||
            ! (((JavaToSQLValueNode) value).getJavaValueNode() instanceof MethodCallNode)) {
            {if (true) throw new StandardException("Invalid call statement");}
        }

        StatementNode callStatement = (StatementNode)
            nodeFactory.getNode(NodeTypes.CALL_STATEMENT_NODE,
                                value,
                                parserContext);

        {if (true) return callStatement;}
      break;
    case QUESTION_MARK:
    case DOLLAR_N:
      // ? = CALL method()
          returnParam = dynamicParameterSpecification();
      jj_consume_token(EQUALS_OPERATOR);
      jj_consume_token(CALL);
      resultSetNode = rowValueConstructor(null);
        // Validate that we have something that is an appropriate call statement.
        ResultColumnList rcl = resultSetNode.getResultColumns();

        // We can have only 1 return value/column.
        if (rcl == null || rcl.size() > 1) {
            {if (true) throw new StandardException("Invalid call statement");}
        }

        // We must have a method call node.
        value = rcl.get(0).getExpression();
        if (! (value instanceof JavaToSQLValueNode) ||
            ! (((JavaToSQLValueNode) value).getJavaValueNode() instanceof MethodCallNode)) {
            {if (true) throw new StandardException("Invalid call statement");}
        }

        // wrap the row result set in a cursor node
        StatementNode cursorNode = (StatementNode)
            nodeFactory.getNode(NodeTypes.CURSOR_NODE,
                                "SELECT",
                                resultSetNode,
                                null,
                                null,
                                null,
                                null,
                                CursorNode.UpdateMode.READ_ONLY,
                                null,
                                parserContext);

        // Set the 0th param to be a RETURN param.
        returnParam.setReturnOutputParam(value);

        parserContext.setReturnParameterFlag();

        {if (true) return cursorNode;}
      break;
    default:
      jj_la1[26] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode primaryExpression() throws ParseException, StandardException {
    ValueNode value = null;
    if (getToken(2).kind == PERIOD && getToken(4).kind == LEFT_PAREN) {
      value = routineInvocation();
        {if (true) return value;}
    } else if (jj_2_13(1)) {
      value = primaryExpressionXX();
        {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode savepointStatement() throws ParseException, StandardException {
    String savepointName = null;
    SavepointNode.StatementType savepointStatementType;
    Boolean[] savepointStatementClauses = new Boolean[SAVEPOINT_NCLAUSES];
    switch (jj_nt.kind) {
    case SAVEPOINT:
      jj_consume_token(SAVEPOINT);
      savepointName = identifier();
      label_3:
      while (true) {
        savepointStatementClause(savepointStatementClauses);
        switch (jj_nt.kind) {
        case ON:
        case UNIQUE:
          ;
          break;
        default:
          jj_la1[27] = jj_gen;
          break label_3;
        }
      }
        //ON ROLLBACK RETAIN CURSORS is only supported case *** TODO: fix this ***
        if (savepointStatementClauses[SAVEPOINT_RETAIN_CURSORS] == null)
            {if (true) throw new StandardException("Missing required ON ROLLBACK RETAIN CURSORS");}
        savepointStatementType = SavepointNode.StatementType.SET;
      break;
    case ROLLBACK:
      jj_consume_token(ROLLBACK);
      switch (jj_nt.kind) {
      case WORK:
        jj_consume_token(WORK);
        break;
      default:
        jj_la1[28] = jj_gen;
        ;
      }
      jj_consume_token(TO);
      jj_consume_token(SAVEPOINT);
      if (jj_2_14(1)) {
        savepointName = identifier();
      } else {
        ;
      }
        savepointStatementType = SavepointNode.StatementType.ROLLBACK;
      break;
    case RELEASE:
      jj_consume_token(RELEASE);
      switch (jj_nt.kind) {
      case TO:
        jj_consume_token(TO);
        break;
      default:
        jj_la1[29] = jj_gen;
        ;
      }
      jj_consume_token(SAVEPOINT);
      savepointName = identifier();
        savepointStatementType = SavepointNode.StatementType.RELEASE;
      break;
    default:
      jj_la1[30] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SAVEPOINT_NODE,
                                                  savepointName,
                                                  savepointStatementType,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void savepointStatementClause(Boolean[] savepointStatementClauses) throws ParseException, StandardException {
    int clausePosition;
    switch (jj_nt.kind) {
    case UNIQUE:
      jj_consume_token(UNIQUE);
               clausePosition = SAVEPOINT_UNIQUE;
      break;
    case ON:
      jj_consume_token(ON);
      jj_consume_token(ROLLBACK);
      jj_consume_token(RETAIN);
      clausePosition = LocksOrCursors();
      break;
    default:
      jj_la1[31] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        // Check for repeated clause
        if (savepointStatementClauses[clausePosition] != null) {
            String which = SAVEPOINT_CLAUSE_NAMES[clausePosition];
            {if (true) throw new StandardException("Repeated " + which + " clause");}
        }

        savepointStatementClauses[clausePosition] = Boolean.TRUE;
  }

  final public int LocksOrCursors() throws ParseException {
    switch (jj_nt.kind) {
    case LOCKS:
      jj_consume_token(LOCKS);
        {if (true) return SAVEPOINT_RETAIN_LOCKS;}
      break;
    case CURSORS:
      jj_consume_token(CURSORS);
        {if (true) return SAVEPOINT_RETAIN_CURSORS;}
      break;
    default:
      jj_la1[32] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode transactionControlStatement() throws ParseException, StandardException {
    TransactionControlNode.Operation transactionOperation;
    switch (jj_nt.kind) {
    case BEGIN:
      jj_consume_token(BEGIN);
            transactionOperation = TransactionControlNode.Operation.BEGIN;
      break;
    case COMMIT:
      jj_consume_token(COMMIT);
            transactionOperation = TransactionControlNode.Operation.COMMIT;
      break;
    case ROLLBACK:
      jj_consume_token(ROLLBACK);
            transactionOperation = TransactionControlNode.Operation.ROLLBACK;
      break;
    default:
      jj_la1[33] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case TRANSACTION:
    case WORK:
      switch (jj_nt.kind) {
      case WORK:
        jj_consume_token(WORK);
        break;
      case TRANSACTION:
        jj_consume_token(TRANSACTION);
        break;
      default:
        jj_la1[34] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[35] = jj_gen;
      ;
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.TRANSACTION_CONTROL_NODE,
                                                  transactionOperation,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode globalTemporaryTableDeclaration() throws ParseException, StandardException {
    TableName tableName;
    TableElementList tableElementList;
    Boolean[] declareTableClauses = new Boolean[TEMPORARY_TABLE_NCLAUSES];
    jj_consume_token(DECLARE);
    jj_consume_token(GLOBAL);
    jj_consume_token(TEMPORARY);
    jj_consume_token(TABLE);
    tableName = qualifiedName();
    tableElementList = tableElementList();
    label_4:
    while (true) {
      declareTableClause(declareTableClauses);
      if (jj_2_15(1)) {
        ;
      } else {
        break label_4;
      }
    }
        // NOT LOGGED is mandatory
        if (declareTableClauses[TEMPORARY_TABLE_NOT_LOGGED] == null)
            {if (true) throw new StandardException("Missing required NOT LOGGED");}
        // if ON COMMIT behavior not explicitly specified in DECLARE command, resort to default ON COMMIT DELETE ROWS
        if (declareTableClauses[TEMPORARY_TABLE_ON_COMMIT] == null)
            declareTableClauses[TEMPORARY_TABLE_ON_COMMIT] = Boolean.TRUE;
        // if ON ROLLBACK behavior not explicitly specified in DECLARE command, resort to default ON ROLLBACK DELETE ROWS
        if (declareTableClauses[TEMPORARY_TABLE_ON_ROLLBACK] == null)
            declareTableClauses[TEMPORARY_TABLE_ON_ROLLBACK] = Boolean.TRUE;
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_TABLE_NODE,
                                                  tableName,
                                                  tableElementList,
                                                  (Properties)null,
                                                  (Boolean)declareTableClauses[1],
                                                  (Boolean)declareTableClauses[2],
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void declareTableClause(Boolean[] declareTableClauses) throws ParseException, StandardException {
    int clausePosition;
    Boolean clauseValue;
    switch (jj_nt.kind) {
    case NOT:
      jj_consume_token(NOT);
      jj_consume_token(LOGGED);
        clausePosition = TEMPORARY_TABLE_NOT_LOGGED;
        clauseValue = Boolean.TRUE;
      break;
    default:
      jj_la1[36] = jj_gen;
      if (getToken(1).kind == ON && getToken(2).kind == COMMIT) {
        jj_consume_token(ON);
        jj_consume_token(COMMIT);
        clauseValue = onCommit();
        jj_consume_token(ROWS);
        clausePosition = TEMPORARY_TABLE_ON_COMMIT;
      } else if (getToken(1).kind == ON && getToken(2).kind == ROLLBACK) {
        jj_consume_token(ON);
        jj_consume_token(ROLLBACK);
        jj_consume_token(DELETE);
        jj_consume_token(ROWS);
        clausePosition = TEMPORARY_TABLE_ON_ROLLBACK;
        clauseValue = Boolean.TRUE;
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        // Check for repeated clause.
        if (declareTableClauses[clausePosition] != null) {
            String which = TEMPORARY_TABLE_CLAUSE_NAMES[clausePosition];
            {if (true) throw new StandardException("Repeated " + which + " clause");}
        }
        declareTableClauses[clausePosition] = clauseValue;
  }

  final public Boolean onCommit() throws ParseException {
    switch (jj_nt.kind) {
    case PRESERVE:
      jj_consume_token(PRESERVE);
        {if (true) return Boolean.FALSE;}
      break;
    case DELETE:
      jj_consume_token(DELETE);
        {if (true) return Boolean.TRUE;}
      break;
    default:
      jj_la1[37] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public TableElementList tableElementList() throws ParseException, StandardException {
    TableElementList tableElementList =
                    (TableElementList)nodeFactory.getNode(NodeTypes.TABLE_ELEMENT_LIST,
                                                          parserContext);
    jj_consume_token(LEFT_PAREN);
    tableElement(tableElementList);
    label_5:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[38] = jj_gen;
        break label_5;
      }
      jj_consume_token(COMMA);
      tableElement(tableElementList);
    }
    jj_consume_token(RIGHT_PAREN);
        {if (true) return tableElementList;}
    throw new Error("Missing return statement in function");
  }

  final public void tableElement(TableElementList tableElementList) throws ParseException, StandardException {
    if (jj_2_16(1)) {
      columnDefinition(tableElementList);
    } else if (jj_2_17(1)) {
      tableConstraintDefinition(tableElementList);
        {if (true) return ;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public boolean columnDefinition(TableElementList tableElementList) throws ParseException, StandardException {
    DataTypeDescriptor[] typeDescriptor = new DataTypeDescriptor[1];
    ValueNode defaultNode = null;
    String columnName;
    long[] autoIncrementInfo = new long[4]; // *** TODO: constant ***
    String collation = null;
    columnName = identifier();
    typeDescriptor[0] = dataTypeDDL();
    if (jj_2_18(1)) {
      defaultNode = defaultAndConstraints(typeDescriptor, tableElementList, columnName,
                                                autoIncrementInfo);
      switch (jj_nt.kind) {
      case COLLATE:
        collation = collateClause();
                                      typeDescriptor[0] = new DataTypeDescriptor(typeDescriptor[0], CharacterTypeAttributes.forCollation(null, collation));
        break;
      default:
        jj_la1[39] = jj_gen;
        ;
      }
    } else {
      ;
    }
        // Only pass autoincrement info for autoincrement columns
        if (autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_IS_AUTOINCREMENT_INDEX] == 0) {
            autoIncrementInfo = null;
        }

        tableElementList.addTableElement((TableElementNode) nodeFactory.getNode(
                                                            NodeTypes.COLUMN_DEFINITION_NODE,
                                                            columnName,
                                                            defaultNode,
                                                            typeDescriptor[0],
                                                            autoIncrementInfo,
                                                            parserContext));
        {if (true) return autoIncrementInfo != null;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode defaultAndConstraints(DataTypeDescriptor[] typeDescriptor,
                      TableElementList tableElementList,
                      String columnName,
                      long[] autoIncrementInfo) throws ParseException, StandardException {
    ValueNode defaultNode = null;
    if (jj_2_22(1)) {
      columnConstraintDefinition(typeDescriptor, tableElementList, columnName);
      label_6:
      while (true) {
        if (jj_2_19(1)) {
          ;
        } else {
          break label_6;
        }
        columnConstraintDefinition(typeDescriptor, tableElementList, columnName);
      }
      switch (jj_nt.kind) {
      case _DEFAULT:
      case WITH:
      case GENERATED:
        defaultNode = defaultClause(autoIncrementInfo, columnName);
        label_7:
        while (true) {
          if (jj_2_20(1)) {
            ;
          } else {
            break label_7;
          }
          columnConstraintDefinition(typeDescriptor, tableElementList, columnName);
        }
        break;
      default:
        jj_la1[40] = jj_gen;
        ;
      }
        {if (true) return defaultNode;}
    } else {
      switch (jj_nt.kind) {
      case _DEFAULT:
      case WITH:
      case GENERATED:
        defaultNode = defaultClause(autoIncrementInfo, columnName);
        label_8:
        while (true) {
          if (jj_2_21(1)) {
            ;
          } else {
            break label_8;
          }
          columnConstraintDefinition(typeDescriptor, tableElementList, columnName);
        }
        {if (true) return defaultNode;}
        break;
      default:
        jj_la1[41] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor dataTypeDDL() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    if (commonDatatypeName(false)) {
      typeDescriptor = dataTypeCommon();
        {if (true) return typeDescriptor;}
    } else if (getToken(1).kind != GENERATED) {
      typeDescriptor = javaType();
        {if (true) return typeDescriptor;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor catalogType() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    typeDescriptor = dataTypeDDL();
        {if (true) return typeDescriptor;}          // NOTE: Used to be .getCatalogType()

    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor dataTypeCast() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    if (commonDatatypeName(true)) {
      typeDescriptor = dataTypeCommon();
        {if (true) return typeDescriptor;}
    } else if (jj_2_23(1)) {
      typeDescriptor = javaType();
        {if (true) return typeDescriptor;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor dataTypeCommon() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    boolean checkCS = false;
    CharacterTypeAttributes characterAttributes = null;
    if (jj_2_24(1)) {
      if (getToken(2).kind != LARGE) {

      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
      typeDescriptor = characterStringType();
      characterAttributes = characterTypeAttributes();
    } else if (jj_2_25(1)) {
      if (getToken(3).kind != LARGE) {

      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
      typeDescriptor = nationalCharacterStringType();
      characterAttributes = characterTypeAttributes();
    } else if (jj_2_26(1)) {
      typeDescriptor = numericType();
    } else {
      switch (jj_nt.kind) {
      case YEAR:
      case DATE:
      case DATETIME:
      case TIME:
      case TIMESTAMP:
        typeDescriptor = datetimeType();
        break;
      case INTERVAL:
        typeDescriptor = intervalType();
        break;
      case BOOLEAN:
        jj_consume_token(BOOLEAN);
        typeDescriptor = new DataTypeDescriptor(TypeId.BOOLEAN_ID, true);
        break;
      case LONG:
        typeDescriptor = longType();
        break;
      case BINARY:
      case CHAR:
      case CHARACTER:
      case NATIONAL:
      case BLOB:
      case CLOB:
      case NCLOB:
      case LONGBLOB:
      case LONGTEXT:
      case MEDIUMBLOB:
      case MEDIUMTEXT:
      case TEXT:
      case TINYBLOB:
      case TINYTEXT:
        typeDescriptor = LOBType();
        break;
      case XML:
        typeDescriptor = XMLType();
        break;
      default:
        jj_la1[42] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        if (characterAttributes != null)
            typeDescriptor = new DataTypeDescriptor(typeDescriptor, characterAttributes);
        {if (true) return typeDescriptor;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor characterStringType() throws ParseException, StandardException {
    int length = DEFAULT_STRING_COLUMN_LENGTH;
    Token varyingToken = null;
    int type;
    switch (jj_nt.kind) {
    case VARCHAR:
      jj_consume_token(VARCHAR);
      length = charLength();
        type = Types.VARCHAR;
      break;
    case CHAR:
    case CHARACTER:
      charOrCharacter();
      switch (jj_nt.kind) {
      case VARYING:
        // Length is optional for CHARACTER, not for plain CHARACTER VARYING
                varyingToken = jj_consume_token(VARYING);
        length = charLength();
        break;
      default:
        jj_la1[44] = jj_gen;
        switch (jj_nt.kind) {
        case LEFT_PAREN:
          length = charLength();
          break;
        default:
          jj_la1[43] = jj_gen;
          ;
        }
      }
        // If the user says CHARACTER VARYING, it's really VARCHAR
        type = (varyingToken == null ? Types.CHAR : Types.VARCHAR);
      break;
    default:
      jj_la1[45] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case FOR:
      type = forBitData(type);
      break;
    default:
      jj_la1[46] = jj_gen;
      ;
    }
        DataTypeDescriptor charDTD = DataTypeDescriptor.getBuiltInDataTypeDescriptor(type, length);
        {if (true) return charDTD;}
    throw new Error("Missing return statement in function");
  }

  final public void charOrCharacter() throws ParseException {
    switch (jj_nt.kind) {
    case CHAR:
      jj_consume_token(CHAR);
      break;
    case CHARACTER:
      jj_consume_token(CHARACTER);
      break;
    default:
      jj_la1[47] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public int charLength() throws ParseException, StandardException {
    int length;
    jj_consume_token(LEFT_PAREN);
    length = length();
    jj_consume_token(RIGHT_PAREN);
        {if (true) return length;}
    throw new Error("Missing return statement in function");
  }

  final public int forBitData(int charType) throws ParseException {
    jj_consume_token(FOR);
    jj_consume_token(BIT);
    jj_consume_token(DATA);
        switch (charType) {
        case Types.CHAR:
            charType = Types.BINARY;
            break;
        case Types.VARCHAR:
            charType = Types.VARBINARY;
            break;
        case Types.LONGVARCHAR:
            charType = Types.LONGVARBINARY;
            break;
        }
        {if (true) return charType;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor nationalCharacterStringType() throws ParseException, StandardException {
    DataTypeDescriptor  dataTypeDescriptor;
    int length = DEFAULT_STRING_COLUMN_LENGTH;
    String type = null;
    Token varyingToken = null;
    switch (jj_nt.kind) {
    case NATIONAL:
      jj_consume_token(NATIONAL);
      charOrCharacter();
      switch (jj_nt.kind) {
      case VARYING:
        // Length is optional for NATIONAL CHARACTER , not for NATIONAL CHARACTER VARYING
                varyingToken = jj_consume_token(VARYING);
        length = charLength();
        break;
      default:
        jj_la1[49] = jj_gen;
        switch (jj_nt.kind) {
        case LEFT_PAREN:
          length = charLength();
          break;
        default:
          jj_la1[48] = jj_gen;
          ;
        }
      }
        // If the user says NATIONAL CHARACTER VARYING, it's really NATIONALVARCHAR
        type = (varyingToken == null ? TypeId.NATIONAL_CHAR_NAME :
                        TypeId.NATIONAL_VARCHAR_NAME);
      break;
    case NCHAR:
      jj_consume_token(NCHAR);
      switch (jj_nt.kind) {
      case VARYING:
        // Length is optional for NCHAR, not for NCHAR VARYING
                varyingToken = jj_consume_token(VARYING);
        length = charLength();
        break;
      default:
        jj_la1[51] = jj_gen;
        switch (jj_nt.kind) {
        case LEFT_PAREN:
          length = charLength();
          break;
        default:
          jj_la1[50] = jj_gen;
          ;
        }
      }
        // If the user says NCHAR VARYING, it's really NATIONALVARCHAR
        type = (varyingToken == null ? TypeId.NATIONAL_CHAR_NAME :
                TypeId.NATIONAL_VARCHAR_NAME);
      break;
    case NVARCHAR:
      jj_consume_token(NVARCHAR);
      length = charLength();
        type = TypeId.NATIONAL_VARCHAR_NAME;
      break;
    default:
      jj_la1[52] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(type, length);}
    throw new Error("Missing return statement in function");
  }

  final public CharacterTypeAttributes characterTypeAttributes() throws ParseException, StandardException {
    TableName characterSet = null;
    String collation = null;
    CharacterTypeAttributes characterAttributes = null;
    switch (jj_nt.kind) {
    case CHARACTER:
      jj_consume_token(CHARACTER);
      jj_consume_token(SET);
      characterSet = qualifiedName();
                                                         characterAttributes = CharacterTypeAttributes.forCharacterSet(characterSet.toString());
      break;
    default:
      jj_la1[53] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case COLLATE:
      collation = collateClause();
                                    characterAttributes = CharacterTypeAttributes.forCollation(characterAttributes, collation);
      break;
    default:
      jj_la1[54] = jj_gen;
      ;
    }
        {if (true) return characterAttributes;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor LOBType() throws ParseException, StandardException {
    int length = 0x80000000-1;      // default to 2GB-1 if no length specified
    String type;
    CharacterTypeAttributes characterAttributes;
    switch (jj_nt.kind) {
    case BLOB:
      jj_consume_token(BLOB);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[55] = jj_gen;
        ;
      }
        type = TypeId.BLOB_NAME;
      break;
    case CLOB:
      jj_consume_token(CLOB);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[56] = jj_gen;
        ;
      }
        type = TypeId.CLOB_NAME;
      break;
    case TEXT:
      jj_consume_token(TEXT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[57] = jj_gen;
        ;
      }
        type = TypeId.TEXT_NAME;
      break;
    case NCLOB:
      jj_consume_token(NCLOB);
      length = lengthAndModifier();
        type = TypeId.NCLOB_NAME;
      break;
    case BINARY:
      jj_consume_token(BINARY);
      jj_consume_token(LARGE);
      jj_consume_token(OBJECT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[58] = jj_gen;
        ;
      }
        type = TypeId.BLOB_NAME;
      break;
    case CHAR:
    case CHARACTER:
      charOrCharacter();
      jj_consume_token(LARGE);
      jj_consume_token(OBJECT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[59] = jj_gen;
        ;
      }
        type = TypeId.CLOB_NAME;
      break;
    case NATIONAL:
      jj_consume_token(NATIONAL);
      jj_consume_token(CHARACTER);
      jj_consume_token(LARGE);
      jj_consume_token(OBJECT);
      length = lengthAndModifier();
        type = TypeId.NCLOB_NAME;
      break;
    case TINYBLOB:
      jj_consume_token(TINYBLOB);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[60] = jj_gen;
        ;
      }
        type = TypeId.TINYBLOB_NAME;
      break;
    case TINYTEXT:
      jj_consume_token(TINYTEXT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[61] = jj_gen;
        ;
      }
        type = TypeId.TINYTEXT_NAME;
      break;
    case MEDIUMBLOB:
      jj_consume_token(MEDIUMBLOB);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[62] = jj_gen;
        ;
      }
        type = TypeId.MEDIUMBLOB_NAME;
      break;
    case MEDIUMTEXT:
      jj_consume_token(MEDIUMTEXT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[63] = jj_gen;
        ;
      }
        type = TypeId.MEDIUMTEXT_NAME;
      break;
    case LONGBLOB:
      jj_consume_token(LONGBLOB);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[64] = jj_gen;
        ;
      }
        type = TypeId.LONGBLOB_NAME;
      break;
    case LONGTEXT:
      jj_consume_token(LONGTEXT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        length = lengthAndModifier();
        break;
      default:
        jj_la1[65] = jj_gen;
        ;
      }
        type = TypeId.LONGTEXT_NAME;
      break;
    default:
      jj_la1[66] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    characterAttributes = characterTypeAttributes();
        DataTypeDescriptor dtd = DataTypeDescriptor.getBuiltInDataTypeDescriptor(type, length);
        if (characterAttributes != null)
            dtd = new DataTypeDescriptor(dtd, characterAttributes);
        {if (true) return dtd;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor numericType() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    Token unsignedToken = null;
    switch (jj_nt.kind) {
    case DEC:
    case DECIMAL:
    case INT:
    case INTEGER:
    case NUMERIC:
    case SMALLINT:
    case LONGINT:
    case MEDIUMINT:
    case TINYINT:
      typeDescriptor = exactNumericType();
      break;
    default:
      jj_la1[67] = jj_gen;
      if (jj_2_27(1)) {
        typeDescriptor = approximateNumericType();
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    if (unsignedFollows()) {
      unsignedToken = jj_consume_token(UNSIGNED);
    } else {
      ;
    }
        if (unsignedToken != null)
            {if (true) return typeDescriptor.getUnsigned();}
        else
            {if (true) return typeDescriptor;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor exactNumericType() throws ParseException, StandardException {
    int precision = DEFAULT_DECIMAL_PRECISION;
    int scale = DEFAULT_DECIMAL_SCALE;
    int type = Types.DECIMAL;
    String typeStr = "DECIMAL";
    int maxWidth;
    DataTypeDescriptor dtd = null;
    switch (jj_nt.kind) {
    case DEC:
    case DECIMAL:
    case NUMERIC:
      switch (jj_nt.kind) {
      case NUMERIC:
        jj_consume_token(NUMERIC);
        type = Types.NUMERIC;
        typeStr = "NUMERIC";
        break;
      case DECIMAL:
        jj_consume_token(DECIMAL);
        break;
      case DEC:
        jj_consume_token(DEC);
        break;
      default:
        jj_la1[68] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        precision = precision();
        switch (jj_nt.kind) {
        case COMMA:
          jj_consume_token(COMMA);
          scale = scale();
          break;
        default:
          jj_la1[69] = jj_gen;
          ;
        }
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[70] = jj_gen;
        ;
      }
        if ((precision <= 0) ||
                (precision > MAX_DECIMAL_PRECISION_SCALE)) {
            {if (true) throw new StandardException("Invalid precision: " + precision);}
        }
        else if ((scale < 0) ||
                         (scale > MAX_DECIMAL_PRECISION_SCALE)) {
            {if (true) throw new StandardException("Invalid scale: " + scale);}
        }
        else if (scale > precision) {
            {if (true) throw new StandardException("Scale is greater than precision: " +
                                                                     scale + " > " + precision);}
        }
        /*
        ** If we have a decimal point, need to count it
        ** towards maxwidth.    Max width needs to account
        ** for the possible leading '0' and '-' and the
        ** decimal point.    e.g., DEC(1,1) has a maxwidth
        ** of 4 (to handle "-0.1").
        */
        maxWidth = DataTypeDescriptor.computeMaxWidth(precision, scale);
        {if (true) return getType(type, precision, scale, maxWidth);}
      break;
    case INT:
    case INTEGER:
    case SMALLINT:
    case LONGINT:
    case MEDIUMINT:
    case TINYINT:
      dtd = exactIntegerType();
        {if (true) return dtd;}
      break;
    default:
      jj_la1[71] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor exactIntegerType() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case INT:
    case INTEGER:
      switch (jj_nt.kind) {
      case INTEGER:
        jj_consume_token(INTEGER);
        break;
      case INT:
        jj_consume_token(INT);
        break;
      default:
        jj_la1[72] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.INTEGER);}
      break;
    case MEDIUMINT:
      jj_consume_token(MEDIUMINT);
        {if (true) return DataTypeDescriptor.MEDIUMINT;}
      break;
    case TINYINT:
      jj_consume_token(TINYINT);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TINYINT);}
      break;
    case SMALLINT:
      jj_consume_token(SMALLINT);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.SMALLINT);}
      break;
    case LONGINT:
      jj_consume_token(LONGINT);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.BIGINT);}
      break;
    default:
      jj_la1[73] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor approximateNumericType() throws ParseException, StandardException {
    int type = 0, scale = 0, width = 0;
    int prec = -1;
    DataTypeDescriptor dts = null;
    switch (jj_nt.kind) {
    case FLOAT:
      jj_consume_token(FLOAT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        prec = precision();
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[74] = jj_gen;
        ;
      }
        /*
            When not specified, default is DOUBLE_PRECISION
         */
        if (prec == -1)
            prec = TypeId.DOUBLE_PRECISION;

        if (prec > 0 && prec <= TypeId.REAL_PRECISION) {
            type = Types.REAL;
            prec = TypeId.REAL_PRECISION;
            scale = TypeId.REAL_SCALE;
            width = TypeId.REAL_MAXWIDTH;
        }
        else if (prec > TypeId.REAL_PRECISION &&
                 prec <= TypeId.DOUBLE_PRECISION) {
            type = Types.DOUBLE;
            prec = TypeId.DOUBLE_PRECISION;
            scale = TypeId.DOUBLE_SCALE;
            width = TypeId.DOUBLE_MAXWIDTH;
        }
        else
            {if (true) throw new StandardException("Invalid floating point precision: " + prec);}

        /*
            REMIND: this is a slight hack, in that an exact reading of
            the InformationSchema requires that the type the user typed
            in be visible to them in the InformationSchema views. But
            most implementations use synonyms or mappings at some point,
            and this is one of those places, for us.
         */
        {if (true) return getType(type, prec, scale, width);}
      break;
    case REAL:
      jj_consume_token(REAL);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.REAL);}
      break;
    default:
      jj_la1[75] = jj_gen;
      if (jj_2_28(1)) {
        dts = doubleType();
        {if (true) return dts;}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor doubleType() throws ParseException, StandardException {
    if (getToken(2).kind == PRECISION) {
      jj_consume_token(DOUBLE);
      jj_consume_token(PRECISION);
    } else {
      switch (jj_nt.kind) {
      case DOUBLE:
        jj_consume_token(DOUBLE);
        break;
      default:
        jj_la1[76] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DOUBLE);}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor longType() throws ParseException, StandardException {
    DataTypeDescriptor dataTypeDescriptor;
    jj_consume_token(LONG);
    dataTypeDescriptor = longSubType();
        {if (true) return dataTypeDescriptor;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor longSubType() throws ParseException, StandardException {
    int lvcType = Types.LONGVARCHAR;
    switch (jj_nt.kind) {
    case VARCHAR:
      jj_consume_token(VARCHAR);
      switch (jj_nt.kind) {
      case FOR:
        lvcType = forBitData(lvcType);
        break;
      default:
        jj_la1[77] = jj_gen;
        ;
      }
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(lvcType);}
      break;
    case NVARCHAR:
      jj_consume_token(NVARCHAR);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(TypeId.NATIONAL_LONGVARCHAR_NAME);}
      break;
    default:
      jj_la1[78] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor XMLType() throws ParseException, StandardException {
    DataTypeDescriptor value;
    jj_consume_token(XML);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.SQLXML);}
    throw new Error("Missing return statement in function");
  }

/*
 * Parse the XML keywords DOCUMENT and CONTENT.  We don't support
 * CONTENT yet, so we throw an appropriate error if we see it.
 *
 */
  final public void xmlDocOrContent() throws ParseException, StandardException {
    if ((getToken(1).kind != DOCUMENT) && (getToken(1).kind != CONTENT)) {
        // TODO: Is this needed?
        {if (true) throw new StandardException("Not DOCUMENT or CONTENT");}
    } else if (getToken(1).kind == CONTENT) {
      jj_consume_token(CONTENT);
        // TODO: Fix this by returning a proper value.
        {if (true) throw new StandardException("CONTENT not supported yet");}
    } else if (getToken(1).kind == DOCUMENT) {
      jj_consume_token(DOCUMENT);
        {if (true) return;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public DataTypeDescriptor javaType() throws ParseException, StandardException {
    TableName typeName;
    typeName = qualifiedName();
        {if (true) return getJavaClassDataTypeDescriptor(typeName);}
    throw new Error("Missing return statement in function");
  }

// A Java dot-separated list.
  final public String javaDSL() throws ParseException {
    String dotSeparatedList;
    dotSeparatedList = caseSensitiveIdentifierPlusReservedWords();
    label_9:
    while (true) {
      switch (jj_nt.kind) {
      case PERIOD:
        ;
        break;
      default:
        jj_la1[79] = jj_gen;
        break label_9;
      }
      dotSeparatedList = javaDSLNameExtender(dotSeparatedList);
    }
        {if (true) return dotSeparatedList;}
    throw new Error("Missing return statement in function");
  }

  final public String javaClassName() throws ParseException {
    String javaClassName;
    javaClassName = javaDSL();
        {if (true) return javaClassName;}
    throw new Error("Missing return statement in function");
  }

  final public String javaDSLNameExtender(String dotSeparatedList) throws ParseException {
    String extender;
    jj_consume_token(PERIOD);
    extender = caseSensitiveIdentifierPlusReservedWords();
        {if (true) return dotSeparatedList + "." + extender;}
    throw new Error("Missing return statement in function");
  }

  final public int lengthAndModifier() throws ParseException, StandardException {
    Token tok;
    Token tokmod = null;
    jj_consume_token(LEFT_PAREN);
    switch (jj_nt.kind) {
    case LENGTH_MODIFIER:
      tok = jj_consume_token(LENGTH_MODIFIER);
      break;
    case EXACT_NUMERIC:
      tok = jj_consume_token(EXACT_NUMERIC);
      switch (jj_nt.kind) {
      case IDENTIFIER:
        tokmod = jj_consume_token(IDENTIFIER);
        break;
      default:
        jj_la1[80] = jj_gen;
        ;
      }
      break;
    default:
      jj_la1[81] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(RIGHT_PAREN);
        // Collapse cases, whether single token or multiple.
        String s = tok.image;
        if (tokmod != null) s += tokmod.image;
        try {
            char modifier = s.charAt(s.length()-1);
            String number = s.substring(0, s.length()-1); // In case of suffix.
            long mul;
            switch (modifier) {
            case 'G':
            case 'g':
                mul = 1073741824L;          // 1 Giga
                break;
            case 'M':
            case 'm':
                mul = 1048576L;                 // 1 Mega
                break;
            case 'K':
            case 'k':
                mul = 1024l;                        // 1 Kilo
                break;
            default:
                mul = 1;
                number = s;                         // No letter in end, use whole string.
                break;
            }
            long specifiedLength = Long.parseLong(number) * mul;

            // match DB2 limits of 1 to 2147483647
            if ((specifiedLength > 0L) &&
                    (specifiedLength <= Integer.MAX_VALUE)) {
                {if (true) return (int)specifiedLength;}
            }
        }
        catch (NumberFormatException nfe) {
        }
        {if (true) throw new StandardException("Invalid LOB length:" + s);}
    throw new Error("Missing return statement in function");
  }

  final public int length() throws ParseException, StandardException {
    Token tok;
    int retval;
    tok = jj_consume_token(EXACT_NUMERIC);
        try {
            retval = Integer.parseInt(tok.image);

            if (retval > 0)
                {if (true) return retval;}
        }
        catch (NumberFormatException nfe) {
        }
        {if (true) throw new StandardException("Invalid column length: " + tok.image);}
    throw new Error("Missing return statement in function");
  }

  final public long exactNumber() throws ParseException, StandardException {
    Token longToken;
    String sign = "";
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
      sign = sign();
      break;
    default:
      jj_la1[82] = jj_gen;
      ;
    }
    longToken = jj_consume_token(EXACT_NUMERIC);
        try {
            {if (true) return Long.parseLong(getNumericString(longToken, sign));}
        }
        catch (NumberFormatException nfe) {
            {if (true) throw new StandardException("Invalid integer: " + longToken.image, nfe);}
        }
    throw new Error("Missing return statement in function");
  }

  final public int precision() throws ParseException, StandardException {
    int uintValue;
    uintValue = uint_value();
        {if (true) return uintValue;}
    throw new Error("Missing return statement in function");
  }

  final public int uint_value() throws ParseException, StandardException {
    Token uintToken;
    /* TODO: What is this comment about? ***
         * Because the parser won't match to UINT, we use EXACT_NUMERIC.
         */
        uintToken = jj_consume_token(EXACT_NUMERIC);
        try {
            {if (true) return Integer.parseInt(uintToken.image);}
        }
        catch (NumberFormatException nfe) {
            {if (true) throw new StandardException("Invalid integer: " + uintToken.image, nfe);}
        }
    throw new Error("Missing return statement in function");
  }

  final public int scale() throws ParseException, StandardException {
    int uintValue;
    uintValue = uint_value();
        {if (true) return uintValue;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor datetimeType() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case DATE:
      jj_consume_token(DATE);
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.DATE);}
      break;
    case TIME:
      jj_consume_token(TIME);
        // TODO: Not handling TIME (prec) or WITH TIME ZONE / WITHOUT TIME ZONE
        /*
            We do not try to set up a precision for time/timestamp
            values because this field gets mapped to the precision
            field in the JDBC driver that is for the number of
            decimal digits in the value.    Precision for time is
            actually the scale of the seconds value.

            If/when precision for times is supported, we may need
            to migrate the system catalog information to fill in
            the default values appropriately (the default for
            time is 0, fortunately; but for timestamp it is
            actually 9 due to java.sql.Timestamp's precision).
         */
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIME);}
      break;
    case TIMESTAMP:
      jj_consume_token(TIMESTAMP);
        // TODO; Ditto
        {if (true) return DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.TIMESTAMP);}
      break;
    case DATETIME:
      jj_consume_token(DATETIME);
        {if (true) return new DataTypeDescriptor(TypeId.DATETIME_ID, true);}
      break;
    case YEAR:
      jj_consume_token(YEAR);
        {if (true) return new DataTypeDescriptor(TypeId.YEAR_ID, true);}
      break;
    default:
      jj_la1[83] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor intervalType() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    jj_consume_token(INTERVAL);
    typeDescriptor = intervalQualifier();
        {if (true) return typeDescriptor;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor intervalQualifier() throws ParseException, StandardException {
    TypeId typeId, otherId = null;
    int[] precAndScale = new int[2];
    if ((getToken(2).kind == TO) ||
                           ((getToken(2).kind == LEFT_PAREN) &&
                            (getToken(5).kind == TO))) {
      typeId = intervalStartField(precAndScale);
      jj_consume_token(TO);
      otherId = intervalEndField(precAndScale);
            typeId = TypeId.intervalTypeId(typeId, otherId);
    } else {
      switch (jj_nt.kind) {
      case HOUR:
      case MINUTE:
      case SECOND:
      case YEAR:
      case DAY:
      case MONTH:
        typeId = intervalSingleField(precAndScale);
        break;
      default:
        jj_la1[84] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        {if (true) return new DataTypeDescriptor(typeId, precAndScale[0], precAndScale[1],
                                      true, DataTypeDescriptor.intervalMaxWidth(typeId, precAndScale[0], precAndScale[1]));}
    throw new Error("Missing return statement in function");
  }

  final public TypeId intervalStartField(int[] precAndScale) throws ParseException, StandardException {
    TypeId typeId;
    Token prec;
    typeId = intervalNonSecond();
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      prec = jj_consume_token(EXACT_NUMERIC);
      jj_consume_token(RIGHT_PAREN);
        precAndScale[0] = Integer.parseInt(prec.image);
      break;
    default:
      jj_la1[85] = jj_gen;
      ;
    }
        {if (true) return typeId;}
    throw new Error("Missing return statement in function");
  }

  final public TypeId intervalEndField(int[] precAndScale) throws ParseException, StandardException {
    TypeId typeId;
    Token scale;
    switch (jj_nt.kind) {
    case HOUR:
    case MINUTE:
    case YEAR:
    case DAY:
    case MONTH:
      typeId = intervalNonSecond();
        {if (true) return typeId;}
      break;
    case SECOND:
      jj_consume_token(SECOND);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        scale = jj_consume_token(EXACT_NUMERIC);
        jj_consume_token(RIGHT_PAREN);
        precAndScale[1] = Integer.parseInt(scale.image);
        break;
      default:
        jj_la1[86] = jj_gen;
        ;
      }
        {if (true) return TypeId.INTERVAL_SECOND_ID;}
      break;
    default:
      jj_la1[87] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public TypeId intervalSingleField(int[] precAndScale) throws ParseException, StandardException {
    TypeId typeId;
    Token prec = null, scale = null;
    switch (jj_nt.kind) {
    case HOUR:
    case MINUTE:
    case YEAR:
    case DAY:
    case MONTH:
      typeId = intervalNonSecond();
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        prec = jj_consume_token(EXACT_NUMERIC);
        jj_consume_token(RIGHT_PAREN);
        precAndScale[0] = Integer.parseInt(prec.image);
        break;
      default:
        jj_la1[88] = jj_gen;
        ;
      }
        {if (true) return typeId;}
      break;
    case SECOND:
      jj_consume_token(SECOND);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        prec = jj_consume_token(EXACT_NUMERIC);
        switch (jj_nt.kind) {
        case COMMA:
          jj_consume_token(COMMA);
          scale = jj_consume_token(EXACT_NUMERIC);
          break;
        default:
          jj_la1[89] = jj_gen;
          ;
        }
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[90] = jj_gen;
        ;
      }
        if (prec != null)
            precAndScale[0] = Integer.parseInt(prec.image);
        if (scale != null)
            precAndScale[1] = Integer.parseInt(scale.image);
        {if (true) return TypeId.INTERVAL_SECOND_ID;}
      break;
    default:
      jj_la1[91] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public TypeId intervalNonSecond() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case YEAR:
      jj_consume_token(YEAR);
      {if (true) return TypeId.INTERVAL_YEAR_ID;}
      break;
    case MONTH:
      jj_consume_token(MONTH);
      {if (true) return TypeId.INTERVAL_MONTH_ID;}
      break;
    case DAY:
      jj_consume_token(DAY);
      {if (true) return TypeId.INTERVAL_DAY_ID;}
      break;
    case HOUR:
      jj_consume_token(HOUR);
      {if (true) return TypeId.INTERVAL_HOUR_ID;}
      break;
    case MINUTE:
      jj_consume_token(MINUTE);
      {if (true) return TypeId.INTERVAL_MINUTE_ID;}
      break;
    default:
      jj_la1[92] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void qualifiedNameList(List<TableName> list) throws ParseException, StandardException {
    qualifiedNameElement(list);
    label_10:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[93] = jj_gen;
        break label_10;
      }
      jj_consume_token(COMMA);
      qualifiedNameElement(list);
    }
  }

  final public void qualifiedNameElement(List<TableName> list) throws ParseException, StandardException {
    TableName qualifiedName = null;
    qualifiedName = qualifiedName();
        list.add(qualifiedName);
  }

  final public TableName qualifiedName(int nodeType) throws ParseException, StandardException {
//String    catalogName = null;
    String  schemaName = null;
    String  qualifiedId;
    String  firstName = null;
    String  secondName = null;
    firstName = identifierDeferCheckLength();
    if (getToken(1).kind == PERIOD && getToken(2).kind != ASTERISK) {
      jj_consume_token(PERIOD);
      secondName = identifierDeferCheckLength();
    } else {
      ;
    }
        if (secondName == null) {
            qualifiedId = firstName;
        }
        else {
            schemaName = firstName;
            qualifiedId = secondName;
        }

        parserContext.checkIdentifierLengthLimit(qualifiedId);
        if (schemaName != null)
            parserContext.checkIdentifierLengthLimit(schemaName);

        {if (true) return (TableName)nodeFactory.getNode(nodeType,
                                              schemaName,
                                              qualifiedId,
                                              new Integer(lastIdentifierToken.beginOffset),
                                              new Integer(lastIdentifierToken.endOffset),
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
 * We have to be carefull to get the associativity correct. 
 * According to the SQL spec:
 *   <non-join query expression> ::=
 *       <non-join query term>
 *      | <query expression body> UNION [ ALL ] <query term>
 *      | <query expression body> EXCEPT [ ALL ] <query term>
 * Meaning that
 *   t1 UNION ALL t2 UNION t3
 * is equivalent to
 *   (t1 UNION ALL t2) UNION t3
 * However recursive descent parsers want recursion to be on the
 * right, so this kind of associativity is unnatural for our
 * parser. The queryExpression method must know whether it is being
 * called as the right hand side of a set operator to produce a query
 * tree with the correct associativity.
 */
  final public ResultSetNode queryExpression(ResultSetNode leftSide, int operatorType) throws ParseException, StandardException {
    ResultSetNode term;
    term = nonJoinQueryTerm(leftSide, operatorType);
    switch (jj_nt.kind) {
    case EXCEPT:
    case UNION:
      term = unionOrExcept(term);
      break;
    default:
      jj_la1[94] = jj_gen;
      ;
    }
        {if (true) return term;}
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode unionOrExcept(ResultSetNode term) throws ParseException, StandardException {
    ResultSetNode expression;
    Token tok = null;
    switch (jj_nt.kind) {
    case UNION:
      jj_consume_token(UNION);
      switch (jj_nt.kind) {
      case ALL:
      case DISTINCT:
        switch (jj_nt.kind) {
        case ALL:
          tok = jj_consume_token(ALL);
          break;
        case DISTINCT:
          jj_consume_token(DISTINCT);
          break;
        default:
          jj_la1[95] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[96] = jj_gen;
        ;
      }
      // TODO: Is this right? DISTINCT doesn't matter to queryExpression?
          expression = queryExpression(term, (tok != null) ? UNION_ALL_OP : UNION_OP);
        if ((tok != null) && (tok.kind == DISTINCT)) {
            forbidNextValueFor();
        }
        {if (true) return expression;}
      break;
    case EXCEPT:
      jj_consume_token(EXCEPT);
      switch (jj_nt.kind) {
      case ALL:
      case DISTINCT:
        switch (jj_nt.kind) {
        case ALL:
          tok = jj_consume_token(ALL);
          break;
        case DISTINCT:
          jj_consume_token(DISTINCT);
          break;
        default:
          jj_la1[97] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[98] = jj_gen;
        ;
      }
      expression = queryExpression(term, (tok != null) ? EXCEPT_ALL_OP : EXCEPT_OP);
        if ((tok != null) && (tok.kind == DISTINCT)) {
            forbidNextValueFor();
        }
        {if (true) return expression;}
      break;
    default:
      jj_la1[99] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

/*
 * Be careful with the associativity of INTERSECT. 
 * According to the SQL spec:
 *   t1 INTERSECT t2 INTERSECT ALL t3
 * is equivalent to
 *   (t1 INTERSECT t2) INTERSECT ALL t3
 * which is not the same as
 *   t1 INTERSECT (t2 INTERSECT ALL t3)
 * See the comment on queryExpression.
 */
  final public ResultSetNode nonJoinQueryTerm(ResultSetNode leftSide, int operatorType) throws ParseException, StandardException {
    ResultSetNode term;
    term = nonJoinQueryPrimary();
    switch (jj_nt.kind) {
    case INTERSECT:
      term = intersect(term);
      break;
    default:
      jj_la1[100] = jj_gen;
      ;
    }
        switch( operatorType) {
        case NO_SET_OP:
            {if (true) return term;}

        case UNION_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.UNION_NODE,
                                                      leftSide,
                                                      term,
                                                      Boolean.FALSE,
                                                      Boolean.FALSE,
                                                      null,
                                                      parserContext);}

        case UNION_ALL_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.UNION_NODE,
                                                      leftSide,
                                                      term,
                                                      Boolean.TRUE,
                                                      Boolean.FALSE,
                                                      null,
                                                      parserContext);}

        case EXCEPT_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.INTERSECT_OR_EXCEPT_NODE,
                                                      IntersectOrExceptNode.OpType.EXCEPT,
                                                      leftSide,
                                                      term,
                                                      Boolean.FALSE,
                                                      null,
                                                      parserContext);}

        case EXCEPT_ALL_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.INTERSECT_OR_EXCEPT_NODE,
                                                      IntersectOrExceptNode.OpType.EXCEPT,
                                                      leftSide,
                                                      term,
                                                      Boolean.TRUE,
                                                      null,
                                                      parserContext);}

        case INTERSECT_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.INTERSECT_OR_EXCEPT_NODE,
                                                      IntersectOrExceptNode.OpType.INTERSECT,
                                                      leftSide,
                                                      term,
                                                      Boolean.FALSE,
                                                      null,
                                                      parserContext);}

        case INTERSECT_ALL_OP:
            {if (true) return (ResultSetNode)nodeFactory.getNode(NodeTypes.INTERSECT_OR_EXCEPT_NODE,
                                                      IntersectOrExceptNode.OpType.INTERSECT,
                                                      leftSide,
                                                      term,
                                                      Boolean.TRUE,
                                                      null,
                                                      parserContext);}

        default:
            assert false : "Invalid set operator type: " + operatorType;
            {if (true) return null;}
        }
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode intersect(ResultSetNode term) throws ParseException, StandardException {
    ResultSetNode expression;
    Token tok = null;
    jj_consume_token(INTERSECT);
    switch (jj_nt.kind) {
    case ALL:
    case DISTINCT:
      switch (jj_nt.kind) {
      case ALL:
        tok = jj_consume_token(ALL);
        break;
      case DISTINCT:
        jj_consume_token(DISTINCT);
        break;
      default:
        jj_la1[101] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[102] = jj_gen;
      ;
    }
    expression = nonJoinQueryTerm(term, (tok != null) ? INTERSECT_ALL_OP : INTERSECT_OP);
        if ((tok != null) && (tok.kind == DISTINCT)) {
            forbidNextValueFor();
        }
        {if (true) return expression;}
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode nonJoinQueryPrimary() throws ParseException, StandardException {
    ResultSetNode    primary;
    switch (jj_nt.kind) {
    case SELECT:
    case VALUES:
      primary = simpleTable();
        {if (true) return primary;}
      break;
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      primary = queryExpression(null, NO_SET_OP);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return primary;}
      break;
    default:
      jj_la1[103] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode simpleTable() throws ParseException, StandardException {
    ResultSetNode resultSetNode;
    switch (jj_nt.kind) {
    case SELECT:
      resultSetNode = querySpecification();
        {if (true) return resultSetNode;}
      break;
    case VALUES:
      resultSetNode = tableValueConstructor();
        {if (true) return resultSetNode;}
      break;
    default:
      jj_la1[104] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode querySpecification() throws ParseException, StandardException {
    ResultColumnList selectList;
    SelectNode selectNode;
    boolean isDistinct = false;
    Token straightJoin = null;
    jj_consume_token(SELECT);
    if (straightJoinFollows()) {
      straightJoin = jj_consume_token(STRAIGHT_JOIN);
    } else {
      ;
    }
    if (jj_2_29(1)) {
      isDistinct = setQuantifier();
    } else {
      ;
    }
    if ((straightJoin == null) && straightJoinFollows()) {
      straightJoin = jj_consume_token(STRAIGHT_JOIN);
    } else {
      ;
    }
    selectList = selectList();
    selectNode = tableExpression(selectList);
        if (isDistinct) selectNode.makeDistinct();
        if (straightJoin != null) selectNode.makeStraightJoin();
        {if (true) return selectNode;}
    throw new Error("Missing return statement in function");
  }

  final public boolean setQuantifier() throws ParseException {
    if (getToken(1).kind == DISTINCT &&
                                     !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON)) {
      jj_consume_token(DISTINCT);
        forbidNextValueFor();
        {if (true) return true;}
    } else if (getToken(1).kind == ALL &&
                                     !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON)) {
      jj_consume_token(ALL);
        {if (true) return false;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList selectList() throws ParseException, StandardException {
    ResultColumn allResultColumn;
    ResultColumnList resultColumns = (ResultColumnList)
        nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                            parserContext);
    selectColumnList(resultColumns);
        {if (true) return resultColumns;}
    throw new Error("Missing return statement in function");
  }

  final public void selectColumnList(ResultColumnList resultColumns) throws ParseException, StandardException {
    selectSublist(resultColumns);
    label_11:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[105] = jj_gen;
        break label_11;
      }
      jj_consume_token(COMMA);
      selectSublist(resultColumns);
    }
  }

  final public void selectSublist(ResultColumnList resultColumns) throws ParseException, StandardException {
    ResultColumn resultColumn;
    ResultColumn allResultColumn;
    TableName tableName;
    switch (jj_nt.kind) {
    case ASTERISK:
      jj_consume_token(ASTERISK);
        allResultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.ALL_RESULT_COLUMN,
                                                            Boolean.FALSE,
                                                            parserContext);
        resultColumns.addResultColumn(allResultColumn);
      break;
    case ASTERISK_ASTERISK:
      jj_consume_token(ASTERISK_ASTERISK);
        allResultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.ALL_RESULT_COLUMN,
                                                            Boolean.TRUE,
                                                            parserContext);
        resultColumns.addResultColumn(allResultColumn);
      break;
    default:
      jj_la1[106] = jj_gen;
      if (getToken(2).kind == PERIOD &&
                                       (getToken(3).kind == ASTERISK ||
                                          (getToken(4).kind == PERIOD && getToken(5).kind == ASTERISK))) {
        tableName = qualifiedName();
        jj_consume_token(PERIOD);
        jj_consume_token(ASTERISK);
        allResultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.ALL_RESULT_COLUMN,
                                                            tableName,
                                                            parserContext);
        resultColumns.addResultColumn(allResultColumn);
      } else if (jj_2_30(1)) {
        resultColumn = derivedColumn(resultColumns);
        resultColumns.addResultColumn(resultColumn);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public ResultColumn derivedColumn(ResultColumnList resultColumns) throws ParseException, StandardException {
    ValueNode columnExpression;
    String columnName = null;
    columnExpression = valueExpression();
    if (jj_2_31(1)) {
      columnName = asClause();
    } else {
      ;
    }
        /* If there is no AS clause, and the expression is a simple
         * column, use the name of the column as the result column name.
         */
        if ((columnName == null) && (columnExpression instanceof ColumnReference)) {
            columnName = ((ColumnReference)columnExpression).getColumnName();
        }
        {if (true) return (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                 columnName,
                                                 columnExpression,
                                                 parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public String asClause() throws ParseException, StandardException {
    String columnName;
    switch (jj_nt.kind) {
    case AS:
      jj_consume_token(AS);
      break;
    default:
      jj_la1[107] = jj_gen;
      ;
    }
    columnName = identifier();
        {if (true) return columnName;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode valueExpression() throws ParseException, StandardException {
    ValueNode leftOperand;
    leftOperand = orExpression(null);
    label_12:
    while (true) {
      switch (jj_nt.kind) {
      case OR:
        ;
        break;
      default:
        jj_la1[108] = jj_gen;
        break label_12;
      }
      jj_consume_token(OR);
      leftOperand = orExpression(leftOperand);
    }
        {if (true) return leftOperand;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode orExpression(ValueNode farLeftOperand) throws ParseException, StandardException {
    ValueNode leftOperand;
    leftOperand = andExpression(null);
    label_13:
    while (true) {
      switch (jj_nt.kind) {
      case AND:
        ;
        break;
      default:
        jj_la1[109] = jj_gen;
        break label_13;
      }
      jj_consume_token(AND);
      leftOperand = andExpression(leftOperand);
    }
        if (farLeftOperand == null) {
            {if (true) return leftOperand;}
        }
        else {
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.OR_NODE,
                                                  farLeftOperand,
                                                  leftOperand,
                                                  parserContext);}
        }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode andExpression(ValueNode farLeftOperand) throws ParseException, StandardException {
    Token notToken = null;
    ValueNode test;
    if (getToken(1).kind == NOT &&
                                         !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON)) {
      notToken = jj_consume_token(NOT);
    } else {
      ;
    }
    test = isSearchCondition();
        /* Put any NOT on top of test */
        if (notToken != null) {
            test = (ValueNode)nodeFactory.getNode(NodeTypes.NOT_NODE,
                                                  test,
                                                  parserContext);
        }
        if (farLeftOperand != null) {
            test = (ValueNode)nodeFactory.getNode(NodeTypes.AND_NODE,
                                                  farLeftOperand,
                                                  test,
                                                  parserContext);
        }
        {if (true) return test;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode isSearchCondition() throws ParseException, StandardException {
    ValueNode result;
    ValueNode booleanPrimary;
    Token isToken = null;
    Token notToken = null;
    ValueNode truthValue = null;
    booleanPrimary = booleanPrimary();
    switch (jj_nt.kind) {
    case IS:
      isToken = jj_consume_token(IS);
      switch (jj_nt.kind) {
      case NOT:
        notToken = jj_consume_token(NOT);
        break;
      default:
        jj_la1[110] = jj_gen;
        ;
      }
      truthValue = truthValue();
      break;
    default:
      jj_la1[111] = jj_gen;
      ;
    }
        if (isToken != null) {
            if (truthValue == null)
                result = (ValueNode)nodeFactory.getNode(NodeTypes.IS_NULL_NODE,
                                                        booleanPrimary,
                                                        parserContext);
            else
                result = (ValueNode)nodeFactory.getNode(NodeTypes.IS_NODE,
                                                        booleanPrimary,
                                                        truthValue,
                                                        parserContext);
            /* Put any NOT on top of the tree */
            if (notToken != null) {
                result = (ValueNode)nodeFactory.getNode(NodeTypes.NOT_NODE,
                                                        result,
                                                        parserContext);
            }
        }
        else {
            result = booleanPrimary;
        }
        {if (true) return result;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode truthValue() throws ParseException, StandardException {
    Boolean value;
    switch (jj_nt.kind) {
    case NULL:
      jj_consume_token(NULL);
             {if (true) return null;}
      break;
    case TRUE:
      jj_consume_token(TRUE);
             value = Boolean.TRUE;
      break;
    case FALSE:
      jj_consume_token(FALSE);
              value = Boolean.FALSE;
      break;
    case UNKNOWN:
      jj_consume_token(UNKNOWN);
                value = null;
      break;
    default:
      jj_la1[112] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return (ValueNode)
            nodeFactory.getNode(NodeTypes.BOOLEAN_CONSTANT_NODE,
                                value,
                                parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode booleanPrimary() throws ParseException, StandardException {
    ValueNode primary;
    ValueNode searchCondition;
    primary = predicate();
        {if (true) return  primary;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode predicate() throws ParseException, StandardException {
    ValueNode value;
    if (rowValueConstructorListFollows()) {
      value = rowCtor(new int[] {0});
    } else if (jj_2_32(1)) {
      value = additiveExpression();
    } else {
      switch (jj_nt.kind) {
      case EXISTS:
        value = existsExpression();
        break;
      default:
        jj_la1[113] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    label_14:
    while (true) {
      if (remainingPredicateFollows()) {
        ;
      } else {
        break label_14;
      }
      value = remainingPredicate(value);
    }
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode remainingPredicate(ValueNode value) throws ParseException, StandardException {
    Token notToken = null;
    switch (jj_nt.kind) {
    case LESS_THAN_OPERATOR:
    case LESS_THAN_OR_EQUALS_OPERATOR:
    case EQUALS_OPERATOR:
    case NOT_EQUALS_OPERATOR:
    case NOT_EQUALS_OPERATOR2:
    case GREATER_THAN_OPERATOR:
    case GREATER_THAN_OR_EQUALS_OPERATOR:
      value = remainingNonNegatablePredicate(value);
        {if (true) return value;}
      break;
    case BETWEEN:
    case IN:
    case LIKE:
    case NOT:
    case DUMMY:
      switch (jj_nt.kind) {
      case NOT:
        notToken = jj_consume_token(NOT);
        break;
      default:
        jj_la1[114] = jj_gen;
        ;
      }
      value = remainingNegatablePredicate(value);
        /* Put any NOT on top of the tree */
        if (notToken != null) {
            value = (ValueNode)nodeFactory.getNode(NodeTypes.NOT_NODE,
                                                   value,
                                                   parserContext);
        }
        {if (true) return value;}
      break;
    default:
      jj_la1[115] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode remainingNonNegatablePredicate(ValueNode leftOperand) throws ParseException, StandardException {
    BinaryOperatorNode.OperatorType operator;
    SubqueryNode.SubqueryType subqueryType;
    String javaClassName;
    Token tok = null;
    ValueNode tree = null;
    ValueNode likePattern;
    ValueNode betweenLeft;
    ValueNode betweenRight;
    operator = compOp();
    if ((getToken(1).kind == ALL || getToken(1).kind == ANY ||
                                        getToken(1).kind == SOME)
                                         && getToken(2).kind == LEFT_PAREN) {
      subqueryType = quantifier(operator);
      jj_consume_token(LEFT_PAREN);
      leftOperand = tableSubquery(subqueryType, leftOperand);
      jj_consume_token(RIGHT_PAREN);
    } else if (jj_2_33(1)) {
      //            leftOperand = rowCtor()
        //          |
                  leftOperand = additiveExpression(leftOperand, operator);
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return leftOperand;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode remainingNegatablePredicate(ValueNode leftOperand) throws ParseException, StandardException {
    ValueNode tree = null;
    ValueNode likePattern;
    ValueNode betweenLeft;
    ValueNode betweenRight;
    ValueNode escapeValue = null;
    switch (jj_nt.kind) {
    case DUMMY:
      jj_consume_token(DUMMY);
      tree = rowCtor(new int[]{0});
        {if (true) return tree;}
      break;
    case IN:
      jj_consume_token(IN);
      tree = inPredicateValue(leftOperand);
        {if (true) return tree;}
      break;
    case LIKE:
      jj_consume_token(LIKE);
      likePattern = additiveExpression();
      switch (jj_nt.kind) {
      case ESCAPE:
      case LEFT_BRACE:
        switch (jj_nt.kind) {
        case ESCAPE:
          jj_consume_token(ESCAPE);
          escapeValue = additiveExpression();
          break;
        case LEFT_BRACE:
          jj_consume_token(LEFT_BRACE);
          jj_consume_token(ESCAPE);
          escapeValue = additiveExpression();
          jj_consume_token(RIGHT_BRACE);
          break;
        default:
          jj_la1[116] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[117] = jj_gen;
        ;
      }
        tree = (ValueNode)nodeFactory.getNode(NodeTypes.LIKE_OPERATOR_NODE,
                                              leftOperand,
                                              likePattern,
                                              escapeValue,
                                              parserContext);

        {if (true) return tree;}
      break;
    case BETWEEN:
      jj_consume_token(BETWEEN);
      betweenLeft = additiveExpression();
      jj_consume_token(AND);
      betweenRight = additiveExpression();
        ValueNodeList betweenList = (ValueNodeList)
            nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST,
                                parserContext);
        betweenList.addValueNode(betweenLeft);
        betweenList.addValueNode(betweenRight);
        tree = (ValueNode)nodeFactory.getNode(NodeTypes.BETWEEN_OPERATOR_NODE,
                                              leftOperand,
                                              betweenList,
                                              parserContext);

        {if (true) return tree;}
      break;
    default:
      jj_la1[118] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType compOp() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case EQUALS_OPERATOR:
      jj_consume_token(EQUALS_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.EQ;}
      break;
    case NOT_EQUALS_OPERATOR:
      jj_consume_token(NOT_EQUALS_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.NE;}
      break;
    case NOT_EQUALS_OPERATOR2:
      jj_consume_token(NOT_EQUALS_OPERATOR2);
        {if (true) return BinaryOperatorNode.OperatorType.NE;}
      break;
    case LESS_THAN_OPERATOR:
      jj_consume_token(LESS_THAN_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.LT;}
      break;
    case GREATER_THAN_OPERATOR:
      jj_consume_token(GREATER_THAN_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.GT;}
      break;
    case LESS_THAN_OR_EQUALS_OPERATOR:
      jj_consume_token(LESS_THAN_OR_EQUALS_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.LE;}
      break;
    case GREATER_THAN_OR_EQUALS_OPERATOR:
      jj_consume_token(GREATER_THAN_OR_EQUALS_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.GE;}
      break;
    default:
      jj_la1[119] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode additiveExpression(ValueNode farLeftOperand, BinaryOperatorNode.OperatorType compOp) throws ParseException, StandardException {
    ValueNode leftOperand;
    BinaryOperatorNode.OperatorType operator;
    String collation = null;
    leftOperand = multiplicativeExpression(null, null);
    label_15:
    while (true) {
      if (jj_2_34(1)) {
        ;
      } else {
        break label_15;
      }
      operator = additiveOperator();
      leftOperand = multiplicativeExpression(leftOperand, operator);
    }
    switch (jj_nt.kind) {
    case COLLATE:
      collation = collateClause();
      break;
    default:
      jj_la1[120] = jj_gen;
      ;
    }
        if (farLeftOperand != null) {
            int nodeType;
            switch (compOp) {
            case EQ:
                nodeType = NodeTypes.BINARY_EQUALS_OPERATOR_NODE;
                break;

            case NE:
                nodeType = NodeTypes.BINARY_NOT_EQUALS_OPERATOR_NODE;
                break;

            case LT:
                nodeType = NodeTypes.BINARY_LESS_THAN_OPERATOR_NODE;
                break;

            case GT:
                nodeType = NodeTypes.BINARY_GREATER_THAN_OPERATOR_NODE;
                break;

            case LE:
                nodeType = NodeTypes.BINARY_LESS_EQUALS_OPERATOR_NODE;
                break;

            case GE:
                nodeType = NodeTypes.BINARY_GREATER_EQUALS_OPERATOR_NODE;
                break;

            default:
                assert false : "Unknown comparison operator " + compOp;
                nodeType = 0;
                break;
            }
            leftOperand = (ValueNode)nodeFactory.getNode(nodeType,
                                                         farLeftOperand,
                                                         leftOperand,
                                                         parserContext);
        }
        if (collation != null)
            leftOperand = (ValueNode)nodeFactory.getNode(NodeTypes.EXPLICIT_COLLATE_NODE,
                                                         leftOperand,
                                                         collation,
                                                         parserContext);
        {if (true) return leftOperand;}
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType additiveOperator() throws ParseException, StandardException {
    BinaryOperatorNode.OperatorType operator;
    switch (jj_nt.kind) {
    case PLUS_SIGN:
      jj_consume_token(PLUS_SIGN);
        {if (true) return BinaryOperatorNode.OperatorType.PLUS;}
      break;
    case MINUS_SIGN:
      jj_consume_token(MINUS_SIGN);
        {if (true) return BinaryOperatorNode.OperatorType.MINUS;}
      break;
    default:
      jj_la1[121] = jj_gen;
      if (infixBitFollows()) {
        operator = infixBitOperator();
        {if (true) return operator;}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType infixBitOperator() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case AMPERSAND:
      jj_consume_token(AMPERSAND);
        {if (true) return BinaryOperatorNode.OperatorType.BITAND;}
      break;
    case VERTICAL_BAR:
      jj_consume_token(VERTICAL_BAR);
        {if (true) return BinaryOperatorNode.OperatorType.BITOR;}
      break;
    case CARET:
      jj_consume_token(CARET);
        {if (true) return BinaryOperatorNode.OperatorType.BITXOR;}
      break;
    case DOUBLE_LESS:
      jj_consume_token(DOUBLE_LESS);
        {if (true) return BinaryOperatorNode.OperatorType.LEFT_SHIFT;}
      break;
    case DOUBLE_GREATER:
      jj_consume_token(DOUBLE_GREATER);
        {if (true) return BinaryOperatorNode.OperatorType.RIGHT_SHIFT;}
      break;
    default:
      jj_la1[122] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode multiplicativeExpression(ValueNode farLeftOperand,
                         BinaryOperatorNode.OperatorType additiveOperator) throws ParseException, StandardException {
    ValueNode leftOperand;
    BinaryOperatorNode.OperatorType multOp;
    leftOperand = unaryExpression(null, null);
    label_16:
    while (true) {
      if (jj_2_35(1)) {
        ;
      } else {
        break label_16;
      }
      multOp = multiplicativeOperator();
      leftOperand = unaryExpression(leftOperand, multOp);
    }
        if (farLeftOperand == null)
            {if (true) return leftOperand;}

        switch (additiveOperator) {
        case PLUS:
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_PLUS_OPERATOR_NODE,
                                                  farLeftOperand,
                                                  leftOperand,
                                                  parserContext);}

        case MINUS:
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_MINUS_OPERATOR_NODE,
                                                  farLeftOperand,
                                                  leftOperand,
                                                  parserContext);}

        case BITAND:
        case BITOR:
        case BITXOR:
        case LEFT_SHIFT:
        case RIGHT_SHIFT:
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_BIT_OPERATOR_NODE,
                                                  additiveOperator,
                                                  farLeftOperand,
                                                  leftOperand,
                                                  parserContext);}

        default:
            assert false : "Unexpected operator value of " + additiveOperator;
            {if (true) return null;}
        }
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType multiplicativeOperator() throws ParseException, StandardException {
    if (infixModFollows()) {
      switch (jj_nt.kind) {
      case MOD:
        jj_consume_token(MOD);
        break;
      case PERCENT:
        jj_consume_token(PERCENT);
        break;
      default:
        jj_la1[123] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return BinaryOperatorNode.OperatorType.MOD;}
    } else if (divOperatorFollows()) {
      jj_consume_token(DIV);
        {if (true) return BinaryOperatorNode.OperatorType.DIV;}
    } else {
      switch (jj_nt.kind) {
      case ASTERISK:
        jj_consume_token(ASTERISK);
        {if (true) return BinaryOperatorNode.OperatorType.TIMES;}
        break;
      case SOLIDUS:
        jj_consume_token(SOLIDUS);
        {if (true) return BinaryOperatorNode.OperatorType.DIVIDE;}
        break;
      case CONCATENATION_OPERATOR:
        jj_consume_token(CONCATENATION_OPERATOR);
        {if (true) return BinaryOperatorNode.OperatorType.CONCATENATE;}
        break;
      default:
        jj_la1[124] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode unaryExpression(ValueNode farLeftOperand,
                BinaryOperatorNode.OperatorType multiplicativeOperator) throws ParseException, StandardException {
    ValueNode value;
    String sign = null;
    if (jj_2_36(1)) {
      if (unaryArithmeticFollows()) {
        sign = sign();
      } else if (unaryBitFollows()) {
        sign = unaryBitOperator();
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    } else {
      ;
    }
    value = primaryExpression();
        if ("-".equals(sign)) {
            value = (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_MINUS_OPERATOR_NODE,
                                                   value,
                                                   parserContext);
        }
        else if ("+".equals(sign)) {
            value = (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_PLUS_OPERATOR_NODE,
                                                   value,
                                                   parserContext);
        }
        else if ("~".equals(sign)) {
            value = (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_BITNOT_OPERATOR_NODE,
                                                   value,
                                                   parserContext);
        }
        else {
            assert (sign == null) : "Unknown unary operator '" + sign + "'";
        }
        {if (true) return multOp(farLeftOperand, value, multiplicativeOperator);}
    throw new Error("Missing return statement in function");
  }

  final public String sign() throws ParseException {
    Token s;
    switch (jj_nt.kind) {
    case PLUS_SIGN:
      s = jj_consume_token(PLUS_SIGN);
        {if (true) return s.image;}
      break;
    case MINUS_SIGN:
      s = jj_consume_token(MINUS_SIGN);
        {if (true) return s.image;}
      break;
    default:
      jj_la1[125] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public String unaryBitOperator() throws ParseException {
    Token s;
    s = jj_consume_token(TILDE);
        {if (true) return s.image;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode primaryExpressionXX() throws ParseException, StandardException {
    ValueNode value;
    value = primary();
    label_17:
    while (true) {
      if (jj_2_37(1)) {
        ;
      } else {
        break label_17;
      }
      value = nonStaticMethodCallOrFieldAccess(value);
    }
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode nonStaticMethodCallOrFieldAccess(ValueNode receiver) throws ParseException, StandardException {
    ValueNode value;
    value = nonStaticMethodInvocation(receiver);
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode nonStaticMethodInvocation(ValueNode receiver) throws ParseException, StandardException {
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    MethodCallNode methodNode;
    ParameterNode parameterNode;
    if (getToken(3).kind == LEFT_PAREN) {
      switch (jj_nt.kind) {
      case FIELD_REFERENCE:
        jj_consume_token(FIELD_REFERENCE);
        break;
      case PERIOD:
        jj_consume_token(PERIOD);
        break;
      default:
        jj_la1[126] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      methodNode = methodName(receiver);
      methodCallParameterList(parameterList);
        /*
        ** ? parameters are not allowed for the receiver --
        ** unless the receiver is standing in for a named parameter,
        ** whose type is therefore known.
        */
        if (receiver instanceof ParameterNode) {
            {if (true) throw new StandardException("Parameter not allowed for method receiver");}
        }
        methodNode.addParms(parameterList);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              methodNode,
                                              parserContext);}
    } else {
      switch (jj_nt.kind) {
      case PERIOD:
        jj_consume_token(PERIOD);
        methodNode = methodName(receiver);
        /*
        ** ? parameters are not allowed for the receiver --
        ** unless the receiver is standing in for a named parameter,
        ** whose type is therefore known.
        */
        if (receiver instanceof ParameterNode) {
            {if (true) throw new StandardException("Parameter not allowed for method receiver");}
        }

        methodNode.addParms(parameterList);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              methodNode,
                                              parserContext);}
        break;
      default:
        jj_la1[127] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public MethodCallNode methodName(ValueNode receiver) throws ParseException, StandardException {
    String methodName;
    /*
        ** NOTE: allowing a delimited identifier as a method name is necessary,
        ** because Java is case-sensitive.  But this also allows identifiers that
        ** do not match Java syntax.    This will probably not cause a problem
        ** in later phases, like binding and code generation.
        */
        methodName = caseSensitiveIdentifierPlusReservedWords();
        {if (true) return (MethodCallNode)nodeFactory.getNode(NodeTypes.NON_STATIC_METHOD_CALL_NODE,
                                                   methodName,
                                                   receiver,
                                                   parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public MethodCallNode staticMethodName(String javaClassName) throws ParseException, StandardException {
    String methodName;
    /*
        ** NOTE: allowing a delimited identifier as a method name is necessary,
        ** because Java is case-sensitive.  But this also allows identifiers that
        ** do not match Java syntax.    This will probably not cause a problem
        ** in later phases, like binding and code generation.
        */
        methodName = caseSensitiveIdentifierPlusReservedWords();
        {if (true) return (MethodCallNode)nodeFactory.getNode(NodeTypes.STATIC_METHOD_CALL_NODE,
                                                   methodName,
                                                   javaClassName,
                                                   parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void methodParameter(List<ValueNode> parameterList) throws ParseException, StandardException {
    ValueNode parameter;
    parameter = valueExpression();
        parameterList.add(parameter);
  }

  final public ValueNode primary() throws ParseException, StandardException {
    String javaClassName;
    ValueNode value;
    if (javaClassFollows()) {
      value = staticClassReference();
        {if (true) return value;}
    } else if (jj_2_38(1)) {
      value = valueExpressionPrimary();
        {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode staticClassReference() throws ParseException, StandardException {
    String javaClassName;
    ValueNode value;
    javaClassName = javaClass();
    jj_consume_token(DOUBLE_COLON);
    value = staticClassReferenceType(javaClassName);
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode staticClassReferenceType(String javaClassName) throws ParseException, StandardException {
    ValueNode value;
    if ((getToken(2).kind == LEFT_PAREN)) {
      value = staticMethodInvocation(javaClassName);
        {if (true) return value;}
    } else if (jj_2_39(1)) {
      value = staticClassFieldReference(javaClassName);
        {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode staticClassFieldReference(String javaClassName) throws ParseException, StandardException {
    String fieldName;
    fieldName = caseSensitiveIdentifierPlusReservedWords();
        {if (true) return (ValueNode)
            nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                nodeFactory.getNode(NodeTypes.STATIC_CLASS_FIELD_REFERENCE_NODE,
                                                    javaClassName,
                                                    fieldName,
                                                    nextToLastTokenDelimitedIdentifier,
                                                    parserContext),
                                parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ExtractOperatorNode.Field nonSecondDatetimeField() throws ParseException {
    switch (jj_nt.kind) {
    case YEAR:
      jj_consume_token(YEAR);
        {if (true) return ExtractOperatorNode.Field.YEAR;}
      break;
    case MONTH:
      jj_consume_token(MONTH);
        {if (true) return ExtractOperatorNode.Field.MONTH;}
      break;
    case DAY:
      jj_consume_token(DAY);
        {if (true) return ExtractOperatorNode.Field.DAY;}
      break;
    case HOUR:
      jj_consume_token(HOUR);
        {if (true) return ExtractOperatorNode.Field.HOUR;}
      break;
    case MINUTE:
      jj_consume_token(MINUTE);
        {if (true) return ExtractOperatorNode.Field.MINUTE;}
      break;
    default:
      jj_la1[128] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode escapedValueFunction() throws ParseException, StandardException {
    ValueNode value;
    ValueNode str1;
    ValueNode str2;
    ValueNode startPosition;
    ValueNode length = null;
    if (jj_2_40(1)) {
      value = miscBuiltinsCore(true);
        {if (true) return value;}
    } else {
      switch (jj_nt.kind) {
      case CURDATE:
        jj_consume_token(CURDATE);
        jj_consume_token(LEFT_PAREN);
        jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.DATE,
                                              parserContext);}
        break;
      case CURTIME:
        jj_consume_token(CURTIME);
        jj_consume_token(LEFT_PAREN);
        jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.TIME,
                                              parserContext);}
        break;
      case CONCAT:
        jj_consume_token(CONCAT);
        jj_consume_token(LEFT_PAREN);
        str1 = additiveExpression();
        jj_consume_token(COMMA);
        str2 = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CONCATENATION_OPERATOR_NODE,
                                              str1,
                                              str2,
                                              parserContext);}
        break;
      case CURRENT_USER:
      case SESSION_USER:
      case USER:
        /* Method versions of USER special registers are ODBC remnants.
             * Only supported when escaped.
             */
            value = userNode();
        jj_consume_token(LEFT_PAREN);
        jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
        break;
      default:
        jj_la1[129] = jj_gen;
        if (getEscapedSYSFUN(getToken(1).image) != null) {
          value = escapedSYSFUNFunction();
        {if (true) return value;}
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode escapedSYSFUNFunction() throws ParseException, StandardException {
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    Token tok;
    tok = jj_consume_token(IDENTIFIER);
    methodCallParameterList(parameterList);
        String sysFunName = getEscapedSYSFUN(tok.image);

        TableName functionName = (TableName)nodeFactory.getNode(NodeTypes.TABLE_NAME,
                                                                IBM_SYSTEM_FUN_SCHEMA_NAME,
                                                                sysFunName,
                                                                new Integer(0),
                                                                new Integer(0),
                                                                parserContext);

        MethodCallNode methodNode = (MethodCallNode)nodeFactory.getNode(NodeTypes.STATIC_METHOD_CALL_NODE,
                                                                        functionName,
                                                                        null,
                                                                        parserContext);

        methodNode.addParms(parameterList);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              methodNode,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode timestampArithmeticFuncion() throws ParseException, StandardException {
    DataTypeDescriptor intervalTypeDesc;
    ValueNode intervalType;
    ValueNode tstamp1;
    ValueNode tstamp2;
    ValueNode interval;
    int[] factors = new int[] { 1, 1 };
    switch (jj_nt.kind) {
    case TIMESTAMPADD:
      jj_consume_token(TIMESTAMPADD);
      jj_consume_token(LEFT_PAREN);
      intervalTypeDesc = jdbcIntervalTypeDescriptor(factors);
      jj_consume_token(COMMA);
      interval = additiveExpression();
      jj_consume_token(COMMA);
      tstamp1 = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        interval = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                               interval, intervalTypeDesc,
                                               parserContext);
        if (factors[0] != 1)
            interval = (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_TIMES_OPERATOR_NODE,
                                                   interval,
                                                   nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                                                       Integer.valueOf(factors[0]),
                                                                       parserContext),
                                                   parserContext);
        if (factors[1] != 1)
            interval = (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_DIVIDE_OPERATOR_NODE,
                                                   interval,
                                                   nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                                                       Integer.valueOf(factors[1]),
                                                                       parserContext),
                                                   parserContext);

        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_PLUS_OPERATOR_NODE,
                                                  tstamp1,
                                                  interval,
                                                  parserContext);}
      break;
    case TIMESTAMPDIFF:
      jj_consume_token(TIMESTAMPDIFF);
      jj_consume_token(LEFT_PAREN);
      intervalType = jdbcIntervalType();
      jj_consume_token(COMMA);
      tstamp1 = additiveExpression();
      jj_consume_token(COMMA);
      tstamp2 = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.TIMESTAMP_DIFF_FN_NODE,
                                              intervalType,
                                              tstamp1,
                                              tstamp2,
                                              TernaryOperatorNode.OperatorType.TIMESTAMPDIFF,
                                              null,
                                              parserContext);}
      break;
    default:
      jj_la1[130] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode jdbcIntervalType() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case SQL_TSI_FRAC_SECOND:
    case MICROSECOND:
      switch (jj_nt.kind) {
      case SQL_TSI_FRAC_SECOND:
        jj_consume_token(SQL_TSI_FRAC_SECOND);
        break;
      case MICROSECOND:
        jj_consume_token(MICROSECOND);
        break;
      default:
        jj_la1[131] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.FRAC_SECOND_INTERVAL);}
      break;
    case SECOND:
    case SQL_TSI_SECOND:
      switch (jj_nt.kind) {
      case SQL_TSI_SECOND:
        jj_consume_token(SQL_TSI_SECOND);
        break;
      case SECOND:
        jj_consume_token(SECOND);
        break;
      default:
        jj_la1[132] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.SECOND_INTERVAL);}
      break;
    case MINUTE:
    case SQL_TSI_MINUTE:
      switch (jj_nt.kind) {
      case SQL_TSI_MINUTE:
        jj_consume_token(SQL_TSI_MINUTE);
        break;
      case MINUTE:
        jj_consume_token(MINUTE);
        break;
      default:
        jj_la1[133] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.MINUTE_INTERVAL);}
      break;
    case HOUR:
    case SQL_TSI_HOUR:
      switch (jj_nt.kind) {
      case SQL_TSI_HOUR:
        jj_consume_token(SQL_TSI_HOUR);
        break;
      case HOUR:
        jj_consume_token(HOUR);
        break;
      default:
        jj_la1[134] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.HOUR_INTERVAL);}
      break;
    case DAY:
    case SQL_TSI_DAY:
      switch (jj_nt.kind) {
      case SQL_TSI_DAY:
        jj_consume_token(SQL_TSI_DAY);
        break;
      case DAY:
        jj_consume_token(DAY);
        break;
      default:
        jj_la1[135] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.DAY_INTERVAL);}
      break;
    case SQL_TSI_WEEK:
    case WEEK:
      switch (jj_nt.kind) {
      case SQL_TSI_WEEK:
        jj_consume_token(SQL_TSI_WEEK);
        break;
      case WEEK:
        jj_consume_token(WEEK);
        break;
      default:
        jj_la1[136] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.WEEK_INTERVAL);}
      break;
    case MONTH:
    case SQL_TSI_MONTH:
      switch (jj_nt.kind) {
      case SQL_TSI_MONTH:
        jj_consume_token(SQL_TSI_MONTH);
        break;
      case MONTH:
        jj_consume_token(MONTH);
        break;
      default:
        jj_la1[137] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.MONTH_INTERVAL);}
      break;
    case SQL_TSI_QUARTER:
    case QUARTER:
      switch (jj_nt.kind) {
      case SQL_TSI_QUARTER:
        jj_consume_token(SQL_TSI_QUARTER);
        break;
      case QUARTER:
        jj_consume_token(QUARTER);
        break;
      default:
        jj_la1[138] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.QUARTER_INTERVAL);}
      break;
    case YEAR:
    case SQL_TSI_YEAR:
      switch (jj_nt.kind) {
      case SQL_TSI_YEAR:
        jj_consume_token(SQL_TSI_YEAR);
        break;
      case YEAR:
        jj_consume_token(YEAR);
        break;
      default:
        jj_la1[139] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return getJdbcIntervalNode(TernaryOperatorNode.YEAR_INTERVAL);}
      break;
    default:
      jj_la1[140] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor jdbcIntervalTypeDescriptor(int[] factors) throws ParseException, StandardException {
    TypeId typeId;
    int prec = 0, scale = 0;
    switch (jj_nt.kind) {
    case SQL_TSI_FRAC_SECOND:
    case MICROSECOND:
      switch (jj_nt.kind) {
      case SQL_TSI_FRAC_SECOND:
        jj_consume_token(SQL_TSI_FRAC_SECOND);
        break;
      case MICROSECOND:
        jj_consume_token(MICROSECOND);
        break;
      default:
        jj_la1[141] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_SECOND_ID;
        scale = 6;
        factors[1] = 1000000;
      break;
    case SECOND:
    case SQL_TSI_SECOND:
      switch (jj_nt.kind) {
      case SQL_TSI_SECOND:
        jj_consume_token(SQL_TSI_SECOND);
        break;
      case SECOND:
        jj_consume_token(SECOND);
        break;
      default:
        jj_la1[142] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_SECOND_ID;
      break;
    case MINUTE:
    case SQL_TSI_MINUTE:
      switch (jj_nt.kind) {
      case SQL_TSI_MINUTE:
        jj_consume_token(SQL_TSI_MINUTE);
        break;
      case MINUTE:
        jj_consume_token(MINUTE);
        break;
      default:
        jj_la1[143] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_MINUTE_ID;
      break;
    case HOUR:
    case SQL_TSI_HOUR:
      switch (jj_nt.kind) {
      case SQL_TSI_HOUR:
        jj_consume_token(SQL_TSI_HOUR);
        break;
      case HOUR:
        jj_consume_token(HOUR);
        break;
      default:
        jj_la1[144] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_HOUR_ID;
      break;
    case DAY:
    case SQL_TSI_DAY:
      switch (jj_nt.kind) {
      case SQL_TSI_DAY:
        jj_consume_token(SQL_TSI_DAY);
        break;
      case DAY:
        jj_consume_token(DAY);
        break;
      default:
        jj_la1[145] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_DAY_ID;
      break;
    case SQL_TSI_WEEK:
    case WEEK:
      switch (jj_nt.kind) {
      case SQL_TSI_WEEK:
        jj_consume_token(SQL_TSI_WEEK);
        break;
      case WEEK:
        jj_consume_token(WEEK);
        break;
      default:
        jj_la1[146] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_DAY_ID;
        factors[0] = 7;
      break;
    case MONTH:
    case SQL_TSI_MONTH:
      switch (jj_nt.kind) {
      case SQL_TSI_MONTH:
        jj_consume_token(SQL_TSI_MONTH);
        break;
      case MONTH:
        jj_consume_token(MONTH);
        break;
      default:
        jj_la1[147] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_MONTH_ID;
      break;
    case SQL_TSI_QUARTER:
    case QUARTER:
      switch (jj_nt.kind) {
      case SQL_TSI_QUARTER:
        jj_consume_token(SQL_TSI_QUARTER);
        break;
      case QUARTER:
        jj_consume_token(QUARTER);
        break;
      default:
        jj_la1[148] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_MONTH_ID;
        factors[0] = 3;
      break;
    case YEAR:
    case SQL_TSI_YEAR:
      switch (jj_nt.kind) {
      case SQL_TSI_YEAR:
        jj_consume_token(SQL_TSI_YEAR);
        break;
      case YEAR:
        jj_consume_token(YEAR);
        break;
      default:
        jj_la1[149] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        typeId = TypeId.INTERVAL_YEAR_ID;
      break;
    default:
      jj_la1[150] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return new DataTypeDescriptor(typeId, prec, scale,
                                      true, DataTypeDescriptor.intervalMaxWidth(typeId, prec, scale));}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode numericValueFunction() throws ParseException, StandardException {
    ValueNode value;
    int field;
    switch (jj_nt.kind) {
    case ABS:
      jj_consume_token(ABS);
      value = absFunction();
        {if (true) return value;}
      break;
    case ABSVAL:
      jj_consume_token(ABSVAL);
      value = absFunction();
        {if (true) return value;}
      break;
    case SQRT:
      jj_consume_token(SQRT);
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.SQRT_OPERATOR_NODE,
                                              value,
                                              parserContext);}
      break;
    case MOD:
      jj_consume_token(MOD);
      value = modFunction();
        {if (true) return value;}
      break;
    case IDENTITY_VAL_LOCAL:
      jj_consume_token(IDENTITY_VAL_LOCAL);
      jj_consume_token(LEFT_PAREN);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.IDENTITY_VAL_NODE,
                                              parserContext);}
      break;
    default:
      jj_la1[151] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode coalesceFunction(String coalesceOrValue) throws ParseException, StandardException {
    ValueNodeList expressionList = (ValueNodeList)nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST,
                                                                      parserContext);
    jj_consume_token(LEFT_PAREN);
    coalesceExpression(expressionList);
    label_18:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[152] = jj_gen;
        break label_18;
      }
      jj_consume_token(COMMA);
      coalesceExpression(expressionList);
    }
    jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.COALESCE_FUNCTION_NODE,
                                              coalesceOrValue,
                                              expressionList,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void coalesceExpression(ValueNodeList expressionList) throws ParseException, StandardException {
    ValueNode expression;
    expression = additiveExpression();
        expressionList.addValueNode(expression);
  }

  final public ValueNode absFunction() throws ParseException, StandardException {
    ValueNode value;
    jj_consume_token(LEFT_PAREN);
    value = additiveExpression();
    jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.ABSOLUTE_OPERATOR_NODE,
                                              value,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode modFunction() throws ParseException, StandardException {
    ValueNode int1;
    ValueNode int2;
    jj_consume_token(LEFT_PAREN);
    int1 = additiveExpression();
    jj_consume_token(COMMA);
    int2 = additiveExpression();
    jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.MOD_OPERATOR_NODE,
                                              int1, int2,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ExtractOperatorNode.Field datetimeField() throws ParseException {
    ExtractOperatorNode.Field field;
    switch (jj_nt.kind) {
    case HOUR:
    case MINUTE:
    case YEAR:
    case DAY:
    case MONTH:
      field = nonSecondDatetimeField();
        {if (true) return field;}
      break;
    case SECOND:
      jj_consume_token(SECOND);
        {if (true) return ExtractOperatorNode.Field.SECOND;}
      break;
    default:
      jj_la1[153] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode characterValueFunction() throws ParseException, StandardException {
    ValueNode value = null;
    ValueNode str1;
    ValueNode str2;
    Token upperTok = null;
    Token lowerTok = null;
    ValueNode startPosition;
    ValueNode length = null;
    switch (jj_nt.kind) {
    case SUBSTRING:
    case SUBSTR:
      switch (jj_nt.kind) {
      case SUBSTR:
        jj_consume_token(SUBSTR);
        break;
      case SUBSTRING:
        jj_consume_token(SUBSTRING);
        break;
      default:
        jj_la1[154] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(COMMA);
      startPosition = additiveExpression();
      switch (jj_nt.kind) {
      case COMMA:
        jj_consume_token(COMMA);
        length = additiveExpression();
        break;
      default:
        jj_la1[155] = jj_gen;
        ;
      }
      jj_consume_token(RIGHT_PAREN);
        {if (true) return getSubstringNode(value, startPosition, length, Boolean.FALSE);}
      break;
    case LOWER:
    case UPPER:
      switch (jj_nt.kind) {
      case UPPER:
        upperTok = jj_consume_token(UPPER);
        break;
      case LOWER:
        lowerTok = jj_consume_token(LOWER);
        break;
      default:
        jj_la1[156] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.SIMPLE_STRING_OPERATOR_NODE,
                                              value,
                                              (upperTok != null) ? "upper" : "lower",
                                              parserContext);}
      break;
    case LCASE:
    case UCASE:
      switch (jj_nt.kind) {
      case UCASE:
        upperTok = jj_consume_token(UCASE);
        break;
      case LCASE:
        lowerTok = jj_consume_token(LCASE);
        break;
      default:
        jj_la1[157] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.SIMPLE_STRING_OPERATOR_NODE,
                                              value,
                                              (upperTok != null) ? "upper" : "lower",
                                              parserContext);}
      break;
    case TRIM:
    case LTRIM:
    case RTRIM:
      value = trimFunction();
        {if (true) return value;}
      break;
    case LOCATE:
      jj_consume_token(LOCATE);
      jj_consume_token(LEFT_PAREN);
      str1 = additiveExpression();
      switch (jj_nt.kind) {
      case COMMA:
        jj_consume_token(COMMA);
        break;
      case IN:
        jj_consume_token(IN);
        break;
      default:
        jj_la1[158] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      str2 = additiveExpression();
      switch (jj_nt.kind) {
      case COMMA:
        jj_consume_token(COMMA);
        value = additiveExpression();
        break;
      default:
        jj_la1[159] = jj_gen;
        ;
      }
      jj_consume_token(RIGHT_PAREN);
        // If start is missing, start is equal to 1.
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.LOCATE_FUNCTION_NODE,
                                              str1,
                                              str2,
                                              (value == null) ? nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE, 1, parserContext) : value,
                                              TernaryOperatorNode.OperatorType.LOCATE,
                                              null,
                                              parserContext);}
      break;
    case POSITION:
      jj_consume_token(POSITION);
      jj_consume_token(LEFT_PAREN);
      str1 = additiveExpression();
      switch (jj_nt.kind) {
      case COMMA:
        jj_consume_token(COMMA);
        break;
      case IN:
        jj_consume_token(IN);
        break;
      default:
        jj_la1[160] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      str2 = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.LOCATE_FUNCTION_NODE,
                                              str1,
                                              str2,
                                              nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE, 1, parserContext),
                                              TernaryOperatorNode.OperatorType.LOCATE,
                                              null,
                                              parserContext);}
      break;
    default:
      jj_la1[162] = jj_gen;
      if (mysqlLeftRightFuncFollows()) {
        switch (jj_nt.kind) {
        case LEFT:
          jj_consume_token(LEFT);
          jj_consume_token(LEFT_PAREN);
          str1 = additiveExpression();
          jj_consume_token(COMMA);
          str2 = additiveExpression();
          jj_consume_token(RIGHT_PAREN);
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.LEFT_FN_NODE,
                                                    str1,
                                                    str2,
                                                    parserContext);}
          break;
        case RIGHT:
          jj_consume_token(RIGHT);
          jj_consume_token(LEFT_PAREN);
          str1 = additiveExpression();
          jj_consume_token(COMMA);
          str2 = additiveExpression();
          jj_consume_token(RIGHT_PAREN);
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.RIGHT_FN_NODE,
                                                    str1,
                                                    str2,
                                                    parserContext);}
          break;
        default:
          jj_la1[161] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode trimFunction() throws ParseException, StandardException {
    ValueNode source;
    BinaryOperatorNode.OperatorType trimType;

    ValueNode ansiTrimNode;
    switch (jj_nt.kind) {
    case LTRIM:
    case RTRIM:
      trimType = trimType();
      jj_consume_token(LEFT_PAREN);
      source = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return getTrimOperatorNode(trimType, null, source);}
      break;
    case TRIM:
      jj_consume_token(TRIM);
      ansiTrimNode = ansiTrim();
        {if (true) return ansiTrimNode;}
      break;
    default:
      jj_la1[163] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode ansiTrim() throws ParseException, StandardException {
    BinaryOperatorNode.OperatorType trimType = BinaryOperatorNode.OperatorType.TRIM;
    ValueNode trimChar = null;
    ValueNode trimSource = null;
    if (ansiTrimSpecFollows()) {
      jj_consume_token(LEFT_PAREN);
      trimType = ansiTrimSpec();
      if (jj_2_41(2147483647)) {
        jj_consume_token(FROM);
        trimSource = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        {if (true) return getTrimOperatorNode(trimType, trimChar, trimSource);}
      } else if (jj_2_42(1)) {
        // LEADING <char> FROM <source>
                trimChar = additiveExpression();
        jj_consume_token(FROM);
        trimSource = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        {if (true) return getTrimOperatorNode(trimType, trimChar, trimSource);}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    } else if (!ansiTrimSpecFollows()) {
      jj_consume_token(LEFT_PAREN);
      trimChar = additiveExpression();
      switch (jj_nt.kind) {
      case FROM:
        jj_consume_token(FROM);
        trimSource = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        {if (true) return getTrimOperatorNode(trimType, trimChar, trimSource);}
        break;
      case RIGHT_PAREN:
        jj_consume_token(RIGHT_PAREN);
        // expr was trim(e)-- we assigned e to trimChar but it is really the trimSource
        {if (true) return getTrimOperatorNode(trimType, null, trimChar);}
        break;
      default:
        jj_la1[164] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType ansiTrimSpec() throws ParseException {
    switch (jj_nt.kind) {
    case TRAILING:
      jj_consume_token(TRAILING);
        {if (true) return BinaryOperatorNode.OperatorType.RTRIM;}
      break;
    case LEADING:
      jj_consume_token(LEADING);
        {if (true) return BinaryOperatorNode.OperatorType.LTRIM;}
      break;
    case BOTH:
      jj_consume_token(BOTH);
        {if (true) return BinaryOperatorNode.OperatorType.TRIM;}
      break;
    default:
      jj_la1[165] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public BinaryOperatorNode.OperatorType trimType() throws ParseException {
    switch (jj_nt.kind) {
    case RTRIM:
      jj_consume_token(RTRIM);
        {if (true) return BinaryOperatorNode.OperatorType.RTRIM;}
      break;
    case LTRIM:
      jj_consume_token(LTRIM);
        {if (true) return BinaryOperatorNode.OperatorType.LTRIM;}
      break;
    default:
      jj_la1[166] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode valueExpressionPrimary() throws ParseException, StandardException {
    ValueNode value;
    int tokKind;
    if (escapedValueFunctionFollows()) {
      jj_consume_token(LEFT_BRACE);
      jj_consume_token(FN);
      value = escapedValueFunction();
      jj_consume_token(RIGHT_BRACE);
        {if (true) return value;}
    } else if (getToken(2).kind == SCHEMA || getToken(2).kind == SQLID) {
      jj_consume_token(CURRENT);
      switch (jj_nt.kind) {
      case SCHEMA:
        jj_consume_token(SCHEMA);
        break;
      case SQLID:
        jj_consume_token(SQLID);
        break;
      default:
        jj_la1[167] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_SCHEMA_NODE,
                                              parserContext);}
    } else if (getToken(2).kind == ISOLATION) {
      jj_consume_token(CURRENT);
      jj_consume_token(ISOLATION);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_ISOLATION_NODE,
                                              parserContext);}
    } else if (jj_2_44(1)) {
      // TODO: *** What is this comment about?
          /* Omitted "case_expression" */
          value = valueSpecification();
        {if (true) return value;}
    } else if (newInvocationFollows(1)) {
      value = newInvocation();
        {if (true) return value;}
    } else if (windowOrAggregateFunctionFollows()) {
      value = windowOrAggregateFunctionNode();
        {if (true) return value;}
    } else if (miscBuiltinFollows()) {
      value = miscBuiltins();
        {if (true) return value;}
    } else if (jj_2_45(1)) {
      value = columnReference();
        {if (true) return value;}
    } else {
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        if (getToken(1).kind == SELECT || getToken(1).kind == VALUES) {
          value = subquery(SubqueryNode.SubqueryType.EXPRESSION, null);
        } else if (jj_2_43(1)) {
          value = valueExpression();
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
        jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
        break;
      case CAST:
        value = castSpecification();
        {if (true) return value;}
        break;
      case NEXT:
        value = nextValueExpression();
        {if (true) return value;}
        break;
      default:
        jj_la1[168] = jj_gen;
        if (jj_2_46(1)) {
          value = currentValueExpression();
        {if (true) return value;}
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode miscBuiltins() throws ParseException, StandardException {
    ValueNode value;
    if (miscBuiltinCoreFollows()) {
      value = miscBuiltinsCore(false);
        {if (true) return value;}
    } else if (jj_2_47(1)) {
      value = datetimeValueFunction();
        {if (true) return value;}
    } else if (jj_2_48(1)) {
      value = routineInvocation();
        {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode miscBuiltinsCore(boolean isJDBCEscape) throws ParseException, StandardException {
    ValueNode value;
    switch (jj_nt.kind) {
    case GET_CURRENT_CONNECTION:
      jj_consume_token(GET_CURRENT_CONNECTION);
      jj_consume_token(LEFT_PAREN);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              nodeFactory.getNode(NodeTypes.GET_CURRENT_CONNECTION_NODE, parserContext),
                                              parserContext);}
      break;
    case ABS:
    case ABSVAL:
    case IDENTITY_VAL_LOCAL:
    case MOD:
    case SQRT:
      value = numericValueFunction();
        {if (true) return value;}
      break;
    default:
      jj_la1[170] = jj_gen;
      if (jj_2_49(1)) {
        value = characterValueFunction();
        {if (true) return value;}
      } else if (jj_2_50(1)) {
        value = dataTypeScalarFunction();
        {if (true) return value;}
      } else {
        switch (jj_nt.kind) {
        case COALESCE:
          jj_consume_token(COALESCE);
          value = coalesceFunction("COALESCE");
        {if (true) return value;}
          break;
        case VALUE:
          jj_consume_token(VALUE);
          value = coalesceFunction("VALUE");
        {if (true) return value;}
          break;
        case LENGTH:
          jj_consume_token(LENGTH);
          jj_consume_token(LEFT_PAREN);
          value = additiveExpression();
          jj_consume_token(RIGHT_PAREN);
        if (isJDBCEscape)
            {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CHAR_LENGTH_OPERATOR_NODE,
                                                  getTrimOperatorNode(BinaryOperatorNode.OperatorType.RTRIM,
                                                                      null,
                                                                      value),
                                                  parserContext);}

        /*
            TODO: DECIDE wether LENGTH should be OCTET_LENGTH or CHARACTER_LENGTH
                  dependent upon some mode
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.OCTET_LENGTH_OPERATOR_NODE,
                                              value,
                                              parserContext);}
          break;
        case CHARACTER_LENGTH:
        case CHAR_LENGTH:
          switch (jj_nt.kind) {
          case CHAR_LENGTH:
            jj_consume_token(CHAR_LENGTH);
            break;
          case CHARACTER_LENGTH:
            jj_consume_token(CHARACTER_LENGTH);
            break;
          default:
            jj_la1[169] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
          jj_consume_token(LEFT_PAREN);
          value = additiveExpression();
          jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CHAR_LENGTH_OPERATOR_NODE,
                                              value,
                                              parserContext);}
          break;
        case OCTET_LENGTH:
          jj_consume_token(OCTET_LENGTH);
          jj_consume_token(LEFT_PAREN);
          value = additiveExpression();
          jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.OCTET_LENGTH_OPERATOR_NODE,
                                              value,
                                              parserContext);}
          break;
        case XMLEXISTS:
        case XMLPARSE:
        case XMLQUERY:
        case XMLSERIALIZE:
          value = xmlFunction();
        {if (true) return value;}
          break;
        default:
          jj_la1[171] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode dataTypeScalarFunction() throws ParseException, StandardException {
    DataTypeDescriptor dts;
    ValueNode value;                            // Converted result
    ValueNode operand;
    int charType;
    int length = -1;
    switch (jj_nt.kind) {
    case HOUR:
    case MINUTE:
    case SECOND:
    case YEAR:
    case DATE:
    case DAY:
    case EXTRACT:
    case MONTH:
    case TIME:
    case TIMESTAMP:
    case TIMESTAMPADD:
    case TIMESTAMPDIFF:
      // NOTE: If you add a new function here or in one of the called
          // rules, you must add the same token(s) to miscBuiltinCoreFollows().
          value = dateTimeScalarFunction();
        {if (true) return value;}
      break;
    default:
      jj_la1[173] = jj_gen;
      if (jj_2_51(1)) {
        dts = numericFunctionType();
        jj_consume_token(LEFT_PAREN);
        operand = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        value = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                               operand,
                                               dts,
                                               parserContext);
        ((CastNode)value).setForDataTypeFunction(true);
        ((CastNode)value).setForExternallyGeneratedCASTnode();

        {if (true) return value;}
      } else {
        switch (jj_nt.kind) {
        case CHAR:
        case VARCHAR:
          charType = charOrVarchar();
          jj_consume_token(LEFT_PAREN);
          operand = additiveExpression();
          switch (jj_nt.kind) {
          case COMMA:
            jj_consume_token(COMMA);
            length = length();
            break;
          default:
            jj_la1[172] = jj_gen;
            ;
          }
          jj_consume_token(RIGHT_PAREN);
        value = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                               operand,
                                               new Integer(charType),
                                               new Integer(length),
                                               parserContext);

        ((CastNode)value).setForDataTypeFunction(true);
        ((CastNode)value).setForExternallyGeneratedCASTnode();
        {if (true) return value;}
          break;
        default:
          jj_la1[174] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
 * This method parses the built-in functions used with the XML datatype.
 */
  final public ValueNode xmlFunction() throws ParseException, StandardException {
    ValueNode value;
    switch (jj_nt.kind) {
    case XMLPARSE:
      jj_consume_token(XMLPARSE);
      jj_consume_token(LEFT_PAREN);
      xmlDocOrContent();
      value = xmlParseValue();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
      break;
    case XMLSERIALIZE:
      jj_consume_token(XMLSERIALIZE);
      jj_consume_token(LEFT_PAREN);
      value = xmlSerializeValue();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
      break;
    case XMLEXISTS:
      jj_consume_token(XMLEXISTS);
      jj_consume_token(LEFT_PAREN);
      value = xmlQueryValue(true);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
      break;
    case XMLQUERY:
      jj_consume_token(XMLQUERY);
      jj_consume_token(LEFT_PAREN);
      value = xmlQueryValue(false);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return value;}
      break;
    default:
      jj_la1[175] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

/*
 * Syntax is as follows:
 *
 *       XMLPARSE( DOCUMENT <string-value-expression> PRESERVE WHITESPACE )
 *
 * The result of this operation will be an XML value, which can either
 * be used transiently or else can be stored persistently in a table that
 * has an XML column.    For example:
 *
 * ij> CREATE TABLE x_table (id INT, xdoc XML);
 * 0 rows inserted/updated/deleted
 * ij> INSERT INTO x_table VALUES (1, XMLPARSE(DOCUMENT '<simp> doc </simp>'
 * PRESERVE WHITESPACE));
 * 1 row inserted/updated/deleted
 *
 * We only allow XML documents (as opposed to XML content) to be
 * parsed into XML values.  Note that we require the "PRESERVE WHITESPACE"
 * keyword to be explicit; this is because the SQL/XML (2003) spec says that
 * if no whitespace option is given, the default is "STRIP WHITESPACE", which
 * we don't support (yet).
 *
 * By the time we get to this method, the "DOCUMENT" keyword has already
 * been parsed.
 *
 */
  final public ValueNode xmlParseValue() throws ParseException, StandardException {
    ValueNode value;
    boolean wsOption;
    value = additiveExpression();
    wsOption = xmlPreserveWhitespace();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.XML_PARSE_OPERATOR_NODE,
                                              value,
                                              XMLUnaryOperatorNode.OperatorType.PARSE,
                                              new Object[] {(wsOption ? Boolean.TRUE : Boolean.FALSE)},
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
 * For now, we only support the PRESERVE WHITESPACE option.
 */
  final public boolean xmlPreserveWhitespace() throws ParseException, StandardException {
    if ((getToken(1).kind != STRIP) || (getToken(1).kind != PRESERVE)) {
        {if (true) throw new StandardException("Missing required PRESERVE WHITESPACE");}
    } else {
      switch (jj_nt.kind) {
      case STRIP:
        jj_consume_token(STRIP);
        jj_consume_token(WHITESPACE);
      // don't preserve whitespace.
        {if (true) return false;}
        break;
      case PRESERVE:
        jj_consume_token(PRESERVE);
        jj_consume_token(WHITESPACE);
      // must preserve whitespace.
        {if (true) return true;}
        break;
      default:
        jj_la1[176] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
 * Syntax is as follows:
 *
 *   XMLSERIALIZE( <xml-value-expression> AS <string-data-type> )
 *
 * The result of this operation will be a string value with the type specified
 * by the user.  For example:
 *
 * ij> SELECT id, XMLSERIALIZE(xdoc AS varchar(30)) FROM x_table;
 * ID                   |2
 * ------------------------------------------
 * 1                    |<simp> doc </simp>
 *
 */
  final public ValueNode xmlSerializeValue() throws ParseException, StandardException {
    ValueNode value;
    DataTypeDescriptor targetType;
    value = additiveExpression();
    targetType = xmlSerializeTargetType();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.XML_SERIALIZE_OPERATOR_NODE,
                                              value,
                                              XMLUnaryOperatorNode.OperatorType.SERIALIZE,
                                              new Object[] {targetType},
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
 * Parse the target type of an XMLSERIALIZE operation.
 */
  final public DataTypeDescriptor xmlSerializeTargetType() throws ParseException, StandardException {
    DataTypeDescriptor targetType;
    if ((getToken(1).kind != AS)) {
        {if (true) throw new StandardException("Missing required AS");}
    } else {
      switch (jj_nt.kind) {
      case AS:
        jj_consume_token(AS);
        targetType = dataTypeDDL();
        {if (true) return targetType;}
        break;
      default:
        jj_la1[177] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
 * This method is used for parsing the XMLEXISTS and XMLQUERY operators
 * (which operator depends on the received boolean parameter).
 *
 * For XMLEXISTS, the syntax is as follows:
 *
 *   XMLEXISTS( <xpath-expression> PASSING BY REF <xml-value-expression> )
 *
 * The result of this operation will be a boolean true/false/unknown value:
 *   -- Unknown if either <xquery-expression> or <xml-value-expression> is null;
 *   -- True if evaluation of the given query expression against the
 *          given xml-value returns at least one node.
 *   -- False otherwise.
 *
 * For example:
 *
 * ij> SELECT id FROM x_table WHERE XMLEXISTS('/simple' PASSING BY REF xdoc);
 * ID
 * -----------
 * 1
 *
 * ====
 *
 * For XMLQUERY, the syntax is as follows:
 *
 *   XMLQUERY( <xquery-expression>
 *          PASSING BY REF <xml-value-expression>
 *          [ RETURNING SEQUENCE [ BY REF ] ]
 *          EMPTY ON EMPTY
 *   )
 *
 * The result of this operation will be an XMLDataValue.
 *
 * For example:
 *
 * ij> SELECT XMLSERIALIZE(
 *           XMLQUERY('/simple' PASSING BY REF xdoc EMPTY ON EMPTY) AS CHAR(100));
 * ID
 * -----------
 * <simp> doc </simp>
 *
 */
  final public ValueNode xmlQueryValue(boolean existsOnly) throws ParseException, StandardException {
    // The query expression (currently must be an expression
    // supported by Xalan--i.e. XPath only).
    ValueNode xqueryExpr = null;

    // Context item for the query; not required by SQL/XML spec,
    // but required by Derby for now.
    ValueNode xmlValue = null;

    // User-specified default passing mechanism.    Since Derby only
    // supports one type of passing mechanism--BY REF--this value
    // isn't currently used.
    XMLBinaryOperatorNode.PassByType defaultPassingMech = null;
    xqueryExpr = additiveExpression();
    jj_consume_token(PASSING);
    defaultPassingMech = xmlPassingMechanism();
    xmlValue = xqVarList();
    if (!existsOnly) {
      if (jj_2_53(1)) {
        xqReturningClause();
        if (jj_2_52(1)) {
          xmlPassingMechanism();
        } else {
          ;
        }
      } else {
        ;
      }
      xqEmptyHandlingClause();

    } else if (existsOnly) {

    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
        ValueNode vNode = (ValueNode)nodeFactory.getNode((existsOnly
                                                          ? NodeTypes.XML_EXISTS_OPERATOR_NODE
                                                          : NodeTypes.XML_QUERY_OPERATOR_NODE),
                                                         xqueryExpr,
                                                         xmlValue,
                                                         (existsOnly
                                                          ? XMLBinaryOperatorNode.OperatorType.EXISTS
                                                          : XMLBinaryOperatorNode.OperatorType.QUERY),
                                                         parserContext);

        {if (true) return vNode;}
    throw new Error("Missing return statement in function");
  }

/**
 * Parse a list of XML query variables, which can include at most one
 * XML value to be used as the "context item" for the query.    If
 * such a context item was found, return that item; for all other
 * variable declarations we currently throw a "not supported" error
 * because Xalan doesn't allowing binding of variables.
 */
  final public ValueNode xqVarList() throws ParseException, StandardException {
    // Placeholder for the XML context item as we parse the
    // argument list.
    ValueNode [] xmlValue = new ValueNode [] { (ValueNode)null };
    xqVariable(xmlValue);
    label_19:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[178] = jj_gen;
        break label_19;
      }
      jj_consume_token(COMMA);
      xqVariable(xmlValue);
    }
        {if (true) return xmlValue[0];}
    throw new Error("Missing return statement in function");
  }

/**
 * Parse an XML query variable.  If the argument is an XML value
 * to be used as the "context item" for a query, then store the
 * value in the first slot of the received ValueNode array;
 * otherwise, throw a "not supported" errror (for now).
 */
  final public void xqVariable(ValueNode [] xmlVal) throws ParseException, StandardException {
    ValueNode curVal;
    String varName = null;
    XMLBinaryOperatorNode.PassByType passingMech = null;
    curVal = additiveExpression();
    if (getToken(1).kind == AS) {
      jj_consume_token(AS);
      varName = identifier();
            /* From XQuery 1.0: "The <identifier> I contained in XQV
             * shall be an XML 1.1 NCName."  From XML 1.1:
             *
             *   [4] NCName ::= (Letter | '_') (NCNameChar)*
             *   [5] NCNameChar ::= Letter | Digit | '.' | '-' | '_' |
             *                                      CombiningChar | Extender
             *
             * Since Derby's definition of an "identifier" is a subset
             * of NCName, we just use Derby's definition.    This means
             * that some valid NCNames won't be recognized by Derby--
             * but since the ones we _do_ recognize are all still valid
             * NCNames, we're not breaking any rules.
             */

            /* All of that said, since we use Xalan as the underlying
             * query engine and Xalan doesn't support variable binding,
             * there's no point in letting the user specify variables
             * right now.    So we disallow it.  In the future we'll have
             * to add logic here to store the variables and pass them
             * to the correct operator for binding/execution.
             */
            {if (true) throw new StandardException("Not implemented yet: PASSING ... AS");}
    } else {
      ;
    }
    if (jj_2_54(1)) {
      passingMech = xmlPassingMechanism();
    } else {
      ;
    }
            if (varName == null) {
                /* We get here if we just parsed an XML context item.
                 * That said, if we already have one (xmlVal[0] is not
                 * null) then we can't allow second one, per SQL/XML[2006]
                 * (6.17: Syntax Rules:5.b.i): "XMQ shall contain exactly
                 * one <XML query context item> XQCI."
                 */
                if (xmlVal[0] != null) {
                    {if (true) throw new StandardException("Multiple XML context items");}
                }

                xmlVal[0] = curVal;

                /* Note: It's possible that a passing mechanism was
                 * specified for the context item; if so its value is
                 * stored in passingMech.    However, we don't actually
                    * store that passing mechanism anywhere because we
                 * (currently) only support BY REF, so we know what
                    * it has to be.  If we add support for other passing
                    * mechanisms (namely, BY VALUE) in the future, we'll
                    * have to store the passing mechanism provided by
                    * the user and process it at compilation/execution
                 * time.
                 */
            }

  }

/*
 * For now, we only support the BY REF option because
 * that gives us better performance (allows us to avoid
 * performing potentially deep copies of XML nodes).    This
 * means that if the same XML value is passed BY REF into
 * two different XML arguments for a single operator, then
 * every node in the first XML argument must have an
 * identical node in the second XML argument, and the
 * ids for both nodes must be the same.  That said,
 * since we don't support variable binding yet, this
 * becomes a non-issue because we can't pass XML values.
 * In the future, though, we may choose to support the
 * passing/binding of variables (the only reason we
 * don't now is because Xalan doesn't support it) and
 * if we do, BY REF should provide better performance
 * due to lack of deep copying.
 */
  final public XMLBinaryOperatorNode.PassByType xmlPassingMechanism() throws ParseException, StandardException {
    if (getToken(2).kind == REF) {
      jj_consume_token(BY);
      jj_consume_token(REF);
      // pass the XML value by reference
        {if (true) return XMLBinaryOperatorNode.PassByType.REF;}
    } else {
      switch (jj_nt.kind) {
      case BY:
        jj_consume_token(BY);
        jj_consume_token(VALUE);
      // pass a 'copy' of the XML value.
        {if (true) return XMLBinaryOperatorNode.PassByType.VALUE;}
        break;
      default:
        jj_la1[179] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
 * For now we only support "RETURNING SEQUENCE".    The reason
 * is that this applies to the XMLQUERY operator and the
 * results of evaluating a query expression in Xalan against
 * an XML value can be an arbritary sequence of items--including
 * atomic values.    For simplicity we just return the values
 * as they are, without doing any further work.  SQL/XML[2006]
 * says that if we supported RETURNING CONTENT then we'd have
 * to construct an XQuery document from the results--but we don't
 * do that extra work for now, so we just say that we return
 * SEQUENCE.
 *
 * NOTE: This means that we may not be able to store the results
 * of an XMLQUERY operation into a Derby XML column.    Right now
 * an XML column can only hold valid DOCUMENT nodes, which we
 * we define as an XML value whose serialized form can be parsed
 * by a JAXP DocumentBuilder (because that's what Derby's XMLPARSE
 * operator uses and the result is always a Document node).
 * Internally this means that we can only store a sequence if it
 * contains exactly one org.w3c.dom.Node that is an instance of
 * org.w3c.dom.Document.    If the result of an XMLQUERY operation
 * does not fit this criteria then it will *not* be storable into
 * Derby XML columns.
 */
  final public XMLBinaryOperatorNode.ReturnType xqReturningClause() throws ParseException, StandardException {
    if (getToken(2).kind == SEQUENCE) {
      jj_consume_token(RETURNING);
      jj_consume_token(SEQUENCE);
      // XMLQUERY should return result as a sequence.
        {if (true) return XMLBinaryOperatorNode.ReturnType.SEQUENCE;}
    } else {
      switch (jj_nt.kind) {
      case RETURNING:
        jj_consume_token(RETURNING);
        jj_consume_token(CONTENT);
      // XMLQUERY should return 'content'.
        {if (true) return XMLBinaryOperatorNode.ReturnType.CONTENT;}
        break;
      default:
        jj_la1[180] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
 * Defines what the behavior should be when an XMLQUERY operator
 * results in an empty sequence.    For now we just return the
 * empty sequence.
 */
  final public XMLBinaryOperatorNode.OnEmpty xqEmptyHandlingClause() throws ParseException, StandardException {
    if (getToken(1).kind == EMPTY) {
      jj_consume_token(EMPTY);
      jj_consume_token(ON);
      jj_consume_token(EMPTY);
      // XMLQUERY should return an empty sequence when result of
        // the query is an empty sequence (i.e. when there are no
        // results).
        {if (true) return XMLBinaryOperatorNode.OnEmpty.EMPTY;}
    } else {
      switch (jj_nt.kind) {
      case NULL:
        jj_consume_token(NULL);
        jj_consume_token(ON);
        jj_consume_token(EMPTY);
      // XMLQUERY should return a null XML value when result of
        // the query is an empty sequence (i.e. when there are no
        // results).
        {if (true) return XMLBinaryOperatorNode.OnEmpty.NULL;}
        break;
      default:
        jj_la1[181] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor numericFunctionType() throws ParseException, StandardException {
    DataTypeDescriptor dts;
    if (jj_2_55(1)) {
      dts = doubleType();
        {if (true) return dts;}
    } else {
      switch (jj_nt.kind) {
      case INT:
      case INTEGER:
      case SMALLINT:
      case LONGINT:
      case MEDIUMINT:
      case TINYINT:
        dts = exactIntegerType();
        {if (true) return dts;}
        break;
      default:
        jj_la1[182] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode dateTimeScalarFunction() throws ParseException, StandardException {
    ValueNode value;
    ValueNode timestampNode;
    ExtractOperatorNode.Field field;
    switch (jj_nt.kind) {
    case EXTRACT:
      jj_consume_token(EXTRACT);
      jj_consume_token(LEFT_PAREN);
      field = datetimeField();
      jj_consume_token(FROM);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.EXTRACT_OPERATOR_NODE,
                                              field,
                                              value,
                                              parserContext);}
      break;
    case TIME:
      jj_consume_token(TIME);
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_DATE_TIMESTAMP_OPERATOR_NODE,
                                              value,
                                              DataTypeDescriptor.getBuiltInDataTypeDescriptor( Types.TIME),
                                              parserContext);}
      break;
    case DATE:
      jj_consume_token(DATE);
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_DATE_TIMESTAMP_OPERATOR_NODE,
                                              value,
                                              DataTypeDescriptor.getBuiltInDataTypeDescriptor( Types.DATE),
                                              parserContext);}
      break;
    case TIMESTAMP:
      jj_consume_token(TIMESTAMP);
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      timestampNode = timestampFunctionCompletion(value);
        {if (true) return timestampNode;}
      break;
    case HOUR:
    case MINUTE:
    case SECOND:
    case YEAR:
    case DAY:
    case MONTH:
      field = datetimeField();
      jj_consume_token(LEFT_PAREN);
      value = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.EXTRACT_OPERATOR_NODE,
                                              field,
                                              value,
                                              parserContext);}
      break;
    case TIMESTAMPADD:
    case TIMESTAMPDIFF:
      value = timestampArithmeticFuncion();
        {if (true) return value;}
      break;
    default:
      jj_la1[183] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode timestampFunctionCompletion(ValueNode firstArg) throws ParseException, StandardException {
    ValueNode timeValue;
    switch (jj_nt.kind) {
    case RIGHT_PAREN:
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNARY_DATE_TIMESTAMP_OPERATOR_NODE,
                                              firstArg,
                                              DataTypeDescriptor.getBuiltInDataTypeDescriptor( Types.TIMESTAMP),
                                              parserContext);}
      break;
    case COMMA:
      jj_consume_token(COMMA);
      timeValue = additiveExpression();
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.TIMESTAMP_OPERATOR_NODE,
                                              firstArg,
                                              timeValue,
                                              parserContext);}
      break;
    default:
      jj_la1[184] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public Token booleanLiteral() throws ParseException {
    Token tok;
    switch (jj_nt.kind) {
    case TRUE:
      tok = jj_consume_token(TRUE);
        {if (true) return tok;}
      break;
    case FALSE:
      tok = jj_consume_token(FALSE);
        {if (true) return tok;}
      break;
    default:
      jj_la1[185] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode generalValueSpecification() throws ParseException, StandardException {
    ValueNode parm;
    switch (jj_nt.kind) {
    case QUESTION_MARK:
    case DOLLAR_N:
      parm = dynamicParameterSpecification();
        {if (true) return parm;}
      break;
    case CURRENT_USER:
    case SESSION_USER:
    case USER:
      parm = userNode();
        {if (true) return parm;}
      break;
    case CURRENT_ROLE:
      parm = currentRoleNode();
        {if (true) return parm;}
      break;
    case CURRENT_SCHEMA:
      parm = currentSchemaNode();
        {if (true) return parm;}
      break;
    default:
      jj_la1[186] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode userNode() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case USER:
      jj_consume_token(USER);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.USER_NODE,
                                              parserContext);}
      break;
    case CURRENT_USER:
      jj_consume_token(CURRENT_USER);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_USER_NODE,
                                              parserContext);}
      break;
    case SESSION_USER:
      jj_consume_token(SESSION_USER);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.SESSION_USER_NODE,
                                              parserContext);}
      break;
    default:
      jj_la1[187] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode currentRoleNode() throws ParseException, StandardException {
    jj_consume_token(CURRENT_ROLE);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_ROLE_NODE,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode currentSchemaNode() throws ParseException, StandardException {
    jj_consume_token(CURRENT_SCHEMA);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_SCHEMA_NODE,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public JavaToSQLValueNode newInvocation() throws ParseException, StandardException {
    QueryTreeNode    newNode;
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    String javaClassName;
    jj_consume_token(NEW);
    javaClassName = javaClassName();
    methodCallParameterList(parameterList);
        newNode = nodeFactory.getNode(NodeTypes.NEW_INVOCATION_NODE,
                                      javaClassName,
                                      parameterList,
                                      lastTokenDelimitedIdentifier,
                                      parserContext);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (JavaToSQLValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                                       newNode,
                                                       parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
 * Parse a TABLE() constructor that corresponds to an internal
 * VTI invocation.  For example:
 *
 *      TABLE ( <qualifiedName> (arg1, arg2, ...) )
 *
 * where <qualifiedName> is a table name that Derby will map internally
 * to a VTI (ex. "SYSCS_DIAG.SPACE_TABLE").  The list of arguments
 * will then be passed to the VTI when it is invoked (DERBY-2152).
 *
 * An example query where this might occur is as follows:
 *
 *   SELECT * FROM TABLE(SYSCS_DIAG.SPACE_TABLE('APP', 'T1')) x
 *
 * in which case SYSCS_DIAG.SPACE_TABLE will be mapped (internally)
 * to the "org.apache.derby.diag.SpaceTable" diagnostic VTI.    Thus
 * the equivalent call prior to DERBY-2152 would have been:
 *
 *   SELECT * FROM NEW org.apache.derby.diag.SpaceTable('APP', 'T1')) x
 *
 * Note that this latter syntax is still supported.
 */
  final public JavaToSQLValueNode vtiTableConstruct() throws ParseException, StandardException {
    NewInvocationNode newNode = null;
    QueryTreeNode invocationNode = null;
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    TableName vtiTableName = null;
    MethodCallNode  methodNode;
    jj_consume_token(TABLE);
    jj_consume_token(LEFT_PAREN);
    vtiTableName = qualifiedName();
    methodCallParameterList(parameterList);
    jj_consume_token(RIGHT_PAREN);
        /* The fact that we pass a NULL table descriptor to the
         * following call is an indication that we are mapping to a
         * VTI table function (i.e. one that accepts arguments).
         * Since we have the table name we do not need to pass in a
         * TableDescriptor--we'll just create one from the table
         * name. See NewInvocationNode for more.
         */
        newNode = (NewInvocationNode)nodeFactory.getNode(NodeTypes.NEW_INVOCATION_NODE,
                                                         vtiTableName,  // TableName
                                                         null,                  // TableDescriptor
                                                         parameterList,
                                                         lastTokenDelimitedIdentifier,
                                                         parserContext);

        if (newNode.isBuiltinVTI()) {
            invocationNode = newNode;
        }
        else {
            methodNode = (MethodCallNode)nodeFactory.getNode(NodeTypes.STATIC_METHOD_CALL_NODE,
                                                             vtiTableName,
                                                             null,
                                                             parserContext);
            methodNode.addParms(parameterList);

            invocationNode = methodNode;
        }

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (JavaToSQLValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                                       invocationNode,
                                                       parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode staticMethodInvocation(String javaClassName) throws ParseException, StandardException {
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    MethodCallNode methodNode;
    methodNode = staticMethodName(javaClassName);
    methodCallParameterList(parameterList);
        methodNode.addParms(parameterList);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              methodNode,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void methodCallParameterList(List<ValueNode> parameterList) throws ParseException, StandardException {
    jj_consume_token(LEFT_PAREN);
    if (jj_2_56(1)) {
      methodParameter(parameterList);
      label_20:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[188] = jj_gen;
          break label_20;
        }
        jj_consume_token(COMMA);
        methodParameter(parameterList);
      }
    } else {
      ;
    }
    jj_consume_token(RIGHT_PAREN);
  }

  final public ValueNode routineInvocation() throws ParseException, StandardException {
    List<ValueNode> parameterList = new ArrayList<ValueNode>();
    TableName routineName;
    MethodCallNode methodNode;
    routineName = qualifiedName();
    methodCallParameterList(parameterList);
        methodNode = (MethodCallNode)nodeFactory.getNode(NodeTypes.STATIC_METHOD_CALL_NODE,
                                                         routineName,
                                                         null,
                                                         parserContext);

        methodNode.addParms(parameterList);

        /*
        ** Assume this is being returned to the SQL domain.  If it turns
        ** out that this is being returned to the Java domain, we will
        ** get rid of this node.
        */
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                              methodNode,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public String javaClass() throws ParseException, StandardException {
    String javaClassName;
    javaClassName = javaClassName();
        {if (true) return javaClassName;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode columnMethodInvocation() throws ParseException, StandardException {
    ValueNode columnReference;
    ValueNode methodNode;
    columnReference = columnNameForInvocation();
    methodNode = nonStaticMethodInvocation(columnReference);
        {if (true) return methodNode;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode columnNameForInvocation() throws ParseException, StandardException {
    String firstName;
    String secondName = null;
    String thirdName = null;
    String columnName = null;
    String tableName = null;
    String schemaName = null;
    TableName tabName = null;
    ValueNode retval;
    firstName = identifier();
    if (getToken(1).kind == PERIOD && getToken(3).kind == PERIOD) {
      jj_consume_token(PERIOD);
      secondName = identifier();
      if (getToken(1).kind == PERIOD && getToken(3).kind == PERIOD) {
        jj_consume_token(PERIOD);
        thirdName = identifier();
      } else {
        ;
      }
    } else {
      ;
    }
        // Figure out what each identifier stands for
        if (thirdName == null) {
            if (secondName == null) {
                // There's only one identifier, so it must be a column name
                columnName = firstName;
            }
            else {
                // There are two identifiers, so they are table and column names
                tableName = firstName;
                columnName = secondName;
            }
        }
        else {
            // There are three identifiers,
            // so they are schema, table, and column names
            schemaName = firstName;
            tableName = secondName;
            columnName = thirdName;
        }

        if (tableName != null) {
            // There is a table name, so get a TableName node
            tabName =
                (TableName)nodeFactory.getNode(NodeTypes.TABLE_NAME,
                                               schemaName,
                                               tableName,
                                               new Integer(nextToLastIdentifierToken.beginOffset),
                                               new Integer(nextToLastIdentifierToken.endOffset),
                                               parserContext);
        }

        // Get the column reference
        retval = (ValueNode)nodeFactory.getNode(NodeTypes.COLUMN_REFERENCE,
                                                columnName,
                                                tabName,
                                                new Integer(lastIdentifierToken.beginOffset),
                                                new Integer(lastIdentifierToken.endOffset),
                                                parserContext);

        {if (true) return retval;}
    throw new Error("Missing return statement in function");
  }

  final public ColumnReference columnReference() throws ParseException, StandardException {
    String firstName;
    String secondName = null;
    String thirdName = null;
    String columnName = null;
    String tableName = null;
    String schemaName = null;
    TableName tabName = null;
    firstName = identifierDeferCheckLength();
    if (getToken(1).kind == PERIOD && getToken(3).kind != LEFT_PAREN) {
      jj_consume_token(PERIOD);
      secondName = identifierDeferCheckLength();
      if (getToken(1).kind == PERIOD && getToken(3).kind != LEFT_PAREN) {
        jj_consume_token(PERIOD);
        thirdName = identifierDeferCheckLength();
      } else {
        ;
      }
    } else {
      ;
    }
        // Figure out what each name stands for
        if (thirdName == null) {
            if (secondName == null) {
                // Only one name, must be column name
                columnName = firstName;
            }
            else {
                // Two names: table.column
                tableName = firstName;
                columnName = secondName;
            }
        }
        else {
            // Three names: schema.table.column
            schemaName = firstName;
            tableName = secondName;
            columnName = thirdName;
        }

        parserContext.checkIdentifierLengthLimit(columnName);
        if (schemaName != null)
            parserContext.checkIdentifierLengthLimit(schemaName);
        if (tableName != null)
            parserContext.checkIdentifierLengthLimit(tableName);

        if (tableName != null) {
            tabName = (TableName)nodeFactory.getNode(NodeTypes.TABLE_NAME,
                                                     schemaName,
                                                     tableName,
                                                     new Integer(nextToLastIdentifierToken.beginOffset),
                                                     new Integer(nextToLastIdentifierToken.endOffset),
                                                     parserContext);
            }

        {if (true) return (ColumnReference)nodeFactory.getNode(NodeTypes.COLUMN_REFERENCE,
                                                    columnName,
                                                    tabName,
                                                    new Integer(lastIdentifierToken.beginOffset),
                                                    new Integer(lastIdentifierToken.endOffset),
                                                    parserContext);}
    throw new Error("Missing return statement in function");
  }

/*
void
columnReference() throws StandardException :
{}
{
    /*
    **
    ** I re-wrote the above rule because it caused a grammar ambiguitity.
    ** The problem is that we are parsing a dot-separated list of identifiers,
    ** and the grammar doesn't know what the identifiers stand for, but the
    ** syntax assumed that it did.  For example, in schema.table.column,
    ** the grammar doesn't know when it parses the first identifier whether
    ** it will be a catalog name, schema name, table name, or column name.
    **
    ** I think this problem could be solved by increasing the lookahead.
    ** I will try that solution next.    I like that solution better because,
    ** if it works, it will be easier for the grammar to figure out what
    ** each identifier stands for.
    **

    [ <MODULE> <PERIOD> <IDENTIFIER> |
        [ [ [ <IDENTIFIER> <PERIOD> ] <IDENTIFIER> <PERIOD> ] <IDENTIFIER> <PERIOD> ]
    ]
    <IDENTIFIER>
}
*/
  final public OrderByList orderByClause() throws ParseException, StandardException {
    OrderByList orderCols;
    jj_consume_token(ORDER);
    jj_consume_token(BY);
    orderCols = sortSpecificationList();
        forbidNextValueFor();
        {if (true) return orderCols;}
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel atIsolationLevel() throws ParseException, StandardException {
    IsolationLevel isolationLevel;
    jj_consume_token(WITH);
    isolationLevel = isolationLevelDB2Abbrev();
        {if (true) return isolationLevel;}
    throw new Error("Missing return statement in function");
  }

  final public OrderByList sortSpecificationList() throws ParseException, StandardException {
    OrderByList orderCols = (OrderByList)nodeFactory.getNode(NodeTypes.ORDER_BY_LIST,
                                                             parserContext);
    sortSpecification(orderCols);
    label_21:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[189] = jj_gen;
        break label_21;
      }
      jj_consume_token(COMMA);
      sortSpecification(orderCols);
    }
        {if (true) return orderCols;}
    throw new Error("Missing return statement in function");
  }

  final public void sortSpecification(OrderByList orderCols) throws ParseException, StandardException {
     OrderByColumn orderCol;
    orderCol = sortKey();
    switch (jj_nt.kind) {
    case ASC:
    case DESC:
      orderingSpecification(orderCol);
      break;
    default:
      jj_la1[190] = jj_gen;
      ;
    }
    if (jj_2_57(1)) {
      nullOrdering(orderCol);
    } else {
      ;
    }
        orderCols.addOrderByColumn(orderCol);
  }

  final public OrderByColumn sortKey() throws ParseException, StandardException {
    ValueNode columnExpression;
    columnExpression = additiveExpression();
        {if (true) return (OrderByColumn)nodeFactory.getNode(NodeTypes.ORDER_BY_COLUMN,
                                                  columnExpression,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void orderingSpecification(OrderByColumn orderCol) throws ParseException {
    switch (jj_nt.kind) {
    case ASC:
      jj_consume_token(ASC);
      break;
    case DESC:
      jj_consume_token(DESC);
        orderCol.setDescending();
      break;
    default:
      jj_la1[191] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

/*
 * The data type comparison functions need to know whether NULL values
 * should sort higher than non-NULL values, or lower. The answer to this
 * depends on whether the user specified ASCending or DESCending, and on
 * whether the user specified NULLS FIRST, or NULLS LAST, as follows:
 *
 * +===============+========+========+
 * | ORDER BY says | ASC        | DESC   |
 * +===============+========+========+
 * | NULLS FIRST     | less     | greater|
 * +===============+========+========+
 * | NULLS LAST      | greater| less     |
 * +===============+========+========+
 */
  final public void nullOrdering(OrderByColumn orderCol) throws ParseException {
    if (getToken(2).kind == LAST) {
      jj_consume_token(NULLS);
      jj_consume_token(LAST);
        if (!orderCol.isAscending())
            orderCol.setNullsOrderedLow();
    } else {
      switch (jj_nt.kind) {
      case NULLS:
        jj_consume_token(NULLS);
        jj_consume_token(FIRST);
        if (orderCol.isAscending())
            orderCol.setNullsOrderedLow();
        break;
      default:
        jj_la1[192] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public void offsetOrFetchFirstClause(ValueNode[] offsetAndFetchFirst) throws ParseException, StandardException {
    ValueNode value;
    switch (jj_nt.kind) {
    case OFFSET:
      value = offsetClause();
        if (offsetAndFetchFirst[0] != null)
            {if (true) throw new StandardException("OFFSET specified more than one");}
        offsetAndFetchFirst[0] = value;
      break;
    case FETCH:
      value = fetchFirstClause();
        if (offsetAndFetchFirst[1] != null)
            {if (true) throw new StandardException("FETCH FIRST specified more than one");}
        offsetAndFetchFirst[1] = value;
      break;
    case LIMIT:
      limitClause(offsetAndFetchFirst);
      break;
    default:
      jj_la1[193] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public ValueNode offsetClause() throws ParseException, StandardException {
    ValueNode result = null;
    jj_consume_token(OFFSET);
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
    case EXACT_NUMERIC:
      result = intLiteral();
      break;
    case QUESTION_MARK:
    case DOLLAR_N:
      result = dynamicParameterSpecification();
      break;
    default:
      jj_la1[194] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case ROW:
      jj_consume_token(ROW);
      break;
    case ROWS:
      jj_consume_token(ROWS);
      break;
    default:
      jj_la1[195] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return result;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode fetchFirstClause() throws ParseException, StandardException {
    ValueNode result = null;
    jj_consume_token(FETCH);
    switch (jj_nt.kind) {
    case FIRST:
      jj_consume_token(FIRST);
      break;
    case NEXT:
      jj_consume_token(NEXT);
      break;
    default:
      jj_la1[196] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
    case QUESTION_MARK:
    case DOLLAR_N:
    case EXACT_NUMERIC:
      switch (jj_nt.kind) {
      case PLUS_SIGN:
      case MINUS_SIGN:
      case EXACT_NUMERIC:
        result = intLiteral();
        break;
      case QUESTION_MARK:
      case DOLLAR_N:
        result = dynamicParameterSpecification();
        break;
      default:
        jj_la1[197] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[198] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case ROW:
      jj_consume_token(ROW);
      break;
    case ROWS:
      jj_consume_token(ROWS);
      break;
    default:
      jj_la1[199] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(ONLY);
        // The default number of rows to fetch if the literal is omitted is 1:
        if (result == null)
            result = getNumericNode("1", true);
        {if (true) return result;}
    throw new Error("Missing return statement in function");
  }

  final public void limitClause(ValueNode[] offsetAndFetchFirst) throws ParseException, StandardException {
    ValueNode v1, v2 = null;
    Token tok = null;
    jj_consume_token(LIMIT);
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
    case EXACT_NUMERIC:
      v1 = intLiteral();
      break;
    case QUESTION_MARK:
    case DOLLAR_N:
      v1 = dynamicParameterSpecification();
      break;
    default:
      jj_la1[200] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    if (jj_2_58(1)) {
      switch (jj_nt.kind) {
      case COMMA:
        tok = jj_consume_token(COMMA);
        switch (jj_nt.kind) {
        case PLUS_SIGN:
        case MINUS_SIGN:
        case EXACT_NUMERIC:
          v2 = intLiteral();
          break;
        case QUESTION_MARK:
        case DOLLAR_N:
          v2 = dynamicParameterSpecification();
          break;
        default:
          jj_la1[201] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[203] = jj_gen;
        if (getToken(1).kind == OFFSET &&
                                                getToken(3).kind != ROW &&
                                                getToken(3).kind != ROWS) {
          tok = jj_consume_token(OFFSET);
          switch (jj_nt.kind) {
          case PLUS_SIGN:
          case MINUS_SIGN:
          case EXACT_NUMERIC:
            v2 = intLiteral();
            break;
          case QUESTION_MARK:
          case DOLLAR_N:
            v2 = dynamicParameterSpecification();
            break;
          default:
            jj_la1[202] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    } else {
      ;
    }
        if (offsetAndFetchFirst[1] != null)
            {if (true) throw new StandardException("LIMIT specified more than one");}
        if (v2 == null)
            offsetAndFetchFirst[1] = v1;
        else {
            if (offsetAndFetchFirst[0] != null)
                {if (true) throw new StandardException("LIMIT offset specified more than one");}
            if (tok.kind == OFFSET) {
                offsetAndFetchFirst[0] = v2;
                offsetAndFetchFirst[1] = v1;
            }
            else {
                offsetAndFetchFirst[0] = v1;
                offsetAndFetchFirst[1] = v2;
            }
        }
  }

  final public CursorNode.UpdateMode forUpdateClause(List<String> columnList) throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case UPDATE:
      jj_consume_token(UPDATE);
      switch (jj_nt.kind) {
      case OF:
        jj_consume_token(OF);
        forUpdateColumnList(columnList);
        break;
      default:
        jj_la1[204] = jj_gen;
        ;
      }
        {if (true) return CursorNode.UpdateMode.UPDATE;}
      break;
    case READ:
      jj_consume_token(READ);
      jj_consume_token(ONLY);
        {if (true) return CursorNode.UpdateMode.READ_ONLY;}
      break;
    case FETCH:
      jj_consume_token(FETCH);
      jj_consume_token(ONLY);
        {if (true) return CursorNode.UpdateMode.READ_ONLY;}
      break;
    default:
      jj_la1[205] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void forUpdateColumnList(List<String> columnList) throws ParseException, StandardException {
    forUpdateColumn(columnList);
    label_22:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[206] = jj_gen;
        break label_22;
      }
      jj_consume_token(COMMA);
      forUpdateColumn(columnList);
    }
  }

  final public void forUpdateColumn(List<String> columnList) throws ParseException, StandardException {
    String columnName;
    columnName = identifier();
        columnList.add(columnName);
  }

  final public ResultColumnList setClauseList() throws ParseException, StandardException {
    ResultColumnList    columnList = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                           parserContext);
    setClause(columnList);
    label_23:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[207] = jj_gen;
        break label_23;
      }
      jj_consume_token(COMMA);
      setClause(columnList);
    }
        {if (true) return columnList;}
    throw new Error("Missing return statement in function");
  }

  final public void setClause(ResultColumnList columnList) throws ParseException, StandardException {
    ResultColumn resultColumn;
    ColumnReference columnName;
    ValueNode valueNode;
    /*
         * SQL92 only wants identifiers here (column names) but JBuilder
         * expects table.column, so we allow the general form.
         */
        columnName = columnReference();
    jj_consume_token(EQUALS_OPERATOR);
    valueNode = updateSource(columnName.getColumnName());
        resultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                         columnName,
                                                         valueNode,
                                                         parserContext);
        columnList.addResultColumn(resultColumn);
  }

  final public ValueNode updateSource(String columnName) throws ParseException, StandardException {
    ValueNode valueNode;
    if (jj_2_59(1)) {
      valueNode = orExpression(null);
        {if (true) return valueNode;}
    } else {
      switch (jj_nt.kind) {
      case _DEFAULT:
        jj_consume_token(_DEFAULT);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.DEFAULT_NODE,
                                              columnName,
                                              parserContext);}
        break;
      default:
        jj_la1[208] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode nullSpecification() throws ParseException, StandardException {
    jj_consume_token(NULL);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode insertColumnsAndSource(QueryTreeNode targetTable) throws ParseException, StandardException {
    Properties targetProperties = null;
    ResultSetNode queryExpression;
    ResultColumnList columnList = null;
    OrderByList orderCols = null;
    ValueNode[] offsetAndFetchFirst = new ValueNode[2];
    ResultColumnList returningList = null;
    if (getToken(1).kind == LEFT_PAREN && ! subqueryFollows()) {
      jj_consume_token(LEFT_PAREN);
      columnList = insertColumnList();
      jj_consume_token(RIGHT_PAREN);
    } else {
      ;
    }
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      targetProperties = propertyList(false);
      jj_consume_token(CHECK_PROPERTIES);
      break;
    default:
      jj_la1[209] = jj_gen;
      ;
    }
    queryExpression = queryExpression(null, NO_SET_OP);
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[210] = jj_gen;
      ;
    }
    label_24:
    while (true) {
      switch (jj_nt.kind) {
      case FETCH:
      case OFFSET:
      case LIMIT:
        ;
        break;
      default:
        jj_la1[211] = jj_gen;
        break label_24;
      }
      offsetOrFetchFirstClause(offsetAndFetchFirst);
    }
    switch (jj_nt.kind) {
    case RETURNING:
      jj_consume_token(RETURNING);
      returningList = selectList();
      break;
    default:
      jj_la1[212] = jj_gen;
      ;
    }
        if (orderCols != null && isTableValueConstructor(queryExpression)) {
            // Not allowed by the standard since this is a <contextually typed
            // table value constructor> according SQL 2008, vol2, section 14.11
            // "<insert statement>, SR 17. (I.e. it is not a <subquery> and
            // can't have an ORDER BY).

            {if (true) throw new StandardException("ORDER BY not allowed");}
        }

        if ((offsetAndFetchFirst[0] != null || offsetAndFetchFirst[1] != null) &&
                isTableValueConstructor(queryExpression)) {
            {if (true) throw new StandardException("Not allowed: " +
                                         ((offsetAndFetchFirst[0] != null) ? "OFFSET" : "FETCH"));}
        }

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.INSERT_NODE,
                                                  targetTable,
                                                  columnList,
                                                  queryExpression,
                                                  targetProperties,
                                                  orderCols,
                                                  offsetAndFetchFirst[0],
                                                  offsetAndFetchFirst[1],
                                                  returningList,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList insertColumnList() throws ParseException, StandardException {
    ResultColumnList    columnList = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                           parserContext);
    columnQualifiedNameList(columnList);
        {if (true) return columnList;}
    throw new Error("Missing return statement in function");
  }

  final public void columnQualifiedNameList(ResultColumnList columnList) throws ParseException, StandardException {
    columnQualifiedNameItem(columnList);
    label_25:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[213] = jj_gen;
        break label_25;
      }
      jj_consume_token(COMMA);
      columnQualifiedNameItem(columnList);
    }
  }

  final public void columnQualifiedNameItem(ResultColumnList columnList) throws ParseException, StandardException {
    ColumnReference columnRef;
    ResultColumn resultColumn;
    /*
         * SQL92 only wants identifiers here (column names) but JBuilder
         * expects table.column, so we allow the general form.
         */
        columnRef = columnReference();
        /*
        ** Store the column names for the result columns in the
        ** result column list.  We don't know yet what valueNodes
        ** should be hooked up to each result column, so set that
        ** to null for now.
        */
        resultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                         columnRef,
                                                         null,
                                                         parserContext);
        columnList.addResultColumn(resultColumn);
  }

  final public ResultSetNode rowValueConstructor(ResultSetNode leftRSN) throws ParseException, StandardException {
    ResultColumnList resultColumns = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                           parserContext);
    if (rowValueConstructorListFollows()) {
      jj_consume_token(LEFT_PAREN);
      rowValueConstructorList(resultColumns);
      jj_consume_token(RIGHT_PAREN);
    } else {
      rowValueConstructorElement(resultColumns);
    }
        /* If leftRSN is null, simply return the newRSN.
        * Else verify thst the number of columns is the same in both RSNs' RCLs.
        * If leftRSN is already a list, add to it.
        * Else make a new list with both.
        */
        RowResultSetNode newRSN = (RowResultSetNode)
            nodeFactory.getNode(NodeTypes.ROW_RESULT_SET_NODE,
                                resultColumns,
                                null,
                                parserContext);
        if (leftRSN == null)
            {if (true) return newRSN;}

        if (leftRSN.getResultColumns().size() !=
            newRSN.getResultColumns().size()) {
            {if (true) throw new StandardException("Row value size is different");}
        }

        RowsResultSetNode rows;
        if (leftRSN instanceof RowsResultSetNode)
            rows = (RowsResultSetNode)leftRSN;
        else
            rows = (RowsResultSetNode)
                nodeFactory.getNode(NodeTypes.ROWS_RESULT_SET_NODE,
                                    leftRSN,
                                    parserContext);
        rows.addRow(newRSN);
        {if (true) return rows;}
    throw new Error("Missing return statement in function");
  }

  final public void rowValueConstructorElement(ResultColumnList resultColumns) throws ParseException, StandardException {
    ValueNode value;
    if (simpleLiteralInListFollows()) {
      value = literal();
        resultColumns.addResultColumn(
            (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                              null,
                                              value,
                                              parserContext));
    } else if (jj_2_60(1)) {
      value = orExpression(null);
        resultColumns.addResultColumn(
            (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                              null,
                                              value,
                                              parserContext));
    } else {
      switch (jj_nt.kind) {
      case _DEFAULT:
        jj_consume_token(_DEFAULT);
        resultColumns.addResultColumn(
            (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                              null,
                                              nodeFactory.getNode(NodeTypes.DEFAULT_NODE,
                                                                  null, parserContext),
                                              parserContext));
        break;
      default:
        jj_la1[214] = jj_gen;
        {if (true) throw new StandardException("VALUES is empty");}
      }
    }
  }

  final public void rowValueConstructorList(ResultColumnList resultColumns) throws ParseException, StandardException {
    rowValueConstructorElement(resultColumns);
    label_26:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[215] = jj_gen;
        break label_26;
      }
      jj_consume_token(COMMA);
      rowValueConstructorElement(resultColumns);
    }
  }

  final public SubqueryNode tableSubquery(SubqueryNode.SubqueryType subqueryType, ValueNode leftOperand) throws ParseException, StandardException {
    SubqueryNode subqueryNode;
    subqueryNode = subquery(subqueryType, leftOperand);
        {if (true) return subqueryNode;}
    throw new Error("Missing return statement in function");
  }

  final public SubqueryNode subquery(SubqueryNode.SubqueryType subqueryType, ValueNode leftOperand) throws ParseException, StandardException {
    ResultSetNode queryExpression;
    SubqueryNode subqueryNode;
    OrderByList orderCols = null;
    ValueNode[] offsetAndFetchFirst = new ValueNode[2];
    queryExpression = queryExpression(null, NO_SET_OP);
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[216] = jj_gen;
      ;
    }
    label_27:
    while (true) {
      switch (jj_nt.kind) {
      case FETCH:
      case OFFSET:
      case LIMIT:
        ;
        break;
      default:
        jj_la1[217] = jj_gen;
        break label_27;
      }
      offsetOrFetchFirstClause(offsetAndFetchFirst);
    }
        subqueryNode = (SubqueryNode)nodeFactory.getNode(NodeTypes.SUBQUERY_NODE,
                                                         queryExpression,
                                                         subqueryType,
                                                         leftOperand,
                                                         orderCols,
                                                         offsetAndFetchFirst[0],
                                                         offsetAndFetchFirst[1],
                                                         parserContext);
        {if (true) return subqueryNode;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode inPredicateValue(ValueNode leftOperand) throws ParseException, StandardException {
    ValueNode retval;
    int count[] = new int[]{0};
    if (leftParenAndSubqueryFollows()) {
      jj_consume_token(LEFT_PAREN);
      retval = tableSubquery(SubqueryNode.SubqueryType.IN, leftOperand);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return retval;}
    } else {
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        retval = rowCtor(count);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.IN_LIST_OPERATOR_NODE,
                                              leftOperand,
                                              retval,
                                              parserContext);}
        break;
      default:
        jj_la1[218] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode inValueList(ValueNode leftOperand) throws ParseException, StandardException {
    ValueNodeList inList = (ValueNodeList)nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST,
                                                              parserContext);
    inElement(inList);
    label_28:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[219] = jj_gen;
        break label_28;
      }
      jj_consume_token(COMMA);
      inElement(inList);
    }
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.IN_LIST_OPERATOR_NODE,
                                              leftOperand,
                                              inList,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void inElement(ValueNodeList inList) throws ParseException, StandardException {
    ValueNode valueNode;
    valueNode = additiveExpression();
        inList.addValueNode(valueNode);
  }

  final public ValueNode rowCtor(int count[]) throws ParseException, StandardException {
    ValueNodeList list = (ValueNodeList)nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST,
                                                            parserContext);
    jj_consume_token(LEFT_PAREN);
    getRow(list, count);
    jj_consume_token(RIGHT_PAREN);
        ++count[0];
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.ROW_CTOR_NODE,
                                              list,
                                              new int[]{count[0]},
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void getRow(ValueNodeList rowList, int count[]) throws ParseException, StandardException {
    int max = count[0];
    max = rowElement(rowList, new int[]{count[0]}, max);
    label_29:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[220] = jj_gen;
        break label_29;
      }
      jj_consume_token(COMMA);
      max = rowElement(rowList, new int[]{count[0]}, max);
    }
        count[0] = max;
        {if (true) return;}
  }

  final public int rowElement(ValueNodeList list, int count[], int max) throws ParseException, StandardException {
    ValueNode element = null;
    if (jj_2_61(1)) {
      if (rowValueConstructorListFollows()) {

      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
      element = rowCtor(count);
    } else if (jj_2_62(1)) {
      element = additiveExpression();
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
        list.addValueNode(element);
        {if (true) return max >= count[0] ? max : count[0];}
    throw new Error("Missing return statement in function");
  }

  final public SubqueryNode.SubqueryType quantifier(BinaryOperatorNode.OperatorType opType) throws ParseException, StandardException {
    SubqueryNode.SubqueryType retval = null;
    switch (jj_nt.kind) {
    case ALL:
      jj_consume_token(ALL);
        switch (opType) {
        case EQ:
            retval = SubqueryNode.SubqueryType.EQ_ALL;
            break;

        case NE:
            retval = SubqueryNode.SubqueryType.NE_ALL;
            break;

        case LE:
            retval = SubqueryNode.SubqueryType.LE_ALL;
            break;

        case LT:
            retval = SubqueryNode.SubqueryType.LT_ALL;
            break;

        case GE:
            retval = SubqueryNode.SubqueryType.GE_ALL;
            break;

        case GT:
            retval = SubqueryNode.SubqueryType.GT_ALL;
            break;

        default:
            assert false : "Invalid value for opType (" + opType + ") passed to quantifier()";
        }
        {if (true) return retval;}
      break;
    case ANY:
    case SOME:
      some();
        switch (opType) {
        case EQ:
            retval = SubqueryNode.SubqueryType.EQ_ANY;
            break;

        case NE:
            retval = SubqueryNode.SubqueryType.NE_ANY;
            break;

        case LE:
            retval = SubqueryNode.SubqueryType.LE_ANY;
            break;

        case LT:
            retval = SubqueryNode.SubqueryType.LT_ANY;
            break;

        case GE:
            retval = SubqueryNode.SubqueryType.GE_ANY;
            break;

        case GT:
            retval = SubqueryNode.SubqueryType.GT_ANY;
            break;

        default:
            assert false : "Invalid value for opType (" + opType + ") passed to quantifier()";
        }
        {if (true) return retval;}
      break;
    default:
      jj_la1[221] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void some() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case SOME:
      jj_consume_token(SOME);
      break;
    case ANY:
      jj_consume_token(ANY);
      break;
    default:
      jj_la1[222] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public SubqueryNode existsExpression() throws ParseException, StandardException {
    SubqueryNode subqueryNode;
    jj_consume_token(EXISTS);
    jj_consume_token(LEFT_PAREN);
    subqueryNode = tableSubquery(SubqueryNode.SubqueryType.EXISTS, null);
    jj_consume_token(RIGHT_PAREN);
        {if (true) return subqueryNode;}
    throw new Error("Missing return statement in function");
  }

  final public SelectNode tableExpression(ResultColumnList selectList) throws ParseException, StandardException {
    SelectNode selectNode;
    FromList fromList = null;
    ValueNode whereClause = null;
    GroupByList groupByList = null;
    ValueNode havingClause = null;
    Token whereToken;
    WindowList windows = null;
    switch (jj_nt.kind) {
    case FROM:
      fromList = fromClause();
      break;
    default:
      jj_la1[223] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case WHERE:
      whereToken = jj_consume_token(WHERE);
      whereClause = whereClause(whereToken);
      break;
    default:
      jj_la1[224] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case GROUP:
      groupByList = groupByClause();
      break;
    default:
      jj_la1[225] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case HAVING:
      havingClause = havingClause();
      break;
    default:
      jj_la1[226] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case WINDOW:
      windows = windowClause();
      break;
    default:
      jj_la1[227] = jj_gen;
      ;
    }
        // fix for HAVING without GROUP BY, makes sure we get one
        // aggregate operator by adding a count(*), this fixes beetle 5853, 5890
        if (havingClause != null && groupByList == null) {
            ValueNode vn = (ValueNode)nodeFactory.getNode(NodeTypes.AGGREGATE_NODE,
                                                          null,
                                                          "CountAggregateDefinition",
                                                          Boolean.FALSE, // distinct Boolean.TRUE?
                                                          "COUNT(*)",
                                                          parserContext);
            AggregateNode n = (AggregateNode)vn;
            // TODO: Needed somewhere else.
            // n.replaceAggregatesWithColumnReferences(selectList, 0);          
        }

        if (fromList == null)
          fromList = (FromList)nodeFactory.getNode(NodeTypes.FROM_LIST,
                                                   true,
                                                   parserContext);

        selectNode = (SelectNode)nodeFactory.getNode(NodeTypes.SELECT_NODE,
                                                     selectList,
                                                     null,       /* AGGREGATE list */
                                                     fromList,
                                                     whereClause,
                                                     groupByList,
                                                     havingClause,
                                                     windows,
                                                     parserContext);

        {if (true) return selectNode;}
    throw new Error("Missing return statement in function");
  }

  final public FromList fromClause() throws ParseException, StandardException {
    FromList fromList = (FromList)nodeFactory.getNode(NodeTypes.FROM_LIST,
                                                      true, // nodeFactory.doJoinOrderOptimization()
                                                      parserContext);
    int tokKind;
    Token beginToken, endToken;
    jj_consume_token(FROM);
             beginToken = getToken(1);
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      fromListProperties(fromList);
      break;
    default:
      jj_la1[228] = jj_gen;
      ;
    }
    tableReferences(fromList);
    label_30:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[229] = jj_gen;
        break label_30;
      }
      jj_consume_token(COMMA);
      tableReferences(fromList);
    }
                                             endToken = getToken(0);
        fromList.setBeginOffset(beginToken.beginOffset);
        fromList.setEndOffset(endToken.endOffset);
        {if (true) return fromList;}
    throw new Error("Missing return statement in function");
  }

  final public void fromListProperties(FromList fromList) throws ParseException, StandardException {
    Properties properties;
    properties = propertyList(true);
    jj_consume_token(CHECK_PROPERTIES);
        fromList.setProperties(properties);
  }

  final public void tableReferences(FromList fromList) throws ParseException, StandardException {
    FromTable tableReference;
    if (getToken(1).kind == TABLE && getToken(2).kind == LEFT_PAREN &&
                                     (getToken(3).kind == SELECT || getToken(3).kind == VALUES)) {
      jj_consume_token(TABLE);
      tableReference = tableReferenceTypes(false);
        fromList.addFromTable(tableReference);
    } else if (jj_2_63(1)) {
      tableReference = tableReferenceTypes(false);
        fromList.addFromTable(tableReference);
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public FromTable tableReferenceTypes(boolean nestedInParens) throws ParseException, StandardException {
    FromTable tableReference;
    if (jj_2_64(1)) {
      tableReference = tableReference(nestedInParens);
        {if (true) return tableReference ;}
    } else {
      switch (jj_nt.kind) {
      case LEFT_BRACE:
        jj_consume_token(LEFT_BRACE);
        jj_consume_token(OJ);
        tableReference = tableReference(nestedInParens);
        jj_consume_token(RIGHT_BRACE);
        {if (true) return tableReference;}
        break;
      default:
        jj_la1[230] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public Object[] optionalTableClauses() throws ParseException, StandardException {
    Object[] otc = null;
    Properties tableProperties = null;
    ResultColumnList derivedRCL = null;
    String correlationName = null;
    IndexHintList indexHints = null;
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      otc = optionalTableProperties();
        otc[OPTIONAL_TABLE_DERIVED_RCL] = derivedRCL;
        otc[OPTIONAL_TABLE_CORRELATION_NAME] = correlationName;
        {if (true) return otc;}
      break;
    default:
      jj_la1[235] = jj_gen;
      if (indexHintFollows(1)) {
        indexHints = indexHints();
        otc = new Object[OPTIONAL_TABLE_NCLAUSES];
        otc[OPTIONAL_TABLE_MYSQL_INDEX_HINTS] = indexHints;
        {if (true) return otc;}
      } else {
        if (jj_2_65(1)) {
          switch (jj_nt.kind) {
          case AS:
            jj_consume_token(AS);
            break;
          default:
            jj_la1[231] = jj_gen;
            ;
          }
          correlationName = identifier();
          switch (jj_nt.kind) {
          case LEFT_PAREN:
            jj_consume_token(LEFT_PAREN);
            derivedRCL = derivedColumnList();
            jj_consume_token(RIGHT_PAREN);
            break;
          default:
            jj_la1[232] = jj_gen;
            ;
          }
          switch (jj_nt.kind) {
          case FORCE:
          case IGNORE:
          case USE:
            indexHints = indexHints();
            break;
          default:
            jj_la1[233] = jj_gen;
            ;
          }
          switch (jj_nt.kind) {
          case DERBYDASHPROPERTIES:
            tableProperties = propertyList(true);
            jj_consume_token(CHECK_PROPERTIES);
            break;
          default:
            jj_la1[234] = jj_gen;
            ;
          }
        } else {
          ;
        }
        otc = new Object[OPTIONAL_TABLE_NCLAUSES];
        otc[OPTIONAL_TABLE_PROPERTIES] = tableProperties;
        otc[OPTIONAL_TABLE_DERIVED_RCL] = derivedRCL;
        otc[OPTIONAL_TABLE_CORRELATION_NAME] = correlationName;
        otc[OPTIONAL_TABLE_MYSQL_INDEX_HINTS] = indexHints;
        {if (true) return otc;}
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public Object[] optionalTableProperties() throws ParseException, StandardException {
    Object[] otc = null;
    Properties tableProperties = null;
    tableProperties = propertyList(true);
    jj_consume_token(CHECK_PROPERTIES);
        otc = new Object[OPTIONAL_TABLE_NCLAUSES];
        otc[OPTIONAL_TABLE_PROPERTIES] = tableProperties;
        {if (true) return otc;}
    throw new Error("Missing return statement in function");
  }

  final public IndexHintList indexHints() throws ParseException, StandardException {
    IndexHintList list = (IndexHintList)nodeFactory.getNode(NodeTypes.INDEX_HINT_LIST,
                                                            parserContext);
    indexHintList(list);
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void indexHintList(IndexHintList list) throws ParseException, StandardException {
    indexHint(list);
    label_31:
    while (true) {
      if (getToken(1).kind != COMMA && indexHintFollows(2)) {
        ;
      } else {
        break label_31;
      }
      jj_consume_token(COMMA);
      indexHint(list);
    }
  }

  final public void indexHint(IndexHintList list) throws ParseException, StandardException {
    IndexHintNode.HintType hintType;
    IndexHintNode.HintScope hintScope = null;
    List<String> indexes = new ArrayList<String>();
    hintType = indexHintType();
    switch (jj_nt.kind) {
    case INDEX:
      jj_consume_token(INDEX);
      break;
    case KEY:
      jj_consume_token(KEY);
      break;
    default:
      jj_la1[236] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case FOR:
      jj_consume_token(FOR);
      hintScope = indexHintScope();
      break;
    default:
      jj_la1[237] = jj_gen;
      ;
    }
    jj_consume_token(LEFT_PAREN);
    if (jj_2_66(1)) {
      indexHintIndex(indexes);
      label_32:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[238] = jj_gen;
          break label_32;
        }
        jj_consume_token(COMMA);
        indexHintIndex(indexes);
      }
    } else {
      ;
    }
    jj_consume_token(RIGHT_PAREN);
        IndexHintNode indexHint = (IndexHintNode)
            nodeFactory.getNode(NodeTypes.INDEX_HINT_NODE,
                                hintType,
                                hintScope,
                                indexes,
                                parserContext);
        list.add(indexHint);
  }

  final public IndexHintNode.HintType indexHintType() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case USE:
      jj_consume_token(USE);
      {if (true) return IndexHintNode.HintType.USE;}
      break;
    case IGNORE:
      jj_consume_token(IGNORE);
      {if (true) return IndexHintNode.HintType.IGNORE;}
      break;
    case FORCE:
      jj_consume_token(FORCE);
      {if (true) return IndexHintNode.HintType.FORCE;}
      break;
    default:
      jj_la1[239] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public IndexHintNode.HintScope indexHintScope() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case JOIN:
      jj_consume_token(JOIN);
      {if (true) return IndexHintNode.HintScope.JOIN;}
      break;
    case ORDER:
      jj_consume_token(ORDER);
      jj_consume_token(BY);
      {if (true) return IndexHintNode.HintScope.ORDER_BY;}
      break;
    case GROUP:
      jj_consume_token(GROUP);
      jj_consume_token(BY);
      {if (true) return IndexHintNode.HintScope.GROUP_BY;}
      break;
    default:
      jj_la1[240] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void indexHintIndex(List<String> indexes) throws ParseException, StandardException {
    String index;
    Token token;
    if (jj_2_67(1)) {
      index = identifier();
        indexes.add(index);
    } else {
      switch (jj_nt.kind) {
      case PRIMARY:
        token = jj_consume_token(PRIMARY);
        indexes.add(token.image);
        break;
      default:
        jj_la1[241] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public FromTable tableReference(boolean nestedInParens) throws ParseException, StandardException {
    FromTable fromTable;
    TableOperatorNode joinTable = null;
    fromTable = tableFactor();
    label_33:
    while (true) {
      if (joinedTableExpressionFollows()) {
        ;
      } else {
        break label_33;
      }
      joinTable = joinedTableExpression((joinTable == null) ? fromTable : joinTable,
                                                nestedInParens);
    }
        {if (true) return joinTable == null ? fromTable : joinTable;}
    throw new Error("Missing return statement in function");
  }

  final public FromTable tableFactor() throws ParseException, StandardException {
    JavaToSQLValueNode javaToSQLNode = null;
    TableName tableName;
    String correlationName = null;
    ResultColumnList derivedRCL = null;
    FromTable fromTable;
    FromTable tableReference;
    Object[] optionalTableClauses = new Object[OPTIONAL_TABLE_NCLAUSES];
    Properties tableProperties = null;
    SubqueryNode derivedTable;
    if (jj_2_68(1)) {
      if (newInvocationFollows(1)) {
        javaToSQLNode = newInvocation();
      } else {
        switch (jj_nt.kind) {
        case TABLE:
          javaToSQLNode = vtiTableConstruct();
          break;
        default:
          jj_la1[242] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
      switch (jj_nt.kind) {
      case AS:
        jj_consume_token(AS);
        break;
      default:
        jj_la1[243] = jj_gen;
        ;
      }
      correlationName = identifier();
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        derivedRCL = derivedColumnList();
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[244] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case DERBYDASHPROPERTIES:
        optionalTableClauses = optionalTableProperties();
        break;
      default:
        jj_la1[245] = jj_gen;
        ;
      }
        fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_VTI,
                                                   javaToSQLNode.getJavaValueNode(),
                                                   correlationName,
                                                   derivedRCL,
                                                   ((optionalTableClauses != null) ?
                                                    (Properties)optionalTableClauses[OPTIONAL_TABLE_PROPERTIES] :
                                                    (Properties)null),
                                                   parserContext);
        {if (true) return fromTable;}
    } else if (jj_2_69(1)) {
      tableName = qualifiedName();
      optionalTableClauses = optionalTableClauses();
        fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_BASE_TABLE,
                                                   tableName,
                                                   optionalTableClauses[OPTIONAL_TABLE_CORRELATION_NAME],
                                                   optionalTableClauses[OPTIONAL_TABLE_DERIVED_RCL],
                                                   optionalTableClauses[OPTIONAL_TABLE_PROPERTIES],
                                                   optionalTableClauses[OPTIONAL_TABLE_MYSQL_INDEX_HINTS],
                                                   parserContext);
        {if (true) return fromTable;}
    } else if (getToken(1).kind == LEFT_PAREN &&
                                     (getToken(2).kind == SELECT || getToken(2).kind == VALUES)) {
      derivedTable = derivedTable();
      switch (jj_nt.kind) {
      case AS:
        jj_consume_token(AS);
        break;
      default:
        jj_la1[246] = jj_gen;
        ;
      }
      correlationName = identifier();
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        derivedRCL = derivedColumnList();
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[247] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case DERBYDASHPROPERTIES:
        optionalTableClauses = optionalTableProperties();
        break;
      default:
        jj_la1[248] = jj_gen;
        ;
      }
        fromTable = (FromTable)nodeFactory.getNode(NodeTypes.FROM_SUBQUERY,
                                                   derivedTable.getResultSet(),
                                                   derivedTable.getOrderByList(),
                                                   derivedTable.getOffset(),
                                                   derivedTable.getFetchFirst(),
                                                   correlationName,
                                                   derivedRCL,
                                                   ((optionalTableClauses != null) ?
                                                    (Properties)optionalTableClauses[OPTIONAL_TABLE_PROPERTIES] :
                                                    (Properties)null),
                                                   parserContext);

        {if (true) return fromTable;}
    } else {
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        tableReference = tableReferenceTypes(true);
        jj_consume_token(RIGHT_PAREN);
        fromTable = tableReference;
        {if (true) return fromTable;}
        break;
      default:
        jj_la1[249] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList derivedColumnList() throws ParseException, StandardException {
    ResultColumnList resultColumns = (ResultColumnList)
        nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                            parserContext);
    columnNameList(resultColumns);
        {if (true) return resultColumns;}
    throw new Error("Missing return statement in function");
  }

  final public void columnNameList(ResultColumnList columnList) throws ParseException, StandardException {
    columnNameItem(columnList);
    label_34:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[250] = jj_gen;
        break label_34;
      }
      jj_consume_token(COMMA);
      columnNameItem(columnList);
    }
  }

  final public void columnNameItem(ResultColumnList columnList) throws ParseException, StandardException {
    String columnName;
    ResultColumn resultColumn;
    columnName = identifier();
        /*
        ** Store the column names for the result columns in the
        ** result column list.  We don't know yet what valueNodes
        ** should be hooked up to each result column, so set that
        ** to null for now.
        */
        resultColumn = (ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                         columnName,
                                                         null,
                                                         parserContext);
        columnList.addResultColumn(resultColumn);
  }

  final public IndexColumn getIndexColumn() throws ParseException, StandardException {
    String columnName;
    columnName = identifier();
        {if (true) return (IndexColumn) nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                                 columnName,
                                                 Boolean.TRUE,
                                                 parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void indexItemList(IndexColumnList columnList) throws ParseException, StandardException {
    indexItem(columnList);
    label_35:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[251] = jj_gen;
        break label_35;
      }
      jj_consume_token(COMMA);
      indexItem(columnList);
    }
  }

  final public void indexItem(IndexColumnList columnList) throws ParseException, StandardException {
    String columnName;
    boolean asc = true;
    switch (jj_nt.kind) {
    case Z_ORDER_LAT_LON:
      jj_consume_token(Z_ORDER_LAT_LON);
      jj_consume_token(LEFT_PAREN);
      columnName = identifier();
          int latPosition = columnList.size();
          IndexColumn lat = (IndexColumn)
              nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                  columnName,
                                  Boolean.FALSE,
                                  parserContext);
          columnList.add(lat);
      jj_consume_token(COMMA);
      columnName = identifier();
          IndexColumn lon = (IndexColumn)
              nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                  columnName,
                                  Boolean.FALSE,
                                  parserContext);
          columnList.add(lon);
      jj_consume_token(RIGHT_PAREN);
          columnList.applyFunction(IndexColumnList.FunctionType.Z_ORDER_LAT_LON,
                                   latPosition,
                                   2);
      break;
    default:
      jj_la1[254] = jj_gen;
      if (jj_2_70(1)) {
        columnName = identifier();
        switch (jj_nt.kind) {
        case ASC:
        case DESC:
          switch (jj_nt.kind) {
          case ASC:
            jj_consume_token(ASC);
            break;
          case DESC:
            jj_consume_token(DESC);
                         asc = false;
            break;
          default:
            jj_la1[252] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
          break;
        default:
          jj_la1[253] = jj_gen;
          ;
        }
          IndexColumn indexColumn = (IndexColumn)
              nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                  columnName,
                                  asc ? Boolean.TRUE : Boolean.FALSE,
                                  parserContext);
          columnList.add(indexColumn);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public void indexUnorderedColumnItem(IndexColumnList columnList) throws ParseException, StandardException {
    String columnName;
    columnName = identifier();
        IndexColumn indexColumn = (IndexColumn)
            nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                columnName,
                                Boolean.TRUE,
                                parserContext);
        columnList.add(indexColumn);
  }

  final public SubqueryNode derivedTable() throws ParseException, StandardException {
    SubqueryNode tableSubquery;
    jj_consume_token(LEFT_PAREN);
    tableSubquery = tableSubquery(SubqueryNode.SubqueryType.FROM, null);
    jj_consume_token(RIGHT_PAREN);
        {if (true) return tableSubquery;}
    throw new Error("Missing return statement in function");
  }

  final public TableOperatorNode joinedTableExpression(ResultSetNode leftRSN, boolean nestedInParens) throws ParseException, StandardException {
    TableOperatorNode joinNode;
    switch (jj_nt.kind) {
    case CROSS:
      joinNode = crossJoin(leftRSN, nestedInParens);
        {if (true) return joinNode;}
      break;
    default:
      jj_la1[255] = jj_gen;
      if (jj_2_71(1)) {
        joinNode = qualifiedJoin(leftRSN, nestedInParens);
        {if (true) return joinNode;}
      } else {
        switch (jj_nt.kind) {
        case NATURAL:
          joinNode = naturalJoin(leftRSN, nestedInParens);
        {if (true) return joinNode;}
          break;
        default:
          jj_la1[256] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public TableOperatorNode crossJoin(ResultSetNode leftRSN, boolean nestedInParens) throws ParseException, StandardException {
    ResultSetNode rightRSN;
    jj_consume_token(CROSS);
    jj_consume_token(JOIN);
    rightRSN = tableFactor();
                TableOperatorNode ton = newJoinNode(leftRSN,
                                                    rightRSN,
                                                    null, // no ON clause in CROSS JOIN
                                                    null, // no USING clause in CROSS JOIN
                                                    JoinNode.JoinType.INNER);
                ton.setNestedInParens(nestedInParens);
                {if (true) return ton;}
    throw new Error("Missing return statement in function");
  }

  final public TableOperatorNode qualifiedJoin(ResultSetNode leftRSN, boolean nestedInParens) throws ParseException, StandardException {
    JoinNode.JoinType joinType;
    ResultSetNode rightRSN;
    TableOperatorNode ton = null;
    Object[] onOrUsingClause = null;
    ResultColumnList usingClause = null;
    ValueNode onClause;
    joinType = qualifiedJoinType();
    rightRSN = tableReferenceTypes(nestedInParens);
    onOrUsingClause = joinSpecification(leftRSN, rightRSN);
        /* If NATURAL or CROSS is specified, then no joinSpecification()
         * is required, otherwise it is required. NATURAL and CROSS should
         * be handled by other rules, so this rule should always see a
         * joinSpecification().
         */

        /* Figure out whether an ON or USING clause was used */
        onClause = (ValueNode)onOrUsingClause[JOIN_ON];
        usingClause = (ResultColumnList)onOrUsingClause[JOIN_USING];

        if (onClause == null && usingClause == null) {
            {if (true) throw new StandardException("Missing JOIN specification");}
        }

        ton = newJoinNode(leftRSN, rightRSN, onClause, usingClause, joinType);

        /* Mark whether or not we are nested within parens */
        ton.setNestedInParens(nestedInParens);
        {if (true) return ton;}
    throw new Error("Missing return statement in function");
  }

  final public TableOperatorNode naturalJoin(ResultSetNode leftRSN, boolean nestedInParens) throws ParseException, StandardException {
    JoinNode.JoinType joinType = JoinNode.JoinType.INNER;
    ResultSetNode rightRSN;
    jj_consume_token(NATURAL);
    switch (jj_nt.kind) {
    case FULL:
    case INNER:
    case LEFT:
    case RIGHT:
      joinType = joinType();
      break;
    default:
      jj_la1[257] = jj_gen;
      ;
    }
    jj_consume_token(JOIN);
    rightRSN = tableFactor();
        JoinNode node = newJoinNode(leftRSN, rightRSN, null, null, joinType);
        node.setNestedInParens(nestedInParens);
        node.setNaturalJoin();
        {if (true) return node;}
    throw new Error("Missing return statement in function");
  }

  final public JoinNode.JoinType joinType() throws ParseException, StandardException {
    JoinNode.JoinType joinType;
    switch (jj_nt.kind) {
    case INNER:
      jj_consume_token(INNER);
        {if (true) return JoinNode.JoinType.INNER;}
      break;
    case FULL:
    case LEFT:
    case RIGHT:
      joinType = outerJoinType();
      switch (jj_nt.kind) {
      case OUTER:
        jj_consume_token(OUTER);
        break;
      default:
        jj_la1[258] = jj_gen;
        ;
      }
        {if (true) return joinType;}
      break;
    default:
      jj_la1[259] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public JoinNode.JoinType outerJoinType() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case LEFT:
      jj_consume_token(LEFT);
        {if (true) return JoinNode.JoinType.LEFT_OUTER;}
      break;
    case RIGHT:
      jj_consume_token(RIGHT);
        {if (true) return JoinNode.JoinType.RIGHT_OUTER;}
      break;
    case FULL:
      jj_consume_token(FULL);
        {if (true) return JoinNode.JoinType.FULL_OUTER;}
      break;
    default:
      jj_la1[260] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public JoinNode.JoinType qualifiedJoinType() throws ParseException, StandardException {
    JoinNode.JoinType joinType = JoinNode.JoinType.INNER;
    if (straightJoinFollows()) {
      jj_consume_token(STRAIGHT_JOIN);
        {if (true) return JoinNode.JoinType.STRAIGHT;}
    } else {
      switch (jj_nt.kind) {
      case FULL:
      case INNER:
      case JOIN:
      case LEFT:
      case RIGHT:
        switch (jj_nt.kind) {
        case FULL:
        case INNER:
        case LEFT:
        case RIGHT:
          joinType = joinType();
          break;
        default:
          jj_la1[261] = jj_gen;
          ;
        }
        jj_consume_token(JOIN);
        {if (true) return joinType;}
        break;
      default:
        jj_la1[262] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public Object[] joinSpecification(ResultSetNode leftRSN, ResultSetNode rightRSN) throws ParseException, StandardException {
    Object[] onOrUsingClause = new Object[JOIN_NCLAUSES];
    ResultColumnList usingClause = null;
    ValueNode joinClause = null;
    switch (jj_nt.kind) {
    case ON:
      joinClause = joinCondition();
        onOrUsingClause[JOIN_ON] = joinClause;
        onOrUsingClause[JOIN_USING] = usingClause;
        {if (true) return onOrUsingClause;}
      break;
    case USING:
      usingClause = namedColumnsJoin();
        onOrUsingClause[JOIN_ON] = joinClause;
        onOrUsingClause[JOIN_USING] = usingClause;
        {if (true) return onOrUsingClause;}
      break;
    default:
      jj_la1[263] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode joinCondition() throws ParseException, StandardException {
    ValueNode joinClause;
    jj_consume_token(ON);
    joinClause = valueExpression();
        {if (true) return joinClause;}
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList namedColumnsJoin() throws ParseException, StandardException {
    ResultColumnList usingClause = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                         parserContext);
    jj_consume_token(USING);
    jj_consume_token(LEFT_PAREN);
    columnNameList(usingClause);
    jj_consume_token(RIGHT_PAREN);
        {if (true) return usingClause;}
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode tableValueConstructor() throws ParseException, StandardException {
    ResultSetNode    resultSetNode;
    jj_consume_token(VALUES);
    resultSetNode = tableValueConstructorList();
        {if (true) return resultSetNode;}
    throw new Error("Missing return statement in function");
  }

  final public ResultSetNode tableValueConstructorList() throws ParseException, StandardException {
    ResultSetNode resultSetNode;
    resultSetNode = rowValueConstructor(null);
    label_36:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[264] = jj_gen;
        break label_36;
      }
      jj_consume_token(COMMA);
      resultSetNode = rowValueConstructor(resultSetNode);
    }
        if (resultSetNode instanceof UnionNode) {
            ((UnionNode)resultSetNode).markTopTableConstructor();
        }
        {if (true) return resultSetNode;}
    throw new Error("Missing return statement in function");
  }

  final public void checkOptionalParens() throws ParseException, StandardException {
    if (parensFollow()) {
      jj_consume_token(LEFT_PAREN);
      jj_consume_token(RIGHT_PAREN);
        {if (true) return;}
    } else {
        {if (true) return;}
    }
  }

  final public ValueNode datetimeValueFunction() throws ParseException, StandardException {
    int prec = -1;
    if ((getToken(1).kind == CURRENT && getToken(2).kind == DATE)) {
      jj_consume_token(CURRENT);
      jj_consume_token(DATE);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.DATE,
                                              parserContext);}
    } else {
      switch (jj_nt.kind) {
      case CURRENT_DATE:
        jj_consume_token(CURRENT_DATE);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.DATE,
                                              parserContext);}
        break;
      default:
        jj_la1[265] = jj_gen;
        if ((getToken(1).kind == CURRENT && getToken(2).kind == TIME)) {
          jj_consume_token(CURRENT);
          jj_consume_token(TIME);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.TIME,
                                              parserContext);}
        } else {
          switch (jj_nt.kind) {
          case CURRENT_TIME:
            jj_consume_token(CURRENT_TIME);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.TIME,
                                              parserContext);}
            break;
          default:
            jj_la1[266] = jj_gen;
            if ((getToken(1).kind == CURRENT && getToken(2).kind == TIMESTAMP)) {
              jj_consume_token(CURRENT);
              jj_consume_token(TIMESTAMP);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.TIMESTAMP,
                                              parserContext);}
            } else {
              switch (jj_nt.kind) {
              case CURRENT_TIMESTAMP:
                jj_consume_token(CURRENT_TIMESTAMP);
        checkOptionalParens();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_DATETIME_OPERATOR_NODE,
                                              CurrentDatetimeOperatorNode.Field.TIMESTAMP,
                                              parserContext);}
                break;
              default:
                jj_la1[267] = jj_gen;
                jj_consume_token(-1);
                throw new ParseException();
              }
            }
          }
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

/*
* Note that set function and aggregate are used interchangeably in the
* parser.    The tree has aggregate nodes.
*/
  final public ValueNode windowOrAggregateFunctionNode() throws ParseException, StandardException {
    ValueNode winOrAgg;
    QueryTreeNode window = null;
    switch (jj_nt.kind) {
    case COUNT:
      jj_consume_token(COUNT);
      jj_consume_token(LEFT_PAREN);
      switch (jj_nt.kind) {
      case ASTERISK:
        jj_consume_token(ASTERISK);
            winOrAgg = (ValueNode)nodeFactory.getNode(NodeTypes.AGGREGATE_NODE,
                                                      null,
                                                      "CountAggregateDefinition",
                                                      Boolean.FALSE,
                                                      "COUNT(*)",
                                                      parserContext);
        break;
      default:
        jj_la1[268] = jj_gen;
        if (jj_2_72(1)) {
          winOrAgg = aggregateExpression("COUNT", "CountAggregateDefinition");
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
      jj_consume_token(RIGHT_PAREN);
      if (jj_2_73(1)) {
        window = overClause();
      } else {
        ;
      }
        if (window != null) {
            winOrAgg = (ValueNode)nodeFactory.getNode(NodeTypes.AGGREGATE_WINDOW_FUNCTION_NODE,
                                                      window,
                                                      winOrAgg,
                                                      parserContext);
        }

        {if (true) return winOrAgg;}
      break;
    case GROUP_CONCAT:
      jj_consume_token(GROUP_CONCAT);
      winOrAgg = groupConcatExpression();
        {if (true) return winOrAgg;}
      break;
    case AVG:
    case MAX:
    case MIN:
    case SUM:
      winOrAgg = generalAggregate();
      if (jj_2_74(1)) {
        window = overClause();
      } else {
        ;
      }
        if (window != null) {
            winOrAgg = (ValueNode)nodeFactory.getNode(NodeTypes.AGGREGATE_WINDOW_FUNCTION_NODE,
                                                      window,
                                                      winOrAgg,
                                                      parserContext);
        }

        {if (true) return winOrAgg;}
      break;
    case ROWNUMBER:
      jj_consume_token(ROWNUMBER);
      jj_consume_token(LEFT_PAREN);
      jj_consume_token(RIGHT_PAREN);
      window = overClause();
        winOrAgg = (ValueNode)nodeFactory.getNode(NodeTypes.ROW_NUMBER_FUNCTION_NODE,
                                                  null,
                                                  window,
                                                  parserContext);
        {if (true) return winOrAgg;}
      break;
    default:
      jj_la1[269] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public QueryTreeNode overClause() throws ParseException, StandardException {
    String windowRef;
    PartitionByList partitionCols = null;
    OrderByList orderCols = null;
    if (getToken(2).kind == LEFT_PAREN || getToken(2).kind == IDENTIFIER) {

    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(OVER);
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      switch (jj_nt.kind) {
      case PARTITION:
        partitionCols = partitionByClause();
        break;
      default:
        jj_la1[270] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case ORDER:
        orderCols = orderByClause();
        break;
      default:
        jj_la1[271] = jj_gen;
        ;
      }
      jj_consume_token(RIGHT_PAREN);
        {if (true) return (QueryTreeNode)nodeFactory.getNode(NodeTypes.WINDOW_DEFINITION_NODE,
                                                  null,
                                                  partitionCols,
                                                  orderCols,
                                                  parserContext);}
      break;
    default:
      jj_la1[272] = jj_gen;
      if (jj_2_75(1)) {
        windowRef = identifier();
         {if (true) return (QueryTreeNode)nodeFactory.getNode(NodeTypes.WINDOW_REFERENCE_NODE,
                                                   windowRef,
                                                   parserContext);}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public PartitionByList partitionByClause() throws ParseException, StandardException {
    PartitionByList partitionByList = (PartitionByList)nodeFactory.getNode(NodeTypes.PARTITION_BY_LIST,
                                                                       parserContext);
    jj_consume_token(PARTITION);
    jj_consume_token(BY);
    windowPartitionColumnReference(partitionByList);
    label_37:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[273] = jj_gen;
        break label_37;
      }
      jj_consume_token(COMMA);
      windowPartitionColumnReference(partitionByList);
    }
        {if (true) return partitionByList;}
    throw new Error("Missing return statement in function");
  }

  final public void windowPartitionColumnReference(PartitionByList partitionByList) throws ParseException, StandardException {
    ColumnReference column;
    String collation = null;
    column = columnReference();
    switch (jj_nt.kind) {
    case COLLATE:
      collation = collateClause();
      break;
    default:
      jj_la1[274] = jj_gen;
      ;
    }
        ValueNode partitionColumn = column;
        if (collation != null)
            partitionColumn = (ValueNode)nodeFactory.getNode(NodeTypes.EXPLICIT_COLLATE_NODE,
                                                             partitionColumn,
                                                             collation,
                                                             parserContext);
        PartitionByColumn partitionBy = (PartitionByColumn)nodeFactory.getNode(NodeTypes.PARTITION_BY_COLUMN,
                                                                               partitionColumn,
                                                                               parserContext);
        partitionByList.add(partitionBy);
  }

  final public ValueNode groupConcatExpression() throws ParseException, StandardException {
    boolean distinct = false;
    ValueNode value;
    OrderByList orderCols = null;
    String sep = ",";
    jj_consume_token(LEFT_PAREN);
    if (jj_2_76(1)) {
      distinct = setQuantifier();
    } else {
      ;
    }
    value = concatColumns();
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[275] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case SEPARATOR:
      jj_consume_token(SEPARATOR);
      sep = getStringLiteral();
      break;
    default:
      jj_la1[276] = jj_gen;
      ;
    }
    jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.GROUP_CONCAT_NODE,
                                              value,
                                              "GroupConcatDefinitionNode",
                                              distinct ? Boolean.TRUE : Boolean.FALSE,
                                              "GROUP_CONCAT",
                                              orderCols,
                                              sep,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode concatColumns() throws ParseException, StandardException {
    QueryTreeNode newNode;
    List<ValueNode> paramList= new ArrayList<ValueNode>();
    colsList(paramList);
        switch(paramList.size())
        {
            case 0:
                {if (true) throw new StandardException("GROUP_CONCAT must have at least one argument");}
            case 1:
                {if (true) return paramList.get(0);}
            default:
                newNode = nodeFactory.getNode(NodeTypes.NEW_INVOCATION_NODE,
                                              "concat",
                                              paramList,
                                              lastTokenDelimitedIdentifier,
                                              null,
                                              null,
                                              parserContext);

                {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                                               newNode,
                                                               parserContext);}
        }
    throw new Error("Missing return statement in function");
  }

  final public void colsList(List<ValueNode> parameterList) throws ParseException, StandardException {
    methodParameter(parameterList);
    label_38:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[277] = jj_gen;
        break label_38;
      }
      jj_consume_token(COMMA);
      methodParameter(parameterList);
    }
  }

  final public ValueNode aggregateExpression(String aggName, String aggClass) throws ParseException, StandardException {
    boolean distinct = false;
    ValueNode value;
    if (jj_2_77(1)) {
      distinct = setQuantifier();
    } else {
      ;
    }
    value = additiveExpression();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.AGGREGATE_NODE,
                                              value,
                                              aggClass,
                                              distinct ? Boolean.TRUE : Boolean.FALSE,
                                              aggName,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode generalAggregate() throws ParseException, StandardException {
    Token aggToken;
    String methodAliasString;
    ValueNode aggExpr;
    ValueNode value;
    aggToken = builtInAggregateType();
    jj_consume_token(LEFT_PAREN);
    aggExpr = aggregateExpression(aggName(aggToken), aggClass(aggToken));
    jj_consume_token(RIGHT_PAREN);
        {if (true) return aggExpr;}
    throw new Error("Missing return statement in function");
  }

/*
** All built in aggregates are pretty similar to user
** defined aggregates, except we know what to map to
** without looking up the class name.
**
** NOTE: COUNT is omitted here because the COUNT aggregate is
** factored into a different rule, to distinguish between
** COUNT(*) and COUNT(<expression>).
*/
  final public Token builtInAggregateType() throws ParseException, StandardException {
    Token retval;
    switch (jj_nt.kind) {
    case MAX:
      retval = jj_consume_token(MAX);
      break;
    case AVG:
      retval = jj_consume_token(AVG);
      break;
    case MIN:
      retval = jj_consume_token(MIN);
      break;
    case SUM:
      retval = jj_consume_token(SUM);
      break;
    default:
      jj_la1[278] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return retval;}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode castSpecification() throws ParseException, StandardException {
    DataTypeDescriptor dts;
    ValueNode treeTop;
    ValueNode value;
    int charType;
    int length = -1;
    jj_consume_token(CAST);
    jj_consume_token(LEFT_PAREN);
    value = castOperand();
    jj_consume_token(AS);
    dts = dataTypeCast();
    jj_consume_token(RIGHT_PAREN);
        treeTop = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                                 value,
                                                 dts,
                                                 parserContext);
        ((CastNode)treeTop).setForExternallyGeneratedCASTnode();

        /* We need to generate a SQL->Java conversion tree above us if
         * the dataTypeCast is a user type.
         */
        if (dts.getTypeId().userType()) {
            treeTop = (ValueNode)nodeFactory.getNode(NodeTypes.JAVA_TO_SQL_VALUE_NODE,
                                                     nodeFactory.getNode(NodeTypes.SQL_TO_JAVA_VALUE_NODE,
                                                                         treeTop,
                                                                         parserContext),
                                                     parserContext);
        }

        {if (true) return treeTop;}
    throw new Error("Missing return statement in function");
  }

/**
 * Next value from a sequence object
 */
  final public ValueNode nextValueExpression() throws ParseException, StandardException {
    ValueNode nextValue;
    TableName sequenceName;
    jj_consume_token(NEXT);
    jj_consume_token(VALUE);
    jj_consume_token(FOR);
    sequenceName = qualifiedName();
        nextValue = (ValueNode)nodeFactory.getNode(NodeTypes.NEXT_SEQUENCE_NODE,
                                                   sequenceName,
                                                   parserContext);

        {if (true) return nextValue;}
    throw new Error("Missing return statement in function");
  }

/**
 * Current value from a sequence object
 */
  final public ValueNode currentValueExpression() throws ParseException, StandardException {
    ValueNode sequenceValue;
    TableName sequenceName;
    if (getToken(1).kind == CURRENT && getToken(2).kind == VALUE && getToken(3).kind == FOR) {

    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(CURRENT);
    jj_consume_token(VALUE);
    jj_consume_token(FOR);
    sequenceName = qualifiedName();
        sequenceValue = (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_SEQUENCE_NODE,
                                                       sequenceName,
                                                       parserContext);

        {if (true) return sequenceValue;}
    throw new Error("Missing return statement in function");
  }

  final public int charOrVarchar() throws ParseException {
    switch (jj_nt.kind) {
    case CHAR:
      jj_consume_token(CHAR);
        {if (true) return Types.CHAR;}
      break;
    case VARCHAR:
      jj_consume_token(VARCHAR);
        {if (true) return Types.VARCHAR;}
      break;
    default:
      jj_la1[279] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode castOperand() throws ParseException, StandardException {
    ValueNode value;
    value = additiveExpression();
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public ParameterNode dynamicParameterSpecification() throws ParseException, StandardException {
    Token tok;
    switch (jj_nt.kind) {
    case QUESTION_MARK:
      jj_consume_token(QUESTION_MARK);
        {if (true) return makeParameterNode(parameterNumber++);}
      break;
    case DOLLAR_N:
      tok = jj_consume_token(DOLLAR_N);
        int n = Integer.parseInt(tok.image.substring(1));
        {if (true) return makeParameterNode(n-1);}
      break;
    default:
      jj_la1[280] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode whereClause(Token beginToken) throws ParseException, StandardException {
    ValueNode value;
    Token endToken;
    value = valueExpression();
        endToken = getToken(0);

        value.setBeginOffset(beginToken.endOffset + 1);
        value.setEndOffset(endToken.endOffset);

        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public GroupByList groupByClause() throws ParseException, StandardException {
    GroupByList groupingCols;
    jj_consume_token(GROUP);
    jj_consume_token(BY);
    if (getToken(1).kind == ROLLUP && getToken(2).kind == LEFT_PAREN) {
      jj_consume_token(ROLLUP);
      jj_consume_token(LEFT_PAREN);
      groupingCols = groupingColumnReferenceList();
      jj_consume_token(RIGHT_PAREN);
        groupingCols.setRollup();
        {if (true) return groupingCols;}
    } else if (jj_2_78(1)) {
      groupingCols = groupingColumnReferenceList();
        {if (true) return groupingCols;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public GroupByList groupingColumnReferenceList() throws ParseException, StandardException {
    GroupByList groupingCols = (GroupByList)nodeFactory.getNode(NodeTypes.GROUP_BY_LIST,
                                                                parserContext);
    groupingColumnReference(groupingCols);
    label_39:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[281] = jj_gen;
        break label_39;
      }
      jj_consume_token(COMMA);
      groupingColumnReference(groupingCols);
    }
        {if (true) return groupingCols;}
    throw new Error("Missing return statement in function");
  }

  final public void groupingColumnReference(GroupByList groupingCols) throws ParseException, StandardException {
    ValueNode columnExpression;
    columnExpression = additiveExpression();
        /* Aggregates not allowed in group by */
        HasNodeVisitor visitor = new HasNodeVisitor(AggregateNode.class);
        columnExpression.accept(visitor);
        if (visitor.hasNode()) {
            {if (true) throw new StandardException("Aggregate values not allowed in GROUP BY");}
        }

        if (columnExpression.isParameterNode()) {
            {if (true) throw new StandardException("Parameters not allowed in GROUP BY");}
        }
        groupingCols.addGroupByColumn(
            (GroupByColumn)nodeFactory.getNode(NodeTypes.GROUP_BY_COLUMN,
                                               columnExpression,
                                               parserContext));
  }

  final public ValueNode havingClause() throws ParseException, StandardException {
    ValueNode value;
    jj_consume_token(HAVING);
    value = valueExpression();
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public WindowList windowClause() throws ParseException, StandardException {
    WindowList windows = new WindowList();
    windows.setParserContext(parserContext);
    jj_consume_token(WINDOW);
    windows = windowDefinition(windows);
    label_40:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[282] = jj_gen;
        break label_40;
      }
      jj_consume_token(COMMA);
      windows = windowDefinition(windows);
    }
        {if (true) return windows;}
    throw new Error("Missing return statement in function");
  }

  final public WindowList windowDefinition(WindowList wl) throws ParseException, StandardException {
    String windowName;
    PartitionByList partitionCols = null;
    OrderByList orderCols = null;
    windowName = identifier();
    jj_consume_token(AS);
    jj_consume_token(LEFT_PAREN);
    switch (jj_nt.kind) {
    case PARTITION:
      partitionCols = partitionByClause();
      break;
    default:
      jj_la1[283] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[284] = jj_gen;
      ;
    }
    jj_consume_token(RIGHT_PAREN);
        wl.addWindow(
            (WindowDefinitionNode)nodeFactory.getNode(NodeTypes.WINDOW_DEFINITION_NODE,
                                                      windowName,
                                                      partitionCols,
                                                      orderCols,
                                                      parserContext));

        {if (true) return wl;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode schemaDefinition() throws ParseException, StandardException {
    String schemaName = null;
    String authName = null;
    TableName characterSet = null;
    TableName collation = null;
    CharacterTypeAttributes defaultCharacterAttributes = null;
    ExistenceCheck cond = ExistenceCheck.NO_CONDITION;
    jj_consume_token(SCHEMA);
    cond = createCondition();
    if (jj_2_79(1)) {
      schemaName = identifier();
      switch (jj_nt.kind) {
      case AUTHORIZATION:
        jj_consume_token(AUTHORIZATION);
        authName = identifier();
        break;
      default:
        jj_la1[285] = jj_gen;
        ;
      }
    } else {
      switch (jj_nt.kind) {
      case AUTHORIZATION:
        jj_consume_token(AUTHORIZATION);
        authName = identifier();
            schemaName = authName;
        break;
      default:
        jj_la1[286] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    if (getToken(2).kind == CHARACTER) {
      jj_consume_token(_DEFAULT);
      jj_consume_token(CHARACTER);
      jj_consume_token(SET);
      characterSet = qualifiedName();
        defaultCharacterAttributes = CharacterTypeAttributes.forCharacterSet(characterSet.toString());
    } else {
      ;
    }
    if (getToken(2).kind == COLLATION) {
      jj_consume_token(_DEFAULT);
      jj_consume_token(COLLATION);
      collation = qualifiedName();
        defaultCharacterAttributes = CharacterTypeAttributes.forCollation(defaultCharacterAttributes, collation.toString());
    } else {
      ;
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_SCHEMA_NODE,
                                                  schemaName,
                                                  authName,
                                                  defaultCharacterAttributes,
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode roleDefinition() throws ParseException, StandardException {
    String roleName = null;
    jj_consume_token(ROLE);
    roleName = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_ROLE_NODE,
                                                  roleName,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode sequenceDefinition() throws ParseException, StandardException {
    TableName qualifiedSequenceName = null;
    DataTypeDescriptor dtd = null;
    Long initialValue = null;
    Long stepValue = null;
    Long maxValue = null;
    Long minValue = null;
    Boolean cycle = Boolean.FALSE;
    Object[] optionalClauses = new Object[SEQUENCE_NCLAUSES];
    jj_consume_token(SEQUENCE);
    qualifiedSequenceName = qualifiedName();
    label_41:
    while (true) {
      if (jj_2_80(1)) {
        ;
      } else {
        break label_41;
      }
      sequenceGeneratorOption(optionalClauses);
    }
        if (optionalClauses[SEQUENCE_DATA_TYPE] != null) {
            dtd = (DataTypeDescriptor)optionalClauses[SEQUENCE_DATA_TYPE];
        }

        if (optionalClauses[SEQUENCE_START_WITH] != null) {
            initialValue = (Long)optionalClauses[SEQUENCE_START_WITH];
        }
        if (optionalClauses[SEQUENCE_INCREMENT_BY] != null) {
            stepValue = (Long)optionalClauses[SEQUENCE_INCREMENT_BY];
        }
        if ((optionalClauses[SEQUENCE_MAX_VALUE] != null) &&
                (!(optionalClauses[SEQUENCE_MAX_VALUE] instanceof Boolean))) {
            maxValue = (Long)optionalClauses[SEQUENCE_MAX_VALUE];
        }
        if ((optionalClauses[SEQUENCE_MIN_VALUE] != null) &&
                (!(optionalClauses[SEQUENCE_MIN_VALUE] instanceof Boolean))) {
            minValue = (Long)optionalClauses[SEQUENCE_MIN_VALUE];
        }

        if (optionalClauses[SEQUENCE_CYCLE] != null) {
            cycle = (Boolean)optionalClauses[SEQUENCE_CYCLE];
        }

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_SEQUENCE_NODE,
                                                  qualifiedSequenceName,
                                                  dtd,
                                                  initialValue,
                                                  stepValue,
                                                  maxValue,
                                                  minValue,
                                                  cycle,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void sequenceGeneratorOption(Object[] optionalClauses) throws ParseException, StandardException {
    Object option = null;
    int optionIndex = -1;
    Boolean[] cycleOption = new Boolean[1];
    String optionName = null;
    Token optionToken = null;
    switch (jj_nt.kind) {
    case AS:
      optionToken = jj_consume_token(AS);
      option = exactIntegerType();
        optionIndex = SEQUENCE_DATA_TYPE;
      break;
    case START:
      optionToken = jj_consume_token(START);
      jj_consume_token(WITH);
      option = exactIntegerObject();
        optionIndex = SEQUENCE_START_WITH;
      break;
    case INCREMENT:
      optionToken = jj_consume_token(INCREMENT);
      jj_consume_token(BY);
      option = exactIntegerObject();
        optionIndex = SEQUENCE_INCREMENT_BY;
      break;
    default:
      jj_la1[289] = jj_gen;
      if (jj_2_81(1)) {
        switch (jj_nt.kind) {
        case MAXVALUE:
          optionToken = jj_consume_token(MAXVALUE);
          option = exactIntegerObject();
          break;
        default:
          jj_la1[287] = jj_gen;
          if (getToken(2).kind == MAXVALUE) {
            jj_consume_token(NO);
            optionToken = jj_consume_token(MAXVALUE);
                                            option = Boolean.FALSE;
          } else {
            jj_consume_token(-1);
            throw new ParseException();
          }
        }
        optionIndex = SEQUENCE_MAX_VALUE;
      } else if (jj_2_82(1)) {
        switch (jj_nt.kind) {
        case MINVALUE:
          optionToken = jj_consume_token(MINVALUE);
          option = exactIntegerObject();
          break;
        default:
          jj_la1[288] = jj_gen;
          if (getToken(2).kind == MINVALUE) {
            jj_consume_token(NO);
            optionToken = jj_consume_token(MINVALUE);
                                            option = Boolean.FALSE;
          } else {
            jj_consume_token(-1);
            throw new ParseException();
          }
        }
        optionIndex = SEQUENCE_MIN_VALUE;
      } else {
        switch (jj_nt.kind) {
        case NO:
        case CYCLE:
          optionToken = cycleClause(cycleOption);
        option = cycleOption[0];
        optionIndex = SEQUENCE_CYCLE;
          break;
        default:
          jj_la1[290] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
        if (optionIndex != -1) {
            if (optionalClauses[optionIndex] != null) {
                {if (true) throw new StandardException("Repeated SEQUENCE clause");}
            }
            optionalClauses[ optionIndex ] = option;
        }
  }

  final public Token cycleClause(Boolean[] cycleOption) throws ParseException, StandardException {
    Token token = null;
    switch (jj_nt.kind) {
    case CYCLE:
      token = jj_consume_token(CYCLE);
        cycleOption[0] = Boolean.TRUE;
        {if (true) return token;}
      break;
    case NO:
      jj_consume_token(NO);
      token = jj_consume_token(CYCLE);
        cycleOption[0] = Boolean.FALSE;
        {if (true) return token;}
      break;
    default:
      jj_la1[291] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public Long exactIntegerObject() throws ParseException, StandardException {
    long exactNumeric;
    exactNumeric = exactNumber();
        {if (true) return new Long(exactNumeric);}
    throw new Error("Missing return statement in function");
  }

  final public Long stepValue() throws ParseException, StandardException {
    long stepValue;
    jj_consume_token(INCREMENT);
    jj_consume_token(BY);
    stepValue = exactNumber();
        {if (true) return new Long(stepValue);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropSequenceStatement() throws ParseException, StandardException {
    TableName sequenceName;
    ExistenceCheck cond;
    jj_consume_token(SEQUENCE);
    cond = dropCondition();
    sequenceName = qualifiedName();
    jj_consume_token(RESTRICT);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_SEQUENCE_NODE,
                                                  sequenceName,
                                                  new Integer(StatementType.DROP_RESTRICT),
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode tableDefinition() throws ParseException, StandardException {
    TableName tableName;
    TableElementList tableElementList;
    Properties properties = null;
    char lockGranularity = CreateTableNode.DEFAULT_LOCK_GRANULARITY;
    ResultColumnList resultColumns = null;
    ResultSetNode queryExpression;
    boolean withData = true;
    ExistenceCheck cond;
    jj_consume_token(TABLE);
    cond = createCondition();
    tableName = qualifiedName();
    if (getToken(1).kind == LEFT_PAREN &&
                         getToken(3).kind != COMMA && getToken(3).kind != RIGHT_PAREN) {
      tableElementList = tableElementList();
      switch (jj_nt.kind) {
      case DERBYDASHPROPERTIES:
        properties = propertyList(false);
        jj_consume_token(CHECK_PROPERTIES);
        break;
      default:
        jj_la1[292] = jj_gen;
        ;
      }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_TABLE_NODE,
                                                  tableName,
                                                  tableElementList,
                                                  properties,
                                                  new Character(lockGranularity),
                                                  cond,
                                                  parserContext);}
    } else {
      switch (jj_nt.kind) {
      case AS:
      case LEFT_PAREN:
        switch (jj_nt.kind) {
        case LEFT_PAREN:
          jj_consume_token(LEFT_PAREN);
          resultColumns = tableColumnList();
          jj_consume_token(RIGHT_PAREN);
          break;
        default:
          jj_la1[293] = jj_gen;
          ;
        }
        jj_consume_token(AS);
        queryExpression = queryExpression(null, NO_SET_OP);
        jj_consume_token(WITH);
        switch (jj_nt.kind) {
        case NO:
          jj_consume_token(NO);
                        withData = false;
          break;
        default:
          jj_la1[294] = jj_gen;
          ;
        }
        jj_consume_token(DATA);
        /* Parameters not allowed in create table */
        HasNodeVisitor visitor =
            new HasNodeVisitor(ParameterNode.class);
        queryExpression.accept(visitor);
        if (visitor.hasNode()) {
            {if (true) throw new StandardException("Parameters not allowed in CREATE TABLE");}
        }

        StatementNode result = (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_TABLE_NODE,
                                                                  tableName,
                                                                  resultColumns,
                                                                  queryExpression,
                                                                  cond,
                                                                  parserContext);
        if (withData) {
            ((CreateTableNode)result).markWithData();
        }
        {if (true) return result;}
        break;
      default:
        jj_la1[295] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList tableColumnList() throws ParseException, StandardException {
    ResultColumnList resultColumns = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                           parserContext);
    columnNameList(resultColumns);
        {if (true) return resultColumns;}
    throw new Error("Missing return statement in function");
  }

/*
 * This method is called when a comment starting with --derby-properties is found.
 * Such a comment is a special directive to Derby and allows a sql to pass optimizer
 * overrides. Derby looks for propertyName=value [,propertyName=value]* after
 * --derby-properties and returns these properties in a Properties object as a return 
 * value of this method.
 * The param propertiesUseAllowed true indicates that users are allowed to 
 * specify optimizer overrides in the given context. 
 * False means optimizer overrides in the given context are allowed internally 
 * only eg impl/load/import.java specifies property insertMode=replace/bulkInsert
 * in the insert statement. This same property will not be acceptable from an 
 * insert statement from a user sql.
 */
  final public Properties propertyList(boolean propertiesUseAllowed) throws ParseException, StandardException {
    Properties properties = new Properties();
    StringTokenizer commaSeparatedProperties;
    StringTokenizer equalOperatorSeparatedProperty;
    jj_consume_token(DERBYDASHPROPERTIES);
        //first use StringTokenizer to get tokens which are delimited by ,s
        commaSeparatedProperties = new StringTokenizer(getToken(1).image, ",");
        while (commaSeparatedProperties.hasMoreTokens()) {
            //Now verify that tokens delimited by ,s follow propertyName=value pattern
            String currentProperty = commaSeparatedProperties.nextToken();
            equalOperatorSeparatedProperty = new StringTokenizer(currentProperty,"=", true);
            if (equalOperatorSeparatedProperty.countTokens() != 3)
                {if (true) throw new StandardException("Invalid properties syntax");}
            else {
                String key = equalOperatorSeparatedProperty.nextToken().trim();
                if (!equalOperatorSeparatedProperty.nextToken().equals("="))
                    {if (true) throw new StandardException("Invalid properties syntax");}
                String value = equalOperatorSeparatedProperty.nextToken().trim();
                parserContext.checkStringLiteralLengthLimit(value);
                /* Trim off the leading and trailing ', and compress all '' to ' */
                if (value.startsWith("'") && value.endsWith("'"))
                    value = trimAndCompressQuotes(value, SINGLEQUOTES, false);
                /* Trim off the leading and trailing ", and compress all "" to " */
                else if (value.startsWith("\u005c"") && value.endsWith("\u005c""))
                    value = trimAndCompressQuotes(value, DOUBLEQUOTES, false);
                else
                    value = value.toUpperCase();
                // Do not allow user to specify multiple values for the same key
                if (properties.put(key, value) != null) {
                    {if (true) throw new StandardException("Duplicate property: " + key);}
                }
            }
        }
        //if this property override is supported in internal mode only, then do that verification here.
        if (!propertiesUseAllowed) {
            // TODO: What to do?
        }
        {if (true) return properties;}
    throw new Error("Missing return statement in function");
  }

  final public char DB2lockGranularityClause() throws ParseException, StandardException {
    char lockGranularity;
    jj_consume_token(LOCKSIZE);
    lockGranularity = lockGranularity();
        {if (true) return lockGranularity;}
    throw new Error("Missing return statement in function");
  }

  final public char lockGranularity() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case TABLE:
      jj_consume_token(TABLE);
        {if (true) return CreateTableNode.TABLE_LOCK_GRANULARITY;}
      break;
    case ROW:
      jj_consume_token(ROW);
        {if (true) return CreateTableNode.ROW_LOCK_GRANULARITY;}
      break;
    default:
      jj_la1[296] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode indexDefinition() throws ParseException, StandardException {
    Boolean unique = Boolean.FALSE;
    Properties properties = null;
    TableName indexName;
    TableName tableName;
    IndexColumnList indexColumnList = null;
    boolean groupFormat = hasFeature(SQLParserFeature.GROUPING);
    JoinNode.JoinType joinType = null;
    ExistenceCheck cond;
    StorageLocation location = null;
    switch (jj_nt.kind) {
    case UNIQUE:
      unique = unique();
      break;
    default:
      jj_la1[297] = jj_gen;
      ;
    }
    jj_consume_token(INDEX);
    cond = createCondition();
    indexName = qualifiedName();
    jj_consume_token(ON);
    tableName = qualifiedName();
    jj_consume_token(LEFT_PAREN);
    if (groupFormat) {
      groupIndexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else if (!groupFormat) {
      indexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(RIGHT_PAREN);
    if (groupFormat && getToken(1).kind == USING) {
      jj_consume_token(USING);
      joinType = joinType();
      jj_consume_token(JOIN);
    } else {
      ;
    }
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      properties = propertyList(false);
      jj_consume_token(CHECK_PROPERTIES);
      break;
    default:
      jj_la1[298] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case AS:
      jj_consume_token(AS);
      location = getLocation();
      break;
    default:
      jj_la1[299] = jj_gen;
      ;
    }
        /* User allowed to specify schema name on table and index.
         * If no schema name specified for index, then it "inherits" 
         * its schema name from the table.
         * If index has a schema name and table does not, then
         * table "inherits" its schema name from the index.
         * If schema names are specified for both objects, then the
         * schema names must be the same.
         */
        if (indexName.getSchemaName() == null) {
            indexName.setSchemaName(tableName.getSchemaName());
        }
        else if (tableName.getSchemaName() == null) {
            tableName.setSchemaName(indexName.getSchemaName());
        }
        else {
            /* schema name specified for both */
            if (! (indexName.getSchemaName().equals(
                        tableName.getSchemaName()))) {
                {if (true) throw new StandardException("Specified schemas do not match: " +
                                            indexName + ", " + tableName);}
            }
        }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_INDEX_NODE,
                                                  unique,
                                                  null,
                                                  indexName,
                                                  tableName,
                                                  indexColumnList,
                                                  joinType,
                                                  properties,
                                                  cond,
                                                  location,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public Boolean unique() throws ParseException, StandardException {
    jj_consume_token(UNIQUE);
        {if (true) return Boolean.TRUE;}
    throw new Error("Missing return statement in function");
  }

/**
    CREATE PROCEDURE

    procedureElements contains the description of the procedure.
    (CREATE FUNCTIONS shares this lyout), see functionDefinition

    0 - Object[] 3 element array for parameters
    1 - TableName - specific name
    2 - Integer - dynamic result set count
    3 - String language
    4 - String external name
    5 - Short parameter style
    6 - Short - SQL allowed.
    7 - Boolean - CALLED ON NULL INPUT (always TRUE for procedures)
    8 - DataTypeDescriptor - return type (always NULL for procedures)
    9 - Boolean - definers rights
   10 - String - inline definition
*/
  final public StatementNode procedureDefinition(Boolean createOrReplace) throws ParseException, StandardException {
    TableName procedureName;
    Object[] procedureElements = new Object[CreateAliasNode.ROUTINE_ELEMENT_COUNT];
    jj_consume_token(PROCEDURE);
    procedureName = qualifiedName();
    procedureElements[0] = procedureParameterList();
    label_42:
    while (true) {
      routineElement(true, false, procedureElements);
      switch (jj_nt.kind) {
      case AS:
      case EXTERNAL:
      case NO:
      case NOT:
      case CALLED:
      case CONTAINS:
      case DETERMINISTIC:
      case DYNAMIC:
      case LANGUAGE:
      case MODIFIES:
      case RETURNS:
      case PARAMETER:
      case READS:
      case RESULT:
      case SPECIFIC:
        ;
        break;
      default:
        jj_la1[300] = jj_gen;
        break label_42;
      }
    }
        checkRequiredRoutineClause(procedureElements);

        {if (true) return getCreateAliasNode(procedureName,
                                  (String)procedureElements[CreateAliasNode.EXTERNAL_NAME],
                                  procedureElements,
                                  AliasInfo.Type.PROCEDURE,
                                  createOrReplace);}
    throw new Error("Missing return statement in function");
  }

  final public void routineElement(boolean isProcedure, boolean isTableFunction, Object[] routineElements) throws ParseException, StandardException {
    int drs;
    int clausePosition = -1;
    Object clauseValue = null;
    switch (jj_nt.kind) {
    case SPECIFIC:
      jj_consume_token(SPECIFIC);
      clauseValue = qualifiedName();
        clausePosition = CreateAliasNode.TABLE_NAME;
        {if (true) throw new StandardException("Not implemented SPECIFIC identifier");}
      break;
    case DYNAMIC:
    case RESULT:
      switch (jj_nt.kind) {
      case DYNAMIC:
        jj_consume_token(DYNAMIC);
        break;
      default:
        jj_la1[301] = jj_gen;
        ;
      }
      jj_consume_token(RESULT);
      jj_consume_token(SETS);
      drs = uint_value();
        if (!isProcedure)
            {if (true) throw new StandardException("Only allowed on procedure: RESULT SETS");}
        clauseValue = drs;
        clausePosition = CreateAliasNode.DYNAMIC_RESULT_SET_COUNT;
      break;
    case LANGUAGE:
      jj_consume_token(LANGUAGE);
      clauseValue = routineLanguage();
                                                 clausePosition = CreateAliasNode.LANGUAGE;
      break;
    case DETERMINISTIC:
      jj_consume_token(DETERMINISTIC);
        clauseValue = Boolean.TRUE;
        clausePosition = CreateAliasNode.DETERMINISTIC;
      break;
    case NOT:
      jj_consume_token(NOT);
      jj_consume_token(DETERMINISTIC);
        clauseValue = Boolean.FALSE;
        clausePosition = CreateAliasNode.DETERMINISTIC;
      break;
    case EXTERNAL:
      jj_consume_token(EXTERNAL);
      switch (jj_nt.kind) {
      case NAME:
        jj_consume_token(NAME);
        clauseValue = string();
      clausePosition = CreateAliasNode.EXTERNAL_NAME;
        break;
      case SECURITY:
        jj_consume_token(SECURITY);
        clauseValue = new Boolean(routineSecurityClause());
        clausePosition = CreateAliasNode.ROUTINE_SECURITY_DEFINER;
        break;
      default:
        jj_la1[302] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    case PARAMETER:
      jj_consume_token(PARAMETER);
      jj_consume_token(STYLE);
      clauseValue = parameterStyle();
        clausePosition = CreateAliasNode.PARAMETER_STYLE;
      break;
    case AS:
      jj_consume_token(AS);
      clauseValue = string();
        clausePosition = CreateAliasNode.INLINE_DEFINITION;
      break;
    case NO:
      jj_consume_token(NO);
      jj_consume_token(SQL);
        clauseValue = RoutineAliasInfo.SQLAllowed.NO_SQL;
        clausePosition = CreateAliasNode.SQL_CONTROL;
      break;
    case CONTAINS:
      jj_consume_token(CONTAINS);
      jj_consume_token(SQL);
        clauseValue = RoutineAliasInfo.SQLAllowed.CONTAINS_SQL;
        clausePosition = CreateAliasNode.SQL_CONTROL;
      break;
    case READS:
      jj_consume_token(READS);
      jj_consume_token(SQL);
      jj_consume_token(DATA);
        clauseValue = RoutineAliasInfo.SQLAllowed.READS_SQL_DATA;
        clausePosition = CreateAliasNode.SQL_CONTROL;
      break;
    case MODIFIES:
      jj_consume_token(MODIFIES);
      jj_consume_token(SQL);
      jj_consume_token(DATA);
        if (!isProcedure)
            {if (true) throw new StandardException("Only allowed on procedure: MODIFIES SQL DATA");}
        clauseValue = RoutineAliasInfo.SQLAllowed.MODIFIES_SQL_DATA;
        clausePosition = CreateAliasNode.SQL_CONTROL;
      break;
    case CALLED:
    case RETURNS:
      clauseValue = calledOnNullInput(isProcedure);
                                                   clausePosition = CreateAliasNode.NULL_ON_NULL_INPUT;
      break;
    default:
      jj_la1[303] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        if (clausePosition != -1) {
            // check for repeated clause
            if (routineElements[clausePosition] != null) {

                String which = ROUTINE_CLAUSE_NAMES[clausePosition];
                {if (true) throw new StandardException("Repeated " + which + " clause");}
            }

            routineElements[clausePosition] = clauseValue;
        }
  }

  final public Boolean calledOnNullInput(boolean isProcedure) throws ParseException, StandardException {
    Boolean calledOnNull;
    switch (jj_nt.kind) {
    case CALLED:
      jj_consume_token(CALLED);
        calledOnNull = Boolean.TRUE;
      break;
    case RETURNS:
      jj_consume_token(RETURNS);
      jj_consume_token(NULL);
        if (isProcedure)
            {if (true) throw new StandardException("Not allowed for procedure RETURNS NULL ON NULL INPUT");}
        calledOnNull = Boolean.FALSE;
      break;
    default:
      jj_la1[304] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(ON);
    jj_consume_token(NULL);
    jj_consume_token(INPUT);
        {if (true) return calledOnNull;}
    throw new Error("Missing return statement in function");
  }

  final public boolean routineSecurityClause() throws ParseException, StandardException {
    boolean result = false;
    switch (jj_nt.kind) {
    case INVOKER:
      jj_consume_token(INVOKER);
        result = false;
      break;
    case DEFINER:
      jj_consume_token(DEFINER);
        result = true;
      break;
    default:
      jj_la1[305] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return result;}
    throw new Error("Missing return statement in function");
  }

  final public String routineLanguage() throws ParseException, StandardException {
    String ident;
    Token token;
    if (jj_2_83(1)) {
      ident = identifier();
    } else {
      switch (jj_nt.kind) {
      case SQL:
        token = jj_consume_token(SQL);
        ident = token.image;
        break;
      default:
        jj_la1[306] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        {if (true) return ident;}
    throw new Error("Missing return statement in function");
  }

  final public String parameterStyle() throws ParseException, StandardException {
    String ident;
    Token token;
    ident = identifier();
        {if (true) return ident;}
    throw new Error("Missing return statement in function");
  }

  final public List[] procedureParameterList() throws ParseException, StandardException {
    // TODO: Need some struct or something
    List[] list = new List[3];
    list[0] = new ArrayList<String>(); // name
    list[1] = new ArrayList<DataTypeDescriptor>(); // type
    list[2] = new ArrayList<Integer>();
    jj_consume_token(LEFT_PAREN);
    if (jj_2_84(1)) {
      procedureParameterDefinition(list);
      label_43:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[307] = jj_gen;
          break label_43;
        }
        jj_consume_token(COMMA);
        procedureParameterDefinition(list);
      }
    } else {
      ;
    }
    jj_consume_token(RIGHT_PAREN);
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void procedureParameterDefinition(List[] list) throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    String parameterName = null;
    Integer inout;
    inout = inoutParameter();
    if (dataTypeCheck(2)) {
      parameterName = identifier();
    } else {
      ;
    }
    typeDescriptor = dataTypeDDL();
        list[0].add(parameterName);
        list[1].add(typeDescriptor);
        list[2].add(inout);
  }

  final public Integer inoutParameter() throws ParseException {
    int mode = ParameterMetaData.parameterModeIn;
    switch (jj_nt.kind) {
    case IN:
    case INOUT:
    case OUT:
      switch (jj_nt.kind) {
      case IN:
        jj_consume_token(IN);

        break;
      case OUT:
        jj_consume_token(OUT);
                mode = ParameterMetaData.parameterModeOut;
        break;
      case INOUT:
        jj_consume_token(INOUT);
                  mode = ParameterMetaData.parameterModeInOut;
        break;
      default:
        jj_la1[308] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[309] = jj_gen;
      ;
    }
        {if (true) return mode;}
    throw new Error("Missing return statement in function");
  }

/**
    CREATE FUNCTION

    functionElements contains the description of the function.

    0 - Object[] 3 element array for parameters
    1 - TableName - specific name
    2 - Integer - dynamic result set count - always 0
    3 - String language
    4 - String external name
    5 - Short parameter style
    6 - Short - SQL allowed.
    7 - Boolean - CALLED ON NULL INPUT
    8 - DataTypeDescriptor - return type
    9 - Boolean - definers rights
   10 - String - inline definition
*/
  final public StatementNode functionDefinition(Boolean createOrReplace) throws ParseException, StandardException {
    TableName functionName;
    DataTypeDescriptor returnType;
    Object[] functionElements = new Object[CreateAliasNode.ROUTINE_ELEMENT_COUNT];
    jj_consume_token(FUNCTION);
    functionName = qualifiedName();
    functionElements[0] = functionParameterList();
    jj_consume_token(RETURNS);
    returnType = functionReturnDataType();
    label_44:
    while (true) {
      routineElement(false, returnType.isRowMultiSet(), functionElements);
      switch (jj_nt.kind) {
      case AS:
      case EXTERNAL:
      case NO:
      case NOT:
      case CALLED:
      case CONTAINS:
      case DETERMINISTIC:
      case DYNAMIC:
      case LANGUAGE:
      case MODIFIES:
      case RETURNS:
      case PARAMETER:
      case READS:
      case RESULT:
      case SPECIFIC:
        ;
        break;
      default:
        jj_la1[310] = jj_gen;
        break label_44;
      }
    }
        functionElements[CreateAliasNode.RETURN_TYPE] = returnType;
        checkRequiredRoutineClause(functionElements);

        {if (true) return getCreateAliasNode(functionName,
                                  (String)functionElements[CreateAliasNode.EXTERNAL_NAME],
                                  functionElements,
                                  AliasInfo.Type.FUNCTION,
                                  createOrReplace);}
    throw new Error("Missing return statement in function");
  }

  final public Object[] functionParameterList() throws ParseException, StandardException {
    List[] list = new List[3];
    list[0] = new ArrayList<String>(); // name
    list[1] = new ArrayList<DataTypeDescriptor>(); // type
    list[2] = new ArrayList<Integer>();
    jj_consume_token(LEFT_PAREN);
    if (jj_2_85(1)) {
      functionParameterDefinition(list);
      label_45:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[311] = jj_gen;
          break label_45;
        }
        jj_consume_token(COMMA);
        functionParameterDefinition(list);
      }
    } else {
      ;
    }
    jj_consume_token(RIGHT_PAREN);
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void functionParameterDefinition(List[] list) throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    String parameterName = "";
    if (dataTypeCheck(2)) {
      parameterName = identifier();
    } else {
      ;
    }
    typeDescriptor = dataTypeDDL();
        list[0].add(parameterName);
        list[1].add(typeDescriptor);
        list[2].add(ParameterMetaData.parameterModeIn);
  }

  final public DataTypeDescriptor functionReturnDataType() throws ParseException, StandardException {
    DataTypeDescriptor typeDescriptor;
    if (jj_2_86(1)) {
      typeDescriptor = catalogType();
    } else {
      switch (jj_nt.kind) {
      case TABLE:
        typeDescriptor = functionTableType();
        break;
      default:
        jj_la1[312] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
        {if (true) return typeDescriptor;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor functionTableType() throws ParseException, StandardException {
    List<String> names = new ArrayList<String>();
    List<DataTypeDescriptor> types = new ArrayList<DataTypeDescriptor>();
    String[] nameArray;
    DataTypeDescriptor[] typeArray;
    int columnCount;
    jj_consume_token(TABLE);
    jj_consume_token(LEFT_PAREN);
    functionTableReturnColumn(names, types);
    label_46:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[313] = jj_gen;
        break label_46;
      }
      jj_consume_token(COMMA);
      functionTableReturnColumn(names, types);
    }
    jj_consume_token(RIGHT_PAREN);
        columnCount = names.size();
        nameArray = new String[ columnCount ];
        names.toArray(nameArray);
        typeArray = new DataTypeDescriptor[columnCount];
        types.toArray(typeArray);
        {if (true) return DataTypeDescriptor.getRowMultiSet(nameArray, typeArray);}
    throw new Error("Missing return statement in function");
  }

  final public void functionTableReturnColumn(List<String> names, List<DataTypeDescriptor> types) throws ParseException, StandardException {
    String name;
    DataTypeDescriptor typeDescriptor;
    name = identifier();
    typeDescriptor = dataTypeDDL();
        names.add(name);
        types.add(typeDescriptor);
  }

/**
    CREATE TYPE
*/
  final public StatementNode udtDefinition(Boolean createOrReplace) throws ParseException, StandardException {
    TableName udtName;
    String externalName;
    jj_consume_token(TYPE);
    udtName = qualifiedName();
    jj_consume_token(EXTERNAL);
    jj_consume_token(NAME);
    externalName = string();
    jj_consume_token(LANGUAGE);
    jj_consume_token(JAVA);
        {if (true) return getCreateAliasNode(udtName,
                                  externalName,
                                  null,
                                  AliasInfo.Type.UDT,
                                  createOrReplace);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode viewDefinition(Token beginToken) throws ParseException, StandardException {
    int checkOptionType;
    ResultColumnList resultColumns = null;
    ResultSetNode queryExpression;
    TableName tableName;
    Token checkTok = null;
    Token endToken;
    OrderByList orderCols = null;
    ValueNode[] offsetAndFetchFirst = new ValueNode[2];
    ExistenceCheck cond;
    jj_consume_token(VIEW);
    cond = createCondition();
    tableName = qualifiedName();
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      resultColumns = viewColumnList();
      jj_consume_token(RIGHT_PAREN);
      break;
    default:
      jj_la1[314] = jj_gen;
      ;
    }
    jj_consume_token(AS);
    queryExpression = queryExpression(null, NO_SET_OP);
    switch (jj_nt.kind) {
    case ORDER:
      orderCols = orderByClause();
      break;
    default:
      jj_la1[315] = jj_gen;
      ;
    }
    label_47:
    while (true) {
      switch (jj_nt.kind) {
      case FETCH:
      case OFFSET:
      case LIMIT:
        ;
        break;
      default:
        jj_la1[316] = jj_gen;
        break label_47;
      }
      offsetOrFetchFirstClause(offsetAndFetchFirst);
    }
        checkOptionType = CreateViewNode.NO_CHECK_OPTION;
        endToken = getToken(0);

        /* Parameters not allowed in create view */
        HasNodeVisitor visitor = new HasNodeVisitor(ParameterNode.class);
        queryExpression.accept(visitor);
        if (visitor.hasNode()) {
            {if (true) throw new StandardException("Parameters not allowed in CREATE VIEW");}
        }

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_VIEW_NODE,
                                                  tableName,
                                                  resultColumns,
                                                  queryExpression,
                                                  checkOptionType,
                                                  sliceSQLText(beginToken.beginOffset, endToken.endOffset, false),
                                                  orderCols,
                                                  offsetAndFetchFirst[0],
                                                  offsetAndFetchFirst[1],
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList viewColumnList() throws ParseException, StandardException {
    ResultColumnList resultColumns = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                           parserContext);
    columnNameList(resultColumns);
        {if (true) return resultColumns;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode triggerDefinition() throws ParseException, StandardException {
    Boolean isBefore;
    Boolean isRow = Boolean.FALSE;  // STATEMENT implicit by default
    TableName tableName;
    TableName triggerName;
    Token[] tokenHolder = new Token[1];
    Token beginToken;
    Token checkTok = null;
    Token endToken;
    int actionBegin;
    int actionEnd;
    int triggerEvent;
    QueryTreeNode actionNode;
    ResultColumnList triggerColumns = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                            parserContext);
    List<TriggerReferencingStruct> refClause = null;
    jj_consume_token(TRIGGER);
    triggerName = qualifiedName();
    isBefore = beforeOrAfter();
    triggerEvent = triggerEvent(triggerColumns);
    jj_consume_token(ON);
    tableName = qualifiedName();
    switch (jj_nt.kind) {
    case REFERENCING:
      refClause = triggerReferencingClause();
      break;
    default:
      jj_la1[317] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case FOR:
      jj_consume_token(FOR);
      jj_consume_token(EACH);
      isRow = rowOrStatement();
      break;
    default:
      jj_la1[318] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case MODE:
      jj_consume_token(MODE);
      jj_consume_token(DB2SQL);
      break;
    default:
      jj_la1[319] = jj_gen;
      ;
    }
    actionNode = proceduralStatement(tokenHolder);
        actionEnd = getToken(0).endOffset;
        actionBegin = tokenHolder[0].beginOffset;

        // No DML in action node for BEFORE triggers.
        if (isBefore.booleanValue() && (actionNode instanceof DMLModStatementNode)) {
            {if (true) throw new StandardException("DML not allowed in BEFORE trigger");}
        }

        // No params in trigger action.
        HasNodeVisitor visitor = new HasNodeVisitor(ParameterNode.class);
        actionNode.accept(visitor);
        if (visitor.hasNode()) {
            {if (true) throw new StandardException("Parameters not allowed in trigger action");}
        }

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CREATE_TRIGGER_NODE,
                                                  triggerName,
                                                  tableName,
                                                  triggerEvent,
                                                  triggerColumns,
                                                  isBefore,
                                                  isRow,
                                                  Boolean.TRUE,                   // enabled
                                                  refClause,          // referencing clause
                                                  null,// when clause node
                                                  null,           // when clause text
                                                  0,
                                                  // when clause begin offset
                                                  actionNode,
                                                  sliceSQLText(actionBegin, actionEnd, false),
                                                  actionBegin,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode synonymDefinition(Boolean createOrReplace) throws ParseException, StandardException {
    TableName synonymName;
    TableName targetName;
    jj_consume_token(SYNONYM);
    synonymName = qualifiedName();
    jj_consume_token(FOR);
    targetName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getCreateAliasNode(synonymName,
                                                             targetName,
                                                             null,
                                                             AliasInfo.Type.SYNONYM,
                                                             createOrReplace,
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public Boolean beforeOrAfter() throws ParseException {
    switch (jj_nt.kind) {
    case NO:
      jj_consume_token(NO);
      jj_consume_token(CASCADE);
      jj_consume_token(BEFORE);
        {if (true) return Boolean.TRUE;}
      break;
    case AFTER:
      jj_consume_token(AFTER);
        {if (true) return Boolean.FALSE;}
      break;
    default:
      jj_la1[320] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public int triggerEvent(ResultColumnList rcl) throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case INSERT:
      jj_consume_token(INSERT);
        {if (true) return CreateTriggerNode.TRIGGER_EVENT_INSERT;}
      break;
    case DELETE:
      jj_consume_token(DELETE);
        {if (true) return CreateTriggerNode.TRIGGER_EVENT_DELETE;}
      break;
    case UPDATE:
      jj_consume_token(UPDATE);
      switch (jj_nt.kind) {
      case OF:
        jj_consume_token(OF);
        columnNameList(rcl);
        break;
      default:
        jj_la1[321] = jj_gen;
        ;
      }
        {if (true) return CreateTriggerNode.TRIGGER_EVENT_UPDATE;}
      break;
    default:
      jj_la1[322] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public Boolean rowOrStatement() throws ParseException {
    switch (jj_nt.kind) {
    case ROW:
      jj_consume_token(ROW);
        {if (true) return Boolean.TRUE;}
      break;
    case STATEMENT:
      jj_consume_token(STATEMENT);
        {if (true) return Boolean.FALSE;}
      break;
    default:
      jj_la1[323] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public List<TriggerReferencingStruct> triggerReferencingClause() throws ParseException, StandardException {
    List<TriggerReferencingStruct> list = new ArrayList<TriggerReferencingStruct>();
    jj_consume_token(REFERENCING);
    triggerReferencingExpression(list);
    label_48:
    while (true) {
      switch (jj_nt.kind) {
      case NEW:
      case NEW_TABLE:
      case OLD:
      case OLD_TABLE:
        ;
        break;
      default:
        jj_la1[324] = jj_gen;
        break label_48;
      }
      triggerReferencingExpression(list);
    }
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void triggerReferencingExpression(List<TriggerReferencingStruct> list) throws ParseException, StandardException {
    String identifier;
    boolean isNew = true;
    boolean isRow = true;
    switch (jj_nt.kind) {
    case NEW:
      jj_consume_token(NEW);
      switch (jj_nt.kind) {
      case TABLE:
      case ROW:
        switch (jj_nt.kind) {
        case ROW:
          jj_consume_token(ROW);
          break;
        case TABLE:
          jj_consume_token(TABLE);
                              isRow = false;
          break;
        default:
          jj_la1[325] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[326] = jj_gen;
        ;
      }
      break;
    case OLD:
      jj_consume_token(OLD);
           isNew = false;
      switch (jj_nt.kind) {
      case TABLE:
      case ROW:
        switch (jj_nt.kind) {
        case ROW:
          jj_consume_token(ROW);
          break;
        case TABLE:
          jj_consume_token(TABLE);
                                               isRow = false;
          break;
        default:
          jj_la1[327] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[328] = jj_gen;
        ;
      }
      break;
    case NEW_TABLE:
      jj_consume_token(NEW_TABLE);
                  isRow = false;
      break;
    case OLD_TABLE:
      jj_consume_token(OLD_TABLE);
                  isNew = false; isRow = false;
      break;
    default:
      jj_la1[329] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(AS);
    identifier = identifier();
        list.add(new TriggerReferencingStruct(isRow, isNew, identifier));
  }

  final public ValueNode defaultClause(long[] autoIncrementInfo, String columnName) throws ParseException, StandardException {
    ValueNode value;
    Token beginToken;
    switch (jj_nt.kind) {
    case _DEFAULT:
    case WITH:
      switch (jj_nt.kind) {
      case WITH:
        jj_consume_token(WITH);
        break;
      default:
        jj_la1[330] = jj_gen;
        ;
      }
      beginToken = jj_consume_token(_DEFAULT);
      value = defaultOption(beginToken, autoIncrementInfo, columnName);
        {if (true) return value;}
      break;
    case GENERATED:
      value = generatedColumnOption(autoIncrementInfo);
        {if (true) return value;}
      break;
    default:
      jj_la1[331] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode defaultNullOnlyClause() throws ParseException, StandardException {
    jj_consume_token(_DEFAULT);
    jj_consume_token(NULL);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

// TODO: Like it says:
// A specific class not such long[] should exists for autoIncrementInfo ...
  final public ValueNode generatedColumnOption(long[] autoIncrementInfo) throws ParseException, StandardException {
    ValueNode value = null;
    autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_START_INDEX] = 1;
    autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_INC_INDEX] = 1;
    autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_IS_AUTOINCREMENT_INDEX] = 1;
    autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_CREATE_MODIFY] = ColumnDefinitionNode.CREATE_AUTOINCREMENT;
    jj_consume_token(GENERATED);
    switch (jj_nt.kind) {
    case ALWAYS:
      value = generatedAlways(autoIncrementInfo);
            {if (true) return value;}
      break;
    case BY:
      value = generatedByDefault(autoIncrementInfo);
            {if (true) return value;}
      break;
    default:
      jj_la1[332] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode generatedAlways(long[] autoIncrementInfo) throws ParseException, StandardException {
    ValueNode value = null;
    jj_consume_token(ALWAYS);
    if (getToken(1).kind == AS && getToken(2).kind == IDENTITY) {
      asIdentity(autoIncrementInfo);
            {if (true) return value;}
    } else if (getToken(1).kind == AS && getToken(2).kind == LEFT_PAREN) {
      value = generationClause();
            {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode generatedByDefault(long[] autoIncrementInfo) throws ParseException, StandardException {
    ValueNode value = null;
    jj_consume_token(BY);
    jj_consume_token(_DEFAULT);
    asIdentity(autoIncrementInfo);
        value = (ValueNode)nodeFactory.getNode(NodeTypes.DEFAULT_NODE,
                                               parserContext) ;

        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public void asIdentity(long[] autoIncrementInfo) throws ParseException, StandardException {
    jj_consume_token(AS);
    jj_consume_token(IDENTITY);
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      autoIncrementBeginEnd(autoIncrementInfo);
      jj_consume_token(RIGHT_PAREN);
      break;
    default:
      jj_la1[333] = jj_gen;
      ;
    }
  }

  final public ValueNode generationClause() throws ParseException, StandardException {
    ValueNode value = null;
    Token beginToken = null;
    Token endToken = null;
    jj_consume_token(AS);
    beginToken = jj_consume_token(LEFT_PAREN);
    value = valueExpression();
    endToken = jj_consume_token(RIGHT_PAREN);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.GENERATION_CLAUSE_NODE,
                                              value,
                                              sliceSQLText(beginToken.endOffset + 1, endToken.beginOffset - 1, true),
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void autoIncrementBeginEnd(long[] autoIncrementInfo) throws ParseException, StandardException {
    long autoIncrementInitial = 1;
    long autoIncrementIncrement = 1;
    switch (jj_nt.kind) {
    case INCREMENT:
      jj_consume_token(INCREMENT);
      jj_consume_token(BY);
      autoIncrementIncrement = exactNumber();
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_INC_INDEX] = autoIncrementIncrement;
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_CREATE_MODIFY] = ColumnDefinitionNode.CREATE_AUTOINCREMENT;
        {if (true) return;}
      break;
    case START:
      jj_consume_token(START);
      jj_consume_token(WITH);
      autoIncrementInitial = exactNumber();
      switch (jj_nt.kind) {
      case COMMA:
        jj_consume_token(COMMA);
        jj_consume_token(INCREMENT);
        jj_consume_token(BY);
        autoIncrementIncrement = exactNumber();
        break;
      default:
        jj_la1[334] = jj_gen;
        ;
      }
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_START_INDEX] = autoIncrementInitial;
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_INC_INDEX] = autoIncrementIncrement;
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_CREATE_MODIFY] = ColumnDefinitionNode.CREATE_AUTOINCREMENT;
        {if (true) return;}
      break;
    default:
      jj_la1[335] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public ValueNode defaultOption(Token beginToken, long[] autoIncrementInfo, String columnName) throws ParseException, StandardException {
    Token endToken;
    Token errorTok = null;
    Token initialTok = null;
    ValueNode value;
    if (getToken(1).kind == NULL &&
                                     !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON)) {
      jj_consume_token(NULL);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                              parserContext);}
    } else if (jj_2_87(1)) {
      value = DB2DefaultOption(columnName);
        endToken = getToken(0);
        value.setBeginOffset(beginToken.beginOffset);
        value.setEndOffset(endToken.endOffset);
        value = (ValueNode)nodeFactory.getNode(NodeTypes.DEFAULT_NODE,
                                               value,
                                               sliceSQLText(beginToken.beginOffset + 7, endToken.endOffset, true),
                                               parserContext);
        {if (true) return value;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode DB2DefaultOption(String columnName) throws ParseException, StandardException {
    ValueNode value;
    if (getToken(2).kind == SCHEMA || getToken(2).kind == SQLID) {
      jj_consume_token(CURRENT);
      switch (jj_nt.kind) {
      case SCHEMA:
        jj_consume_token(SCHEMA);
        break;
      case SQLID:
        jj_consume_token(SQLID);
        break;
      default:
        jj_la1[336] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CURRENT_SCHEMA_NODE,
                                              parserContext);}
    } else {
      switch (jj_nt.kind) {
      case CURRENT_USER:
      case SESSION_USER:
      case USER:
        /* Revert DB2 restriction: DERBY-3013. Accept standard SQL CURRENT_USER,
             * SESSION_USER in addition to USER.
             */
            value = userNode();
        {if (true) return value;}
        break;
      case CURRENT_ROLE:
        value = currentRoleNode();
        {if (true) return value;}
        break;
      default:
        jj_la1[337] = jj_gen;
        if (getToken(1).kind == DATE ||
                                     getToken(1).kind == TIME ||
                                     getToken(1).kind == TIMESTAMP) {
          value = miscBuiltins();
      // these functions are allowed as valid <cast-function> defaults.
        // Once "BLOB" is allowed as a cast-function (5281), a case should be
        // added for that, as well.
        {if (true) return value;}
        } else if (getToken(2).kind == LEFT_PAREN ||
                                         (getToken(4).kind == LEFT_PAREN && getToken(2).kind != COMMA)) {
          // Check against comma: see Derby-331 
                  // Before adding this, the following was erroneously
                  // flagged as invalid: 
                  //       create table foo(.., b int default 0, unique (a))
              value = miscBuiltins();
        // If we have a function (as indicated by an open paren,
        // which can be either the 2nd token (w/ normal function name)
        // or the 4th token (w/ qualified function name)), then
        // it's not valid.  Catch it here and throw an "invalid
        // default" error (42894) instead of letting it go as
        // a syntax error (this matches DB2 UDB behavior).
        {if (true) throw new StandardException("Invalid default for " + columnName);}
        } else if (jj_2_88(1)) {
          value = datetimeValueFunction();
        {if (true) return value;}
        } else if (jj_2_89(1)) {
          // Only (valid) thing left is literals (i.e. actual constants).
              value = literal();
        {if (true) return value;}
        } else {
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode literal() throws ParseException, StandardException {
    String sign = "";
    Token tok;
    String datetimeString;
    String bitString;
    ValueNode constantNode;
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
    case EXACT_NUMERIC:
    case APPROXIMATE_NUMERIC:
      switch (jj_nt.kind) {
      case PLUS_SIGN:
      case MINUS_SIGN:
        sign = sign();
        break;
      default:
        jj_la1[338] = jj_gen;
        ;
      }
      constantNode = numericLiteral(sign);
        {if (true) return constantNode;}
      break;
    case DOUBLEQUOTED_STRING:
    case SINGLEQUOTED_STRING:
      constantNode = stringLiteral();
        {if (true) return  constantNode;}
      break;
    case HEX_STRING:
      constantNode = hexLiteral();
        {if (true) return  constantNode;}
      break;
    default:
      jj_la1[339] = jj_gen;
      if (jj_2_90(1)) {
        constantNode = dateTimeLiteral();
        {if (true) return constantNode;}
      } else {
        switch (jj_nt.kind) {
        case FALSE:
        case TRUE:
          tok = booleanLiteral();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.BOOLEAN_CONSTANT_NODE,
                                              "true".equalsIgnoreCase(tok.image) ? Boolean.TRUE : Boolean.FALSE,
                                              parserContext);}
          break;
        case INTERVAL:
          constantNode = intervalLiteral();
        {if (true) return constantNode;}
          break;
        case NULL:
          constantNode = nullSpecification();
        {if (true) return constantNode;}
          break;
        default:
          jj_la1[340] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public NumericConstantNode intLiteral() throws ParseException, StandardException {
    Token tok;
    String sign = null;
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
      sign = sign();
      break;
    default:
      jj_la1[341] = jj_gen;
      ;
    }
    tok = jj_consume_token(EXACT_NUMERIC);
        try {
            {if (true) return getNumericNode(getNumericString(tok, sign), true);}
        }
        catch (NumberFormatException e) {
            {if (true) throw new StandardException("Integer literal expected", e);}
        }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode numericLiteral(String sign) throws ParseException, StandardException {
    Token tok;
    switch (jj_nt.kind) {
    case EXACT_NUMERIC:
      tok = jj_consume_token(EXACT_NUMERIC);
        {if (true) return getNumericNode(getNumericString(tok, sign), false);}
      break;
    case APPROXIMATE_NUMERIC:
      tok = jj_consume_token(APPROXIMATE_NUMERIC);
        StringBuffer doubleImage;
        String doubleString;
        int ePosn, dotPosn; // Position of letter e and '.' in value
        Double      doubleValue;

        doubleImage = new StringBuffer(sign);
        doubleImage.append(tok.image);
        doubleString = doubleImage.toString();

        ePosn = doubleString.indexOf('E');
        if (ePosn == -1)
            ePosn = doubleString.indexOf('e');
        assert (ePosn != -1) : "no E or e in approximate numeric";

        // there is a limit on the length of a floatingpoint literal in DB2
        if (doubleString.length() > MAX_FLOATINGPOINT_LITERAL_LENGTH)
            {if (true) throw new StandardException("Floating point literal too long");}
        // if there is no '.' before the e, put one in
        dotPosn = doubleString.substring(0,ePosn).indexOf('.');
        if (dotPosn == -1) {
            doubleImage.insert(ePosn,'.');
            doubleString = doubleImage.toString();
            ePosn++;
        }

        try
        {
            doubleValue = Double.valueOf(doubleString);

        }
        catch (NumberFormatException nfe)
        {
            {if (true) throw new StandardException("Invalid double", nfe);}
        }

        double dv = doubleValue.doubleValue();

        // When the value is 0 it's possible rounded, try to detect it by checking if the mantissa is 0.0
        //   "proof of correctness": any nonzero value (mantissa) with less than 30 characters will not be
        //                                                   rounded to 0.0 by a float/real. This correctly detects the case when
        //                                                   the radix/exponent being "too small" (1e-900) giving a value rounded to zero.
        if ( (dv == 0.0d) && (Double.parseDouble(doubleString.substring(0, ePosn-1)) != 0.0d) )
        {
            {if (true) throw new StandardException("Floating point exponent underflow");}
        }

        if (Double.isNaN(dv) || Double.isInfinite(dv))
            {if (true) throw new StandardException("Floating point exponent overflow");}

        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.DOUBLE_CONSTANT_NODE,
                                              doubleValue,
                                              parserContext);}
      break;
    default:
      jj_la1[342] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode dateTimeLiteral() throws ParseException, StandardException {
    ValueNode constantNode;
    DataTypeDescriptor typeDescriptor;
    switch (jj_nt.kind) {
    case LEFT_BRACE:
      jj_consume_token(LEFT_BRACE);
      constantNode = escapedDateTimeLiteral();
      jj_consume_token(RIGHT_BRACE);
        {if (true) return constantNode;}
      break;
    default:
      jj_la1[343] = jj_gen;
      if (isDATETIME(getToken(1).kind) && getToken(2).kind == SINGLEQUOTED_STRING) {
        typeDescriptor = datetimeType();
        constantNode = stringLiteral();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                              constantNode, typeDescriptor,
                                              parserContext);}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode escapedDateTimeLiteral() throws ParseException, StandardException {
    ValueNode constantNode;
    switch (jj_nt.kind) {
    case D:
      jj_consume_token(D);
      constantNode = bareDateLiteral();
        {if (true) return constantNode;}
      break;
    case T:
      jj_consume_token(T);
      constantNode = bareTimeLiteral();
        {if (true) return constantNode;}
      break;
    case TS:
      jj_consume_token(TS);
      constantNode = bareTimestampLiteral();
        {if (true) return constantNode;}
      break;
    default:
      jj_la1[344] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode bareDateLiteral() throws ParseException, StandardException {
    String dateString;
    dateString = string();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.USERTYPE_CONSTANT_NODE,
                                              Date.valueOf(dateString),
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode bareTimeLiteral() throws ParseException, StandardException {
    String timeString;
    timeString = string();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.USERTYPE_CONSTANT_NODE,
                                              Time.valueOf(timeString),
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode bareTimestampLiteral() throws ParseException, StandardException {
    String timestampString;
    timestampString = string();
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.USERTYPE_CONSTANT_NODE,
                                              Timestamp.valueOf(timestampString),
                                              parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode intervalLiteral() throws ParseException, StandardException {
    ValueNode value;
    DataTypeDescriptor intervalType;
    int[] factors = new int[] { 1, 1 };
    jj_consume_token(INTERVAL);
    value = valueExpression();
    if (jj_2_91(1)) {
      intervalType = intervalQualifier();
    } else if (mysqlIntervalFollows()) {
      intervalType = mysqlIntervalQualifier(factors);
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
        value = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                               value, intervalType,
                                               parserContext);
        if (factors[0] != 1)
            value = (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_TIMES_OPERATOR_NODE,
                                                   value,
                                                   nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                                                       Integer.valueOf(factors[0]),
                                                                       parserContext),
                                                   parserContext);
        if (factors[1] != 1)
            value = (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_DIVIDE_OPERATOR_NODE,
                                                   value,
                                                   nodeFactory.getNode(NodeTypes.INT_CONSTANT_NODE,
                                                                       Integer.valueOf(factors[1]),
                                                                       parserContext),
                                                   parserContext);
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public DataTypeDescriptor mysqlIntervalQualifier(int[] factors) throws ParseException, StandardException {
    TypeId typeId;
    int prec = 0, scale = 0;
    switch (jj_nt.kind) {
    case MICROSECOND:
      jj_consume_token(MICROSECOND);
        typeId = TypeId.INTERVAL_SECOND_ID;
        scale = 6;
        factors[1] = 1000000;
      break;
    case WEEK:
      jj_consume_token(WEEK);
        typeId = TypeId.INTERVAL_DAY_ID;
        factors[0] = 7;
      break;
    case QUARTER:
      jj_consume_token(QUARTER);
        typeId = TypeId.INTERVAL_MONTH_ID;
        factors[0] = 3;
      break;
    case SECOND_MICROSECOND:
      jj_consume_token(SECOND_MICROSECOND);
        typeId = TypeId.INTERVAL_SECOND_ID;
        scale = 6;
      break;
    case MINUTE_MICROSECOND:
      jj_consume_token(MINUTE_MICROSECOND);
        typeId = TypeId.INTERVAL_MINUTE_SECOND_ID;
        scale = 6;
      break;
    case MINUTE_SECOND:
      jj_consume_token(MINUTE_SECOND);
        typeId = TypeId.INTERVAL_MINUTE_SECOND_ID;
      break;
    case HOUR_MICROSECOND:
      jj_consume_token(HOUR_MICROSECOND);
        typeId = TypeId.INTERVAL_HOUR_SECOND_ID;
        scale = 6;
      break;
    case HOUR_SECOND:
      jj_consume_token(HOUR_SECOND);
        typeId = TypeId.INTERVAL_HOUR_SECOND_ID;
      break;
    case HOUR_MINUTE:
      jj_consume_token(HOUR_MINUTE);
        typeId = TypeId.INTERVAL_HOUR_MINUTE_ID;
      break;
    case DAY_MICROSECOND:
      jj_consume_token(DAY_MICROSECOND);
        typeId = TypeId.INTERVAL_DAY_SECOND_ID;
        scale = 6;
      break;
    case DAY_SECOND:
      jj_consume_token(DAY_SECOND);
        typeId = TypeId.INTERVAL_DAY_SECOND_ID;
      break;
    case DAY_MINUTE:
      jj_consume_token(DAY_MINUTE);
        typeId = TypeId.INTERVAL_DAY_MINUTE_ID;
      break;
    case DAY_HOUR:
      jj_consume_token(DAY_HOUR);
        typeId = TypeId.INTERVAL_DAY_HOUR_ID;
      break;
    case YEAR_MONTH:
      jj_consume_token(YEAR_MONTH);
        typeId = TypeId.INTERVAL_YEAR_MONTH_ID;
      break;
    default:
      jj_la1[345] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return new DataTypeDescriptor(typeId, prec, scale,
                                      true, DataTypeDescriptor.intervalMaxWidth(typeId, prec, scale));}
    throw new Error("Missing return statement in function");
  }

  final public String string() throws ParseException, StandardException {
    Token tok;
    switch (jj_nt.kind) {
    case SINGLEQUOTED_STRING:
      tok = jj_consume_token(SINGLEQUOTED_STRING);
        parserContext.checkStringLiteralLengthLimit(tok.image);
        /* Trim off the leading and trailing ', and compress all '' to ' */
        {if (true) return trimAndCompressQuotes(tok.image, SINGLEQUOTES, false);}
      break;
    case DOUBLEQUOTED_STRING:
      tok = jj_consume_token(DOUBLEQUOTED_STRING);
        parserContext.checkStringLiteralLengthLimit(tok.image);
        /* Trim off the leading and trailing ', and compress all '' to ' */
        {if (true) return trimAndCompressQuotes(tok.image, DOUBLEQUOTES, true);}
      break;
    case DOUBLEDOLLAR_STRING:
      tok = jj_consume_token(DOUBLEDOLLAR_STRING);
        parserContext.checkStringLiteralLengthLimit(tok.image);
        /* Trim off the leading and trailing $$ */
        {if (true) return tok.image.substring(2, tok.image.length()-2);}
      break;
    default:
      jj_la1[346] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public CharConstantNode stringLiteral() throws ParseException, StandardException {
    String st;
    st = getStringLiteral();
        {if (true) return (CharConstantNode)nodeFactory.getNode(NodeTypes.CHAR_CONSTANT_NODE,
                                                    st,
                                                    parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public String getStringLiteral() throws ParseException, StandardException {
    Token tok;
    String string;
    switch (jj_nt.kind) {
    case SINGLEQUOTED_STRING:
      tok = jj_consume_token(SINGLEQUOTED_STRING);
        parserContext.checkStringLiteralLengthLimit(tok.image);
        string = trimAndCompressQuotes(tok.image, SINGLEQUOTES, false);
      break;
    case DOUBLEQUOTED_STRING:
      tok = jj_consume_token(DOUBLEQUOTED_STRING);
        parserContext.checkStringLiteralLengthLimit(tok.image);
        string = trimAndCompressQuotes(tok.image, DOUBLEQUOTES, true);
      break;
    default:
      jj_la1[347] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return string;}
    throw new Error("Missing return statement in function");
  }

  final public String collateClause() throws ParseException, StandardException {
    TableName collation;
    jj_consume_token(COLLATE);
    collation = qualifiedName();
        {if (true) return collation.toString();}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode hexLiteral() throws ParseException, StandardException {
    Token tok;
    tok = jj_consume_token(HEX_STRING);
        String hexLiteral = tok.image;

        //there is a maximum limit on the length of the hex constant
        if (hexLiteral.length()-3 > 65535*2)
            {if (true) throw new StandardException("Hex literal too long");}
        if ((hexLiteral.length()-3)%2 == 1)
            {if (true) throw new StandardException("Hex literal invalid");}

        int bitLength = ((hexLiteral.length() - 3) / 2);
        {if (true) return (ValueNode)
                nodeFactory.getNode(NodeTypes.VARBIT_CONSTANT_NODE,
                                    hexLiteral.substring(2, hexLiteral.length() - 1), bitLength,
                                    parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public TableName constraintNameDefinition() throws ParseException, StandardException {
    TableName constraintName;
    jj_consume_token(CONSTRAINT);
    constraintName = qualifiedName();
        {if (true) return constraintName;}
    throw new Error("Missing return statement in function");
  }

/*
 * DB2 requires column check constraints to refer to only that column. Derby
 * doesn't care if check constraints are column level or table level. For DB2 compatibility
 * check that column check constraints only refer to that column.
 */
  final public ConstraintDefinitionNode checkConstraintDefinition(TableName constraintName, String columnName) throws ParseException, StandardException {
    Token beginToken, endToken;
    ValueNode value;
    ResultColumnList rclList = null;
    jj_consume_token(CHECK);
    beginToken = jj_consume_token(LEFT_PAREN);
    value = valueExpression();
    endToken = jj_consume_token(RIGHT_PAREN);
        if (columnName != null) {
            /* Column check constraint */
            rclList = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                            parserContext);
            rclList.addResultColumn((ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                                      columnName,
                                                                      null,
                                                                      parserContext));
        }

        value.setBeginOffset(beginToken.beginOffset);
        value.setEndOffset(endToken.endOffset);
        {if (true) return (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                             constraintName,
                                                             ConstraintDefinitionNode.ConstraintType.CHECK,
                                                             rclList,
                                                             null,
                                                             value,
                                                             sliceSQLText(beginToken.beginOffset, endToken.endOffset, true),
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

// Index names are like column names: unqualified searches tables for
// match with possible ambiguous exception.
  final public String indexName(TableName[] retTableName) throws ParseException, StandardException {
    String firstName;
    String secondName = null;
    String thirdName = null;
    firstName = identifierDeferCheckLength();
    switch (jj_nt.kind) {
    case PERIOD:
      jj_consume_token(PERIOD);
      secondName = identifierDeferCheckLength();
      switch (jj_nt.kind) {
      case PERIOD:
        jj_consume_token(PERIOD);
        thirdName = identifierDeferCheckLength();
        break;
      default:
        jj_la1[348] = jj_gen;
        ;
      }
      break;
    default:
      jj_la1[349] = jj_gen;
      ;
    }
        String schemaName = null;
        String tableName = null;
        String indexName = null;

        if (thirdName != null) {
            // Three names: schema.table.index
            schemaName = firstName;
            tableName = secondName;
            indexName = thirdName;
        }
        else if (secondName != null) {
            // Two names: table.index
            tableName = firstName;
            indexName = secondName;
        }
        else {
            // Only one name, index.
            indexName = firstName;
        }

        if (tableName != null) {
            if (schemaName != null)
                parserContext.checkIdentifierLengthLimit(schemaName);
            parserContext.checkIdentifierLengthLimit(tableName);
            retTableName[0] = (TableName)nodeFactory.getNode(NodeTypes.TABLE_NAME,
                                                             schemaName,
                                                             tableName,
                                                             new Integer(nextToLastIdentifierToken.beginOffset),
                                                             new Integer(nextToLastIdentifierToken.endOffset),
                                                             parserContext);
        }

        parserContext.checkIdentifierLengthLimit(indexName);
        {if (true) return indexName;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode spsRenameStatement() throws ParseException, StandardException {
    StatementNode qtn;
    jj_consume_token(RENAME);
    switch (jj_nt.kind) {
    case TABLE:
      qtn = renameTableStatement();
      break;
    case INDEX:
      qtn = renameIndexStatement();
      break;
    case COLUMN:
      qtn = renameColumnStatement();
      break;
    default:
      jj_la1[350] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return qtn;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode renameTableStatement() throws ParseException, StandardException {
    TableName tableName, newTableName;
    jj_consume_token(TABLE);
    tableName = qualifiedName();
    jj_consume_token(TO);
    newTableName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.RENAME_NODE,
                                                  tableName,
                                                  null,
                                                  newTableName,
                                                  Boolean.FALSE,
                                                  RenameNode.RenameType.TABLE,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode renameIndexStatement() throws ParseException, StandardException {
    String indexName, newIndexName;
    TableName tableName[] = new TableName[1];
    jj_consume_token(INDEX);
    indexName = indexName(tableName);
    jj_consume_token(TO);
    newIndexName = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.RENAME_NODE,
                                                  tableName[0],
                                                  indexName,
                                                  newIndexName,
                                                  Boolean.FALSE,
                                                  RenameNode.RenameType.INDEX,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode renameColumnStatement() throws ParseException, StandardException {
    String newColumnName;
    ColumnReference oldColumnReference;
    TableElementList tableElementList;
    TableName tableName;
    jj_consume_token(COLUMN);
    oldColumnReference = columnReference();
    jj_consume_token(TO);
    newColumnName = identifier();
        if ((tableName = oldColumnReference.getTableNameNode()) == null)
            {if (true) throw new StandardException("Table name missing in RENAME COLUMN");}

        tableElementList = (TableElementList)nodeFactory
                    .getNode(NodeTypes.TABLE_ELEMENT_LIST, parserContext);

        tableElementList.addTableElement((TableElementNode)nodeFactory
                    .getNode(NodeTypes.AT_RENAME_COLUMN_NODE,
                             oldColumnReference.getColumnName(),
                             newColumnName,
                             parserContext));

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  tableElementList,
                                                  new Character('\u005c0'),
                                                  new int[] {DDLStatementNode.MODIFY_TYPE},
                                                  new int[] {0},
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode lockStatement() throws ParseException, StandardException {
    Boolean exclusiveMode;
    TableName tableName;
    jj_consume_token(LOCK);
    jj_consume_token(TABLE);
    tableName = qualifiedName();
    jj_consume_token(IN);
    exclusiveMode = lockMode();
    jj_consume_token(MODE);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.LOCK_TABLE_NODE,
                                                  tableName,
                                                  exclusiveMode,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public Boolean lockMode() throws ParseException {
    switch (jj_nt.kind) {
    case EXCLUSIVE:
      jj_consume_token(EXCLUSIVE);
        {if (true) return Boolean.TRUE;}
      break;
    case SHARE:
      jj_consume_token(SHARE);
        {if (true) return Boolean.FALSE;}
      break;
    default:
      jj_la1[351] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public TransactionStatementNode setIsolationStatement() throws ParseException, StandardException {
    TransactionStatementNode tranNode;
    setIsolationHeader();
    switch (jj_nt.kind) {
    case TO:
    case EQUALS_OPERATOR:
      switch (jj_nt.kind) {
      case EQUALS_OPERATOR:
        jj_consume_token(EQUALS_OPERATOR);
        break;
      case TO:
        jj_consume_token(TO);
        break;
      default:
        jj_la1[352] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[353] = jj_gen;
      ;
    }
    tranNode = transactionMode();
        {if (true) return tranNode;}
    throw new Error("Missing return statement in function");
  }

  final public void setIsolationHeader() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case ISOLATION:
      jj_consume_token(ISOLATION);
      break;
    default:
      jj_la1[354] = jj_gen;
      if (getToken(1).kind == CURRENT && getToken(2).kind == ISOLATION) {
        jj_consume_token(CURRENT);
        jj_consume_token(ISOLATION);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public TransactionStatementNode transactionMode() throws ParseException, StandardException {
    IsolationLevel isolationLevel;
    isolationLevel = isolationLevelDB2OrReset();
        {if (true) return (TransactionStatementNode)nodeFactory.getNode(NodeTypes.SET_TRANSACTION_ISOLATION_NODE,
                                                             Boolean.FALSE,
                                                             isolationLevel,
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel isolationLevelDB2OrReset() throws ParseException {
    IsolationLevel isolationLevel;
    switch (jj_nt.kind) {
    case RESET:
      jj_consume_token(RESET);
        {if (true) return IsolationLevel.UNSPECIFIED_ISOLATION_LEVEL;}
      break;
    default:
      jj_la1[355] = jj_gen;
      if (jj_2_92(1)) {
        isolationLevel = isolationLevelDB2();
        {if (true) return isolationLevel;}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel isolationLevelDB2() throws ParseException {
    IsolationLevel isolationLevel;
    switch (jj_nt.kind) {
    case CS:
    case RR:
    case RS:
    case UR:
      isolationLevel = isolationLevelDB2Abbrev();
        {if (true) return isolationLevel;}
      break;
    case REPEATABLE:
    case SERIALIZABLE:
      switch (jj_nt.kind) {
      case REPEATABLE:
        jj_consume_token(REPEATABLE);
        jj_consume_token(READ);
        break;
      case SERIALIZABLE:
        jj_consume_token(SERIALIZABLE);
        break;
      default:
        jj_la1[356] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return IsolationLevel.SERIALIZABLE_ISOLATION_LEVEL;}
      break;
    case CURSOR:
      jj_consume_token(CURSOR);
      jj_consume_token(STABILITY);
        {if (true) return IsolationLevel.READ_COMMITTED_ISOLATION_LEVEL;}
      break;
    case DIRTY:
      jj_consume_token(DIRTY);
      jj_consume_token(READ);
        {if (true) return IsolationLevel.READ_UNCOMMITTED_ISOLATION_LEVEL;}
      break;
    default:
      jj_la1[357] = jj_gen;
      if (getToken(1).kind == READ && getToken(2).kind == COMMITTED) {
        jj_consume_token(READ);
        jj_consume_token(COMMITTED);
        {if (true) return IsolationLevel.READ_COMMITTED_ISOLATION_LEVEL;}
      } else if (getToken(1).kind == READ && getToken(2).kind == UNCOMMITTED) {
        jj_consume_token(READ);
        jj_consume_token(UNCOMMITTED);
        {if (true) return IsolationLevel.READ_UNCOMMITTED_ISOLATION_LEVEL;}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel isolationLevelDB2Abbrev() throws ParseException {
    switch (jj_nt.kind) {
    case RR:
      jj_consume_token(RR);
        {if (true) return IsolationLevel.SERIALIZABLE_ISOLATION_LEVEL;}
      break;
    case RS:
      jj_consume_token(RS);
        {if (true) return IsolationLevel.REPEATABLE_READ_ISOLATION_LEVEL;}
      break;
    case CS:
      jj_consume_token(CS);
        {if (true) return IsolationLevel.READ_COMMITTED_ISOLATION_LEVEL;}
      break;
    case UR:
      jj_consume_token(UR);
        {if (true) return IsolationLevel.READ_UNCOMMITTED_ISOLATION_LEVEL;}
      break;
    default:
      jj_la1[358] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel isolationLevel() throws ParseException {
    IsolationLevel isolationLevel;
    jj_consume_token(ISOLATION);
    jj_consume_token(LEVEL);
    isolationLevel = levelOfIsolation();
        {if (true) return isolationLevel;}
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel levelOfIsolation() throws ParseException {
    switch (jj_nt.kind) {
    case READ:
      jj_consume_token(READ);
        {if (true) return levelOfIsolationRead();}
      break;
    case REPEATABLE:
      jj_consume_token(REPEATABLE);
      jj_consume_token(READ);
        {if (true) return IsolationLevel.REPEATABLE_READ_ISOLATION_LEVEL;}
      break;
    case SERIALIZABLE:
      jj_consume_token(SERIALIZABLE);
        {if (true) return IsolationLevel.SERIALIZABLE_ISOLATION_LEVEL;}
      break;
    default:
      jj_la1[359] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public IsolationLevel levelOfIsolationRead() throws ParseException {
    switch (jj_nt.kind) {
    case UNCOMMITTED:
      jj_consume_token(UNCOMMITTED);
        {if (true) return IsolationLevel.READ_UNCOMMITTED_ISOLATION_LEVEL;}
      break;
    case COMMITTED:
      jj_consume_token(COMMITTED);
        {if (true) return IsolationLevel.READ_COMMITTED_ISOLATION_LEVEL;}
      break;
    default:
      jj_la1[360] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode simpleValueSpecification() throws ParseException, StandardException {
    ValueNode value;
    value = literal();
        {if (true) return value;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode setRoleStatement() throws ParseException, StandardException {
    StatementNode role;
    jj_consume_token(ROLE);
    role = setRoleSpecification();
        if (parameterList != null && parameterList.size() > 0) {
            // Can also be prepared with ? argument, cf. SET SCHEMA.
            // set the type of parameter node, it should be a varchar
            // max Limits.MAX_IDENTIFIER_LENGTH - non nullable
            ParameterNode p = parameterList.get(0);
            p.setType(new DataTypeDescriptor(TypeId.getBuiltInTypeId(Types.VARCHAR), false,
                                             128));
        }
        {if (true) return role;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode setRoleSpecification() throws ParseException, StandardException {
    String roleName = null;
    switch (jj_nt.kind) {
    case NONE:
      jj_consume_token(NONE);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_ROLE_NODE,
                                                  roleName,
                                                  null,
                                                  parserContext);}
      break;
    default:
      jj_la1[361] = jj_gen;
      if (jj_2_93(1)) {
        roleName = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_ROLE_NODE,
                                                  roleName,
                                                  null,
                                                  parserContext);}
      } else {
        switch (jj_nt.kind) {
        case QUESTION_MARK:
        case DOLLAR_N:
          dynamicParameterSpecification();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_ROLE_NODE,
                                                  null,
                                                  StatementType.SET_ROLE_DYNAMIC,
                                                  parserContext);}
          break;
        case DOUBLEQUOTED_STRING:
        case DOUBLEDOLLAR_STRING:
        case SINGLEQUOTED_STRING:
          roleName = string();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_ROLE_NODE,
                                                  roleName,
                                                  null,
                                                  parserContext);}
          break;
        default:
          jj_la1[362] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode setSchemaStatement() throws ParseException, StandardException {
    StatementNode setSchema;
    setSchemaHeader();
    switch (jj_nt.kind) {
    case EQUALS_OPERATOR:
      jj_consume_token(EQUALS_OPERATOR);
      break;
    default:
      jj_la1[363] = jj_gen;
      ;
    }
    setSchema = setSchemaValues();
        if (parameterList != null && parameterList.size() > 0) {
            // Set the type of parameter node, it should be a VARCHAR.
            ParameterNode p = parameterList.get(0);
            p.setType(new DataTypeDescriptor(TypeId.getBuiltInTypeId(Types.VARCHAR), false,
                                             128));
        }
        {if (true) return setSchema;}
    throw new Error("Missing return statement in function");
  }

  final public void setSchemaHeader() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case SCHEMA:
      jj_consume_token(SCHEMA);
      break;
    default:
      jj_la1[365] = jj_gen;
      if (getToken(1).kind == CURRENT &&
                                       (getToken(2).kind == SCHEMA || getToken(2).kind == SQLID )) {
        jj_consume_token(CURRENT);
        switch (jj_nt.kind) {
        case SCHEMA:
          jj_consume_token(SCHEMA);
          break;
        case SQLID:
          jj_consume_token(SQLID);
          break;
        default:
          jj_la1[364] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public StatementNode setSchemaValues() throws ParseException, StandardException {
    String schemaName;
    if (jj_2_94(1)) {
      schemaName = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_SCHEMA_NODE,
                                                  schemaName,
                                                  null,
                                                  parserContext);}
    } else {
      switch (jj_nt.kind) {
      case USER:
        jj_consume_token(USER);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_SCHEMA_NODE,
                                                  null,
                                                  StatementType.SET_SCHEMA_USER,
                                                  parserContext);}
        break;
      case QUESTION_MARK:
      case DOLLAR_N:
        dynamicParameterSpecification();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_SCHEMA_NODE,
                                                  null,
                                                  StatementType.SET_SCHEMA_DYNAMIC,
                                                  parserContext);}
        break;
      case DOUBLEQUOTED_STRING:
      case DOUBLEDOLLAR_STRING:
      case SINGLEQUOTED_STRING:
        schemaName = string();
        parserContext.checkIdentifierLengthLimit(schemaName);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_SCHEMA_NODE,
                                                  schemaName,
                                                  null,
                                                  parserContext);}
        break;
      default:
        jj_la1[366] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

// Set the locale for messages coming from the database system. This
// is for support only, so we can get messages in our preferred language
// (usually English). I didn't want to create all the execution wiring
// to do this, so this command executes in the parser
  final public StatementNode setMessageLocaleStatement() throws ParseException, StandardException {
    String messageLocale;
    jj_consume_token(MESSAGE_LOCALE);
    messageLocale = string();
        parserContext.setMessageLocale(messageLocale);

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.NOP_STATEMENT_NODE,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode setTransactionStatement() throws ParseException, StandardException {
    Boolean current;
    StatementNode transactionStatement;
    switch (jj_nt.kind) {
    case TRANSACTION:
      jj_consume_token(TRANSACTION);
                    current = Boolean.TRUE;
      break;
    case SESSION:
      jj_consume_token(SESSION);
      jj_consume_token(CHARACTERISTICS);
      jj_consume_token(AS);
      jj_consume_token(TRANSACTION);
                                                     current = Boolean.FALSE;
      break;
    default:
      jj_la1[367] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    transactionStatement = transactionStatement(current);
        {if (true) return transactionStatement;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode transactionStatement(Boolean current) throws ParseException, StandardException {
    IsolationLevel isolationLevel;
    AccessMode accessMode;
    switch (jj_nt.kind) {
    case ISOLATION:
      isolationLevel = isolationLevel();
        {if (true) return (TransactionStatementNode)nodeFactory.getNode(NodeTypes.SET_TRANSACTION_ISOLATION_NODE,
                                                             current,
                                                             isolationLevel,
                                                             parserContext);}
      break;
    case READ:
      accessMode = transactionAccessMode();
        {if (true) return (TransactionStatementNode)nodeFactory.getNode(NodeTypes.SET_TRANSACTION_ACCESS_NODE,
                                                             current,
                                                             accessMode,
                                                             parserContext);}
      break;
    default:
      jj_la1[368] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public AccessMode transactionAccessMode() throws ParseException, StandardException {
    jj_consume_token(READ);
    switch (jj_nt.kind) {
    case ONLY:
      jj_consume_token(ONLY);
        {if (true) return AccessMode.READ_ONLY_ACCESS_MODE;}
      break;
    case WRITE:
      jj_consume_token(WRITE);
        {if (true) return AccessMode.READ_WRITE_ACCESS_MODE;}
      break;
    default:
      jj_la1[369] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode setConfigurationStatement() throws ParseException, StandardException {
    Token variable;
    CharConstantNode value = null;
    variable = jj_consume_token(IDENTIFIER);
    switch (jj_nt.kind) {
    case EQUALS_OPERATOR:
      jj_consume_token(EQUALS_OPERATOR);
      break;
    case TO:
      jj_consume_token(TO);
      break;
    default:
      jj_la1[370] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    switch (jj_nt.kind) {
    case DOUBLEQUOTED_STRING:
    case SINGLEQUOTED_STRING:
      value = stringLiteral();
      break;
    case _DEFAULT:
      jj_consume_token(_DEFAULT);
      break;
    default:
      jj_la1[371] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.SET_CONFIGURATION_NODE,
                                                  variable.image,
                                                  (value == null) ? null : value.getValue(),
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode valueSpecification() throws ParseException, StandardException {
    ValueNode value;
    ValueNode leftExpression;
    ValueNode rightExpression;
    if (jj_2_95(1)) {
      value = literal();
        {if (true) return value;}
    } else {
      switch (jj_nt.kind) {
      case CURRENT_USER:
      case SESSION_USER:
      case USER:
      case CURRENT_ROLE:
      case CURRENT_SCHEMA:
      case QUESTION_MARK:
      case DOLLAR_N:
        value = generalValueSpecification();
        {if (true) return value;}
        break;
      case NULLIF:
        jj_consume_token(NULLIF);
        jj_consume_token(LEFT_PAREN);
        leftExpression = additiveExpression();
        jj_consume_token(COMMA);
        rightExpression = additiveExpression();
        jj_consume_token(RIGHT_PAREN);
        // "NULLIF(L, R)" is the same as "L=R ? untyped NULL : L"
        // An impl assumption here is that Derby can promote CHAR to any comparable datatypes such as numeric
        ValueNodeList thenElseList = (ValueNodeList)nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST, parserContext);

        //Use untyped null for then clause at this point. At the bind time, we will cast it to the datatype of L 
        thenElseList.addValueNode((ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                                                 parserContext));
        thenElseList.addValueNode(leftExpression);

        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.CONDITIONAL_NODE,
                                              (ValueNode)nodeFactory.getNode(NodeTypes.BINARY_EQUALS_OPERATOR_NODE,
                                                                             leftExpression,
                                                                             rightExpression,
                                                                             parserContext),
                                              thenElseList,
                                              Boolean.TRUE,//this node is for nullif 
                                              parserContext);}
        break;
      case CASE:
        jj_consume_token(CASE);
        value = caseExpression();
        {if (true) return value;}
        break;
      default:
        jj_la1[372] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode caseExpression() throws ParseException, StandardException {
    ValueNode expr;
    if (getToken(1).kind == WHEN) {
      expr = whenThenExpression();
        {if (true) return expr;}
    } else if (jj_2_96(1)) {
      expr = simpleCaseExpression();
        {if (true) return expr;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode whenThenExpression() throws ParseException, StandardException {
    ValueNode expr;
    ValueNode thenExpr;
    ValueNode elseExpr;
    jj_consume_token(WHEN);
    expr = orExpression(null);
    label_49:
    while (true) {
      switch (jj_nt.kind) {
      case OR:
        ;
        break;
      default:
        jj_la1[373] = jj_gen;
        break label_49;
      }
      jj_consume_token(OR);
      expr = orExpression(expr);
    }
    jj_consume_token(THEN);
    thenExpr = thenElseExpression();
    elseExpr = caseElseExpression();
        ValueNodeList thenElseList = (ValueNodeList)nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST, parserContext);
        thenElseList.addValueNode(thenExpr); // then
        thenElseList.addValueNode(elseExpr); // else

        {if (true) return((ValueNode)nodeFactory.getNode(NodeTypes.CONDITIONAL_NODE,
                                              expr,
                                              thenElseList,
                                              Boolean.FALSE,
                                              parserContext));}
    throw new Error("Missing return statement in function");
  }

  final public ValueNode thenElseExpression() throws ParseException, StandardException {
    ValueNode expr;
    if (getToken(1).kind == NULL) {
      jj_consume_token(NULL);
        ValueNode value = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                                         (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                                                                        parserContext),
                                                         DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR, 1),
                                                         parserContext);
        ((CastNode)value).setForExternallyGeneratedCASTnode();
        {if (true) return value;}
    } else if (jj_2_97(1)) {
      expr = additiveExpression();
        {if (true) return expr;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode caseElseExpression() throws ParseException, StandardException {
    ValueNode expr;
    switch (jj_nt.kind) {
    case END:
      jj_consume_token(END);
        ValueNode value = (ValueNode)nodeFactory.getNode(NodeTypes.CAST_NODE,
                                                         (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE,
                                                                                        parserContext),
                                                         DataTypeDescriptor.getBuiltInDataTypeDescriptor(Types.CHAR, 1),
                                                         parserContext);
        ((CastNode)value).setForExternallyGeneratedCASTnode();
        {if (true) return value;}
      break;
    case ELSE:
      jj_consume_token(ELSE);
      expr = thenElseExpression();
      jj_consume_token(END);
        {if (true) return expr;}
      break;
    case WHEN:
      expr = whenThenExpression();
        {if (true) return expr;}
      break;
    default:
      jj_la1[374] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode simpleCaseExpression() throws ParseException, StandardException {
    ValueNode operand;
    SimpleCaseNode caseExpr;
    operand = additiveExpression();
        caseExpr = (SimpleCaseNode)nodeFactory.getNode(NodeTypes.SIMPLE_CASE_NODE,
                                                       operand,
                                                       parserContext);
    simpleCaseWhenThenExpression(caseExpr);
        {if (true) return caseExpr;}
    throw new Error("Missing return statement in function");
  }

  final public void simpleCaseWhenThenExpression(SimpleCaseNode caseExpr) throws ParseException, StandardException {
    ValueNode expr;
    ValueNode thenExpr;
    ValueNode elseExpr;
    jj_consume_token(WHEN);
    expr = additiveExpression();
    jj_consume_token(THEN);
    thenExpr = thenElseExpression();
        caseExpr.addCase(expr, thenExpr);
    simpleCaseElseExpression(caseExpr);
  }

  final public void simpleCaseElseExpression(SimpleCaseNode caseExpr) throws ParseException, StandardException {
    ValueNode expr;
    switch (jj_nt.kind) {
    case END:
      jj_consume_token(END);
      break;
    case ELSE:
      jj_consume_token(ELSE);
      expr = thenElseExpression();
      jj_consume_token(END);
        caseExpr.setElseValue(expr);
      break;
    case WHEN:
      simpleCaseWhenThenExpression(caseExpr);
      break;
    default:
      jj_la1[375] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public void tableConstraintDefinition(TableElementList tableElementList) throws ParseException, StandardException {
    Properties properties = null;
    ConstraintDefinitionNode tcdn;
    TableName constraintName = null;
    switch (jj_nt.kind) {
    case CONSTRAINT:
      constraintName = constraintNameDefinition();
      break;
    default:
      jj_la1[376] = jj_gen;
      ;
    }
    tcdn = tableConstraint(constraintName);
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      properties = propertyList(false);
      jj_consume_token(CHECK_PROPERTIES);
      break;
    default:
      jj_la1[377] = jj_gen;
      ;
    }
        if (properties != null) {
            tcdn.setProperties(properties);
        }

        tableElementList.addTableElement((TableElementNode)tcdn);
  }

  final public ConstraintDefinitionNode tableConstraint(TableName constraintName) throws ParseException, StandardException {
    ConstraintDefinitionNode tcdn;
    switch (jj_nt.kind) {
    case INDEX:
      tcdn = indexConstraintDefinition(constraintName);
        {if (true) return tcdn;}
      break;
    case PRIMARY:
    case UNIQUE:
      tcdn = uniqueConstraintDefinition(constraintName);
        {if (true) return tcdn;}
      break;
    default:
      jj_la1[378] = jj_gen;
      if (jj_2_98(1)) {
        tcdn = referentialConstraintDefinition(constraintName);
        {if (true) return tcdn;}
      } else {
        switch (jj_nt.kind) {
        case CHECK:
          tcdn = checkConstraintDefinition(constraintName, null);
        {if (true) return tcdn;}
          break;
        default:
          jj_la1[379] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public ConstraintDefinitionNode indexConstraintDefinition(TableName constraintName) throws ParseException, StandardException {
    boolean groupFormat = hasFeature(SQLParserFeature.GROUPING);

    String indexName = null;
    IndexColumnList indexColumnList = null;
    JoinNode.JoinType joinType = null;
    StorageLocation location = null;
    jj_consume_token(INDEX);
    if (jj_2_99(1)) {
      indexName = identifier();
    } else {
      ;
    }
    jj_consume_token(LEFT_PAREN);
    if (groupFormat) {
      groupIndexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else if (!groupFormat) {
      indexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(RIGHT_PAREN);
    if (groupFormat && getToken(1).kind == USING) {
      jj_consume_token(USING);
      joinType = joinType();
      jj_consume_token(JOIN);
    } else {
      ;
    }
    switch (jj_nt.kind) {
    case AS:
      jj_consume_token(AS);
      location = getLocation();
      break;
    default:
      jj_la1[380] = jj_gen;
      ;
    }
        {if (true) return (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.INDEX_CONSTRAINT_NODE,
                                                             constraintName,
                                                             indexColumnList,
                                                             indexName,
                                                             joinType,
                                                             location,
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ConstraintDefinitionNode uniqueConstraintDefinition(TableName constraintName) throws ParseException, StandardException {
    ConstraintDefinitionNode.ConstraintType constraintType;
    ResultColumnList uniqueColumnList;
    //for table level constraint, parameter will be null
        constraintType = uniqueSpecification(null);
    jj_consume_token(LEFT_PAREN);
    uniqueColumnList = uniqueColumnList();
    jj_consume_token(RIGHT_PAREN);
        {if (true) return (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                             constraintName,
                                                             constraintType,
                                                             uniqueColumnList,
                                                             null,
                                                             null,
                                                             null,
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

//the second parameter to the following method will always be null for a table level
//constraint but not for a column level constraint
  final public ConstraintDefinitionNode.ConstraintType uniqueSpecification(String columnName) throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case UNIQUE:
      jj_consume_token(UNIQUE);
        {if (true) return ConstraintDefinitionNode.ConstraintType.UNIQUE;}
      break;
    case PRIMARY:
      jj_consume_token(PRIMARY);
      jj_consume_token(KEY);
        {if (true) return ConstraintDefinitionNode.ConstraintType.PRIMARY_KEY;}
      break;
    default:
      jj_la1[381] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ResultColumnList uniqueColumnList() throws ParseException, StandardException {
    ResultColumnList resultColumns = (ResultColumnList)
        nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                            parserContext);
    columnNameList(resultColumns);
        {if (true) return resultColumns;}
    throw new Error("Missing return statement in function");
  }

  final public ConstraintDefinitionNode referentialConstraintDefinition(TableName constraintName) throws ParseException, StandardException {
    ResultColumnList fkRcl = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                   parserContext);
    ResultColumnList refRcl = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                    parserContext);
    TableName referencedTable;
    int[] refActions = { StatementType.RA_NOACTION,
                         StatementType.RA_NOACTION }; //default values
    Token grouping = null;
    if (groupConstructFollows(GROUPING)) {
      grouping = jj_consume_token(GROUPING);
    } else {
      ;
    }
    jj_consume_token(FOREIGN);
    jj_consume_token(KEY);
    jj_consume_token(LEFT_PAREN);
    columnNameList(fkRcl);
    jj_consume_token(RIGHT_PAREN);
    referencedTable = referencesSpecification(refRcl, refActions);
        {if (true) return (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.FK_CONSTRAINT_DEFINITION_NODE,
                                                             constraintName,
                                                             referencedTable,
                                                             fkRcl,
                                                             refRcl,
                                                             refActions,
                                                             (grouping == null) ? Boolean.FALSE : Boolean.TRUE,
                                                             parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public TableName referencesSpecification(ResultColumnList rcl, int[] refActions) throws ParseException, StandardException {
    TableName tableName = null;
    jj_consume_token(REFERENCES);
    tableName = referencedTableAndColumns(rcl);
    switch (jj_nt.kind) {
    case ON:
      jj_consume_token(ON);
      referentialTriggeredAction(refActions);
      break;
    default:
      jj_la1[382] = jj_gen;
      ;
    }
        {if (true) return tableName;}
    throw new Error("Missing return statement in function");
  }

  final public TableName referencedTableAndColumns(ResultColumnList rcl) throws ParseException, StandardException {
    TableName    tableName = null;
    tableName = qualifiedName();
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      columnNameList(rcl);
      jj_consume_token(RIGHT_PAREN);
      break;
    default:
      jj_la1[383] = jj_gen;
      ;
    }
        {if (true) return tableName;}
    throw new Error("Missing return statement in function");
  }

  final public void referentialTriggeredAction(int [] refActions) throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case UPDATE:
      refActions[1] = updateActions();
      switch (jj_nt.kind) {
      case ON:
        jj_consume_token(ON);
        refActions[0] = deleteActions();
        break;
      default:
        jj_la1[384] = jj_gen;
        ;
      }
      break;
    case DELETE:
      refActions[0] = deleteActions();
      switch (jj_nt.kind) {
      case ON:
        jj_consume_token(ON);
        refActions[1] = updateActions();
        break;
      default:
        jj_la1[385] = jj_gen;
        ;
      }
      break;
    default:
      jj_la1[386] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public int updateActions() throws ParseException {
    int action;
    jj_consume_token(UPDATE);
    action = updateReferentialAction();
        {if (true) return action;}
    throw new Error("Missing return statement in function");
  }

  final public int deleteActions() throws ParseException {
    int action;
    jj_consume_token(DELETE);
    action = deleteReferentialAction();
        {if (true) return action;}
    throw new Error("Missing return statement in function");
  }

  final public int updateReferentialAction() throws ParseException {
    switch (jj_nt.kind) {
    case RESTRICT:
      jj_consume_token(RESTRICT);
        {if (true) return StatementType.RA_RESTRICT;}
      break;
    case NO:
      jj_consume_token(NO);
      jj_consume_token(ACTION);
        {if (true) return StatementType.RA_NOACTION;}
      break;
    default:
      jj_la1[387] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public int deleteReferentialAction() throws ParseException {
    switch (jj_nt.kind) {
    case CASCADE:
      jj_consume_token(CASCADE);
        {if (true) return StatementType.RA_CASCADE;}
      break;
    case RESTRICT:
      jj_consume_token(RESTRICT);
         {if (true) return StatementType.RA_RESTRICT;}
      break;
    case NO:
      jj_consume_token(NO);
      jj_consume_token(ACTION);
        {if (true) return StatementType.RA_NOACTION;}
      break;
    case SET:
      jj_consume_token(SET);
      switch (jj_nt.kind) {
      case NULL:
        jj_consume_token(NULL);
        {if (true) return StatementType.RA_SETNULL;}
        break;
      case _DEFAULT:
        jj_consume_token(_DEFAULT);
        {if (true) return StatementType.RA_SETDEFAULT;}
        break;
      default:
        jj_la1[388] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[389] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void columnConstraintDefinition(DataTypeDescriptor[] dataTypeDescriptor,
                           TableElementList tableElementList,
                           String columnName) throws ParseException, StandardException {
    int constraintType;
    TableElementNode tcdn;
    TableName constraintName = null;
    switch (jj_nt.kind) {
    case CONSTRAINT:
      constraintName = constraintNameDefinition();
      break;
    default:
      jj_la1[390] = jj_gen;
      ;
    }
    tcdn = columnConstraint(constraintName, dataTypeDescriptor, columnName);
        /* NOT NULL constraints are handled by marking the dataTypeDescriptor
         * as being non-nullable.
         */
        if (tcdn == null) {
            {if (true) return;}
        }

        /* All other constraints, whether column or table will be added as
         * table constraints.    We do this to facilitate the handling of
         * multiple column constraints on the same column.
         */
        tableElementList.addTableElement(tcdn);
  }

  final public ConstraintDefinitionNode columnConstraint(TableName constraintName,
                 DataTypeDescriptor[] dataTypeDescriptor,
                 String columnName) throws ParseException, StandardException {
    Token notNull = null;
    ConstraintDefinitionNode.ConstraintType constraintType;
    Properties properties = null;
    ConstraintDefinitionNode tcdn;
    ResultColumnList refRcl = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                    parserContext);
    TableName referencedTable;
    int[] refActions = {StatementType.RA_NOACTION,
                        StatementType.RA_NOACTION} ; //default: NO ACTION
    Token grouping = null;
    switch (jj_nt.kind) {
    case NOT:
    case NULL:
      switch (jj_nt.kind) {
      case NOT:
        notNull = jj_consume_token(NOT);
        break;
      default:
        jj_la1[391] = jj_gen;
        ;
      }
      jj_consume_token(NULL);
        if ( dataTypeDescriptor[0] == null ) {
            {if (true) throw new StandardException("[NOT] NULL requires a data type");}
        }
        dataTypeDescriptor[0] = dataTypeDescriptor[0].getNullabilityType(notNull == null);
        {if (true) return null;}
      break;
    case PRIMARY:
    case UNIQUE:
      //pass the columnname as the second parameter. It will be used to throw an
          //exception if null constraint is defined for this column-level primary 
          //key constraint
          constraintType = uniqueSpecification(columnName);
      switch (jj_nt.kind) {
      case DERBYDASHPROPERTIES:
        properties = propertyList(false);
        jj_consume_token(CHECK_PROPERTIES);
        break;
      default:
        jj_la1[392] = jj_gen;
        ;
      }
        ResultColumnList uniqueColumnList = (ResultColumnList)
            nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                parserContext);
        uniqueColumnList.addResultColumn((ResultColumn)
                                         nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                             columnName,
                                                             null,
                                                             parserContext));

        {if (true) return (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                             constraintName,
                                                             constraintType,
                                                             uniqueColumnList,
                                                             properties,
                                                             null,
                                                             null,
                                                             parserContext);}
      break;
    default:
      jj_la1[394] = jj_gen;
      if (jj_2_100(1)) {
        if (groupConstructFollows(GROUPING)) {
          grouping = jj_consume_token(GROUPING);
        } else {
          ;
        }
        referencedTable = referencesSpecification(refRcl, refActions);
        switch (jj_nt.kind) {
        case DERBYDASHPROPERTIES:
          properties = propertyList(false);
          jj_consume_token(CHECK_PROPERTIES);
          break;
        default:
          jj_la1[393] = jj_gen;
          ;
        }
        ResultColumnList fkRcl = (ResultColumnList)
            nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                parserContext);
        fkRcl.addResultColumn((ResultColumn)nodeFactory.getNode(NodeTypes.RESULT_COLUMN,
                                                                columnName,
                                                                null,
                                                                parserContext));
        tcdn = (ConstraintDefinitionNode)nodeFactory.getNode(NodeTypes.FK_CONSTRAINT_DEFINITION_NODE,
                                                             constraintName,
                                                             referencedTable,
                                                             fkRcl,
                                                             refRcl,
                                                             refActions,
                                                             (grouping == null) ? Boolean.FALSE : Boolean.TRUE,
                                                             parserContext);
        if (properties != null) {
            tcdn.setProperties(properties);
        }
        {if (true) return tcdn;}
      } else {
        switch (jj_nt.kind) {
        case CHECK:
          tcdn = checkConstraintDefinition(constraintName, columnName);
        {if (true) return tcdn;}
          break;
        default:
          jj_la1[395] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropRoleStatement() throws ParseException, StandardException {
    String roleName;
    jj_consume_token(ROLE);
    roleName = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_ROLE_NODE,
                                                  roleName,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropSchemaStatement() throws ParseException, StandardException {
    String schemaName;
    int[] behavior = new int[1];
    ExistenceCheck cond;
    jj_consume_token(SCHEMA);
    cond = dropCondition();
    schemaName = identifier();
    dropBehavior(behavior);
        StatementNode stmt = (StatementNode)nodeFactory.getNode(NodeTypes.DROP_SCHEMA_NODE,
                                                                schemaName,
                                                                new Integer(behavior[0]),
                                                                cond,
                                                                parserContext);

        {if (true) return stmt;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode alterGroup() throws ParseException, StandardException {
    TableName childTable;
    TableName parentTable;
    ResultColumnList childColumns;
    ResultColumnList parentColumns = null;
    TableElementList tableElementList = (TableElementList)
        nodeFactory.getNode(NodeTypes.TABLE_ELEMENT_LIST, parserContext);
    switch (jj_nt.kind) {
    case ADD:
      jj_consume_token(ADD);
      jj_consume_token(TABLE);
      childTable = qualifiedName();
      jj_consume_token(LEFT_PAREN);
      childColumns = tableColumnList();
      jj_consume_token(RIGHT_PAREN);
      jj_consume_token(TO);
      parentTable = qualifiedName();
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        parentColumns = tableColumnList();
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[396] = jj_gen;
        ;
      }
        // NB: Must be kept in sync with referentialConstraintDefinition()
        // Equivalent to this statement:
        // ALTER TABLE <child schema table name>
        //      ADD GROUPING FOREIGN KEY ( <column name> [ , ... n ] )
        //          REFERENCES <parent schema table name> [ ( <column name> [ , ... n ] ) ]

        tableElementList.addTableElement((TableElementNode)
            nodeFactory.getNode(NodeTypes.FK_CONSTRAINT_DEFINITION_NODE,
                                 null,
                                 parentTable,
                                 childColumns,
                                 parentColumns,
                                 new int[] {StatementType.RA_NOACTION,StatementType.RA_NOACTION},
                                 Boolean.TRUE,
                                 parserContext));

        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  childTable,
                                                  tableElementList,
                                                  new Character('\u005c0'),
                                                  new int[] {DDLStatementNode.ADD_TYPE},
                                                  new int[]{0}, // not used
                                                  parserContext);}
      break;
    case DROP:
      jj_consume_token(DROP);
      jj_consume_token(TABLE);
      childTable = qualifiedName();
        // NB: Must be kept in sync with dropTableConstraintDefinition()
        // Equivalent to this statement:
        // ALTER TABLE <leaf schema table name> DROP GROUPING FOREIGN KEY

        tableElementList.addTableElement((TableElementNode)
            nodeFactory.getNode(NodeTypes.FK_CONSTRAINT_DEFINITION_NODE,
                                null,
                                ConstraintDefinitionNode.ConstraintType.DROP,
                                StatementType.DROP_DEFAULT,
                                Boolean.TRUE,
                                parserContext));

         {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                   childTable,
                                                   tableElementList,
                                                   new Character('\u005c0'),
                                                   new int[]{DDLStatementNode.DROP_TYPE},
                                                   new int[]{StatementType.DROP_DEFAULT},
                                                   parserContext);}
      break;
    default:
      jj_la1[397] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode alterStatement() throws ParseException, StandardException {
    StatementNode node;
    TableName tableName;
    switch (jj_nt.kind) {
    case TABLE:
      jj_consume_token(TABLE);
      tableName = qualifiedName();
      node = alterTableBody(tableName);
        {if (true) return node;}
      break;
    case SERVER:
      jj_consume_token(SERVER);
      node = alterServerBody();
        {if (true) return node;}
      break;
    case GROUP:
      jj_consume_token(GROUP);
      node = alterGroup();
        {if (true) return node;}
      break;
    default:
      jj_la1[398] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode alterServerBody() throws ParseException, StandardException {
    StatementNode sn;
    ValueNode sessionID;
    Token immediate = null;
    Token interrupt = null;
    Token disconnect = null;
    Token kill = null;
    switch (jj_nt.kind) {
    case SET:
      jj_consume_token(SET);
      sn = setConfigurationStatement();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_SERVER_NODE,
                                                sn,
                                                parserContext);}
      break;
    case SHUTDOWN:
      jj_consume_token(SHUTDOWN);
      switch (jj_nt.kind) {
      case IMMEDIATE:
        immediate = jj_consume_token(IMMEDIATE);
        break;
      default:
        jj_la1[399] = jj_gen;
        ;
      }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_SERVER_NODE,
                                                 new Boolean(immediate != null),
                                                 parserContext);}
      break;
    case DISCONNECT:
    case INTERRUPT:
    case KILL:
      switch (jj_nt.kind) {
      case INTERRUPT:
        interrupt = jj_consume_token(INTERRUPT);
        break;
      case DISCONNECT:
        disconnect = jj_consume_token(DISCONNECT);
        break;
      case KILL:
        kill = jj_consume_token(KILL);
        break;
      default:
        jj_la1[400] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      jj_consume_token(SESSION);
      sessionID = alterSessionID();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_SERVER_NODE,
                                                interrupt,
                                                disconnect,
                                                kill,
                                                sessionID,
                                                parserContext);}
      break;
    default:
      jj_la1[401] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public ValueNode alterSessionID() throws ParseException, StandardException {
    ValueNode valueNode;
    switch (jj_nt.kind) {
    case PLUS_SIGN:
    case MINUS_SIGN:
    case EXACT_NUMERIC:
      valueNode = intLiteral();
        {if (true) return valueNode;}
      break;
    case ALL:
      jj_consume_token(ALL);
        {if (true) return (ValueNode)nodeFactory.getNode(NodeTypes.DEFAULT_NODE,
                                              null,
                                              parserContext);}
      break;
    default:
      jj_la1[402] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode alterTableBody(TableName tableName) throws ParseException, StandardException {
    StatementNode sn;
    char lockGranularity = '\u005c0';
    String newTableName;
    TableElementList tableElementList = (TableElementList)
        nodeFactory.getNode(NodeTypes.TABLE_ELEMENT_LIST, parserContext);
    int[] changeType = new int[1];
    int[] behavior = new int[1];
    String indexName = null;
    switch (jj_nt.kind) {
    case COMPRESS:
      jj_consume_token(COMPRESS);
      switch (jj_nt.kind) {
      case INPLACE:
        sn = inplaceCompress(tableName);
        break;
      default:
        jj_la1[403] = jj_gen;
        sn = sequentialCompress(tableName);
      }
        {if (true) return sn;}
      break;
    case ALL:
      jj_consume_token(ALL);
      jj_consume_token(UPDATE);
      jj_consume_token(STATISTICS);
        //This will make sure that this ALTER TABLE...syntax can't be called directly.
        //This sql can only be generated internally (right now it is done for
        //syscs_util.SYSCS_UPDATE_STATISTICS procedure
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  Boolean.TRUE,
                                                  null,
                                                  parserContext);}
      break;
    case UPDATE:
      jj_consume_token(UPDATE);
      jj_consume_token(STATISTICS);
      indexName = identifier();
        //This will make sure that this ALTER TABLE...syntax can't be called directly.
        //This sql can only be generated internally (right now it is done for
        //syscs_util.SYSCS_UPDATE_STATISTICS procedure
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  Boolean.FALSE,
                                                  indexName,
                                                  parserContext);}
      break;
    case ADD:
    case ALTER:
    case DROP:
    case RENAME:
      alterTableAction(tableElementList, changeType, behavior);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  tableElementList,
                                                  new Character(lockGranularity), // Not used, but we kind of nned it
                                                  changeType,                     // to make the method take 5 args
                                                  behavior,                       // The one that takes 4 args does
                                                  parserContext);}                 // something else!

      break;
    default:
      jj_la1[404] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode inplaceCompress(TableName tableName) throws ParseException, StandardException {
    Token purge = null;
    Token defragment = null;
    Token truncate = null;
    jj_consume_token(INPLACE);
    switch (jj_nt.kind) {
    case PURGE:
      purge = jj_consume_token(PURGE);
      break;
    default:
      jj_la1[405] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case DEFRAGMENT:
      defragment = jj_consume_token(DEFRAGMENT);
      break;
    default:
      jj_la1[406] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case TRUNCATE_END:
      truncate = jj_consume_token(TRUNCATE_END);
      break;
    default:
      jj_la1[407] = jj_gen;
      ;
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  new Boolean(purge != null),
                                                  new Boolean(defragment != null),
                                                  new Boolean(truncate != null),
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode sequentialCompress(TableName tableName) throws ParseException, StandardException {
    Token tok = null;
    switch (jj_nt.kind) {
    case SEQUENTIAL:
      tok = jj_consume_token(SEQUENTIAL);
      break;
    default:
      jj_la1[408] = jj_gen;
      ;
    }
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  new Boolean(tok != null),
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void alterTableAction(TableElementList tableElementList, int[] changeType, int[] behavior) throws ParseException, StandardException {
    TableElementNode tableElement;
    boolean hasAutoIncrement = false;
    DataTypeDescriptor  typeDescriptor;
    Token tok = null;
    String name;
    String newCName;
    TableName newName;
    long[] autoIncrementInfo = new long[4];
    switch (jj_nt.kind) {
    case ADD:
      jj_consume_token(ADD);
      if (getToken(1).kind == UNIQUE && getToken(2).kind == INDEX || getToken(1).kind == INDEX) {
        addIndex(tableElementList);
      } else if (jj_2_101(1)) {
        hasAutoIncrement = addColumnDefinition(tableElementList);
      } else if (jj_2_102(1)) {
        tableConstraintDefinition(tableElementList);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
        if (hasAutoIncrement)
            //bug 5724 - auto increment columns not allowed in ALTER TABLE statement
            {if (true) throw new StandardException("Auto increment column not allowed in ALTER TABLE");}

        changeType[0] = DDLStatementNode.ADD_TYPE;
      break;
    case ALTER:
      jj_consume_token(ALTER);
      switch (jj_nt.kind) {
      case COLUMN:
        jj_consume_token(COLUMN);
        break;
      default:
        jj_la1[409] = jj_gen;
        ;
      }
      name = identifier();
      tableElement = columnAlterClause(name);
        changeType[0] = DDLStatementNode.MODIFY_TYPE;
        tableElementList.addTableElement(tableElement);
      break;
    case DROP:
      jj_consume_token(DROP);
      if (getToken(1).kind == INDEX || getToken(1).kind == KEY) {
        tableElement = dropIndex();
      } else if (jj_2_103(1)) {
        tableElement = dropColumnDefinition(behavior);
      } else if (jj_2_104(1)) {
        tableElement = dropTableConstraintDefinition();
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
        changeType[0] = DDLStatementNode.DROP_TYPE;
        tableElementList.addTableElement(tableElement);
      break;
    case RENAME:
      jj_consume_token(RENAME);
      if (getToken(1).kind == COLUMN) {
        jj_consume_token(COLUMN);
        name = identifier();
        switch (jj_nt.kind) {
        case AS:
        case TO:
          switch (jj_nt.kind) {
          case TO:
            jj_consume_token(TO);
            break;
          case AS:
            jj_consume_token(AS);
            break;
          default:
            jj_la1[410] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
          break;
        default:
          jj_la1[411] = jj_gen;
          ;
        }
        newCName = identifier();
            tableElement = (TableElementNode)nodeFactory.getNode(NodeTypes.AT_RENAME_COLUMN_NODE,
                                                                 name,
                                                                 newCName,
                                                                 parserContext);
      } else if (jj_2_105(1)) {
        switch (jj_nt.kind) {
        case TABLE:
          jj_consume_token(TABLE);
          break;
        default:
          jj_la1[412] = jj_gen;
          ;
        }
        switch (jj_nt.kind) {
        case AS:
        case TO:
          switch (jj_nt.kind) {
          case TO:
            jj_consume_token(TO);
            break;
          case AS:
            jj_consume_token(AS);
            break;
          default:
            jj_la1[413] = jj_gen;
            jj_consume_token(-1);
            throw new ParseException();
          }
          break;
        default:
          jj_la1[414] = jj_gen;
          ;
        }
        newName = qualifiedName();
            tableElement = (TableElementNode)nodeFactory.getNode(NodeTypes.AT_RENAME_NODE,
                                                                 newName,
                                                                 parserContext);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
        changeType[0] = DDLStatementNode.MODIFY_TYPE;
        tableElementList.addTableElement(tableElement);
      break;
    default:
      jj_la1[415] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

/*
 * Handle
 *
 *      ALTER TABLE tablename DROP [ COLUMN ] columnname [ CASCADE | RESTRICT ]
 */
  final public TableElementNode dropColumnDefinition(int []behavior) throws ParseException, StandardException {
    String columnName;
    TableElementNode tableElement;
    switch (jj_nt.kind) {
    case COLUMN:
      jj_consume_token(COLUMN);
      break;
    default:
      jj_la1[416] = jj_gen;
      ;
    }
    columnName = identifier();
    dropBehavior(behavior);
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.DROP_COLUMN_NODE,
                                                     columnName, null,
                                                     null, null,
                                                     parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public TableElementNode dropIndex() throws ParseException, StandardException {
    String indexName;
    ExistenceCheck cond;
    switch (jj_nt.kind) {
    case INDEX:
      jj_consume_token(INDEX);
      break;
    case KEY:
      jj_consume_token(KEY);
      break;
    default:
      jj_la1[417] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    cond = dropCondition();
    indexName = identifier();
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.AT_DROP_INDEX_NODE,
                                                     indexName, // name
                                                     cond,      // existence 
                                                     parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StorageLocation getLocation() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case IN_MEMORY:
      jj_consume_token(IN_MEMORY);
        {if (true) return StorageLocation.IN_MEMORY;}
      break;
    case BTREE:
      jj_consume_token(BTREE);
        {if (true) return StorageLocation.BTREE;}
      break;
    default:
      jj_la1[418] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public void addIndex(TableElementList tableElementList) throws ParseException, StandardException {
    boolean groupFormat = hasFeature(SQLParserFeature.GROUPING);

    ExistenceCheck cond;
    Boolean unique = Boolean.FALSE;
    String indexName;
    IndexColumnList indexColumnList = null;
    JoinNode.JoinType joinType = null;
    Properties properties = null;
    StorageLocation location = null;
    switch (jj_nt.kind) {
    case UNIQUE:
      unique = unique();
      break;
    default:
      jj_la1[419] = jj_gen;
      ;
    }
    jj_consume_token(INDEX);
    cond = createCondition();
    indexName = identifier();
    jj_consume_token(LEFT_PAREN);
    if (groupFormat) {
      groupIndexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else if (!groupFormat) {
      indexItemList(indexColumnList = (IndexColumnList)nodeFactory.getNode(NodeTypes.INDEX_COLUMN_LIST, parserContext));
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    jj_consume_token(RIGHT_PAREN);
    if (groupFormat && getToken(1).kind == USING) {
      jj_consume_token(USING);
      joinType = joinType();
      jj_consume_token(JOIN);
    } else {
      ;
    }
    switch (jj_nt.kind) {
    case DERBYDASHPROPERTIES:
      properties = propertyList(false);
      jj_consume_token(CHECK_PROPERTIES);
      break;
    default:
      jj_la1[420] = jj_gen;
      ;
    }
    switch (jj_nt.kind) {
    case AS:
      jj_consume_token(AS);
      location = getLocation();
      break;
    default:
      jj_la1[421] = jj_gen;
      ;
    }
        tableElementList.addTableElement((TableElementNode)nodeFactory.getNode
                                                                (NodeTypes.AT_ADD_INDEX_NODE,
                                                                 cond,
                                                                 unique,
                                                                 indexName,
                                                                 indexColumnList,
                                                                 joinType,
                                                                 properties,
                                                                 location,
                                                                 parserContext));
  }

  final public void dropBehavior(int[] behavior) throws ParseException {
    int refBehavior = StatementType.DROP_DEFAULT;
    switch (jj_nt.kind) {
    case CASCADE:
    case RESTRICT:
      switch (jj_nt.kind) {
      case CASCADE:
        jj_consume_token(CASCADE);
            refBehavior = StatementType.DROP_CASCADE;
        break;
      case RESTRICT:
        jj_consume_token(RESTRICT);
            refBehavior = StatementType.DROP_RESTRICT;
        break;
      default:
        jj_la1[422] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[423] = jj_gen;
      ;
    }
        behavior[0] = refBehavior;
  }

  final public boolean addColumnDefinition(TableElementList tableElementList) throws ParseException, StandardException {
    boolean autoIncre;
    boolean hasAutoIncre;
    switch (jj_nt.kind) {
    case COLUMN:
      jj_consume_token(COLUMN);
      break;
    default:
      jj_la1[424] = jj_gen;
      ;
    }
    hasAutoIncre = columnDefinition(tableElementList);
    label_50:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[425] = jj_gen;
        break label_50;
      }
      jj_consume_token(COMMA);
      if (notAlterActionFollows()) {

      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
      autoIncre = columnDefinition(tableElementList);
                                                        hasAutoIncre |= autoIncre;
    }
        {if (true) return hasAutoIncre;}
    throw new Error("Missing return statement in function");
  }

/*
 * Various variants of the ALTER TABLE ALTER COLUMN statement.
 *
 * By the type we get here, we've parsed
 *      ALTER TABLE tablename ALTER [COLUMN] columnname
 * and here we parse the remainder of the ALTER COLUMN clause, one of:
 *      SET DATA TYPE data_type
 *      SET INCREMENT BY increment_value
 *      RESTART WITH increment_restart_value
 *      [SET | WITH] DEFAULT default_value
 *          DROP DEFAULT
 *          [NOT] NULL
 */
  final public TableElementNode columnAlterClause(String columnName) throws ParseException, StandardException {
    ValueNode    defaultNode;
    long[] autoIncrementInfo = new long[4];
    long autoIncrementIncrement = 1;
    long autoIncrementRestartWith = 1;
    DataTypeDescriptor typeDescriptor = null;
    if (getToken(2).kind == DATA) {
      jj_consume_token(SET);
      jj_consume_token(DATA);
      jj_consume_token(TYPE);
      typeDescriptor = dataTypeDDL();
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_TYPE_NODE,
                                                     columnName, null,
                                                     typeDescriptor, null,
                                                     parserContext);}
    } else if (getToken(2).kind == INCREMENT) {
      jj_consume_token(SET);
      jj_consume_token(INCREMENT);
      jj_consume_token(BY);
      autoIncrementIncrement = exactNumber();
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_INC_INDEX] = autoIncrementIncrement;
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_CREATE_MODIFY] = ColumnDefinitionNode.MODIFY_AUTOINCREMENT_INC_VALUE;
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_DEFAULT_NODE,
                                                     columnName,
                                                     null, null, autoIncrementInfo,
                                                     parserContext);}
    } else {
      switch (jj_nt.kind) {
      case RESTART:
        jj_consume_token(RESTART);
        jj_consume_token(WITH);
        autoIncrementRestartWith = exactNumber();
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_START_INDEX] = autoIncrementRestartWith;
        autoIncrementInfo[QueryTreeNode.AUTOINCREMENT_CREATE_MODIFY] = ColumnDefinitionNode.MODIFY_AUTOINCREMENT_RESTART_VALUE;
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_DEFAULT_NODE,
                                                     columnName,
                                                     null, null, autoIncrementInfo,
                                                     parserContext);}
        break;
      default:
        jj_la1[427] = jj_gen;
        if (getToken(1).kind == WITH || getToken(1).kind == _DEFAULT ||
                         getToken(1).kind == GENERATED ||
                        (getToken(1).kind == SET && (getToken(2).kind == _DEFAULT ||
                                                     getToken(2).kind == GENERATED))) {
          switch (jj_nt.kind) {
          case SET:
            jj_consume_token(SET);
            break;
          default:
            jj_la1[426] = jj_gen;
            ;
          }
          defaultNode = defaultClause(autoIncrementInfo, columnName);
        {if (true) return wrapAlterColumnDefaultValue(defaultNode,
                                           columnName,
                                           autoIncrementInfo);}
        } else {
          switch (jj_nt.kind) {
          case DROP:
            jj_consume_token(DROP);
            jj_consume_token(_DEFAULT);
        defaultNode = (ValueNode)nodeFactory.getNode(NodeTypes.UNTYPED_NULL_CONSTANT_NODE, parserContext);

        {if (true) return wrapAlterColumnDefaultValue(defaultNode,
                                           columnName,
                                           autoIncrementInfo);}
            break;
          default:
            jj_la1[428] = jj_gen;
            if (getToken(1).kind == NULL) {
              jj_consume_token(NULL);
        // for a MODIFY column NULL clause form a modify_column node
        // with all null values. In a column definition a [NOT] NULL
        // column constraint is specified by setting the right value
        // in the nullability field of the data type but we don't have
        // a datatype here.
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_CONSTRAINT_NODE,
                                                     columnName, null, null, null,
                                                     parserContext);}
            } else if (getToken(1).kind == NOT) {
              jj_consume_token(NOT);
              jj_consume_token(NULL);
        // for a MODIFY column NOT NULL clause form a modify_column node
        // with all null values. In a column definition a [NOT] NULL
        // column constraint is specified by setting the right value
        // in the nullability field of the data type but we don't have
        // a datatype here.
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.MODIFY_COLUMN_CONSTRAINT_NOT_NULL_NODE,
                                                     columnName, null, null, null,
                                                     parserContext);}
            } else {
              jj_consume_token(-1);
              throw new ParseException();
            }
          }
        }
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public TableElementNode dropTableConstraintDefinition() throws ParseException, StandardException {
    TableName constraintName = null;
    Token grouping = null;
    if (getToken(1).kind == CONSTRAINT) {
      jj_consume_token(CONSTRAINT);
      constraintName = qualifiedName();
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                     constraintName,
                                                     ConstraintDefinitionNode.ConstraintType.DROP,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     StatementType.DROP_DEFAULT,
                                                     parserContext);}
    } else if (getToken(1).kind == PRIMARY) {
      jj_consume_token(PRIMARY);
      jj_consume_token(KEY);
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                     null,
                                                     ConstraintDefinitionNode.ConstraintType.DROP,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     StatementType.DROP_DEFAULT,
                                                     ConstraintDefinitionNode.ConstraintType.PRIMARY_KEY,
                                                     parserContext);}
    } else if ((getToken(1).kind == FOREIGN) ||
                     (groupConstructFollows(GROUPING) && (getToken(2).kind == FOREIGN))) {
      switch (jj_nt.kind) {
      case GROUPING:
        grouping = jj_consume_token(GROUPING);
        break;
      default:
        jj_la1[429] = jj_gen;
        ;
      }
      jj_consume_token(FOREIGN);
      jj_consume_token(KEY);
      if (jj_2_106(1)) {
        constraintName = qualifiedName();
      } else {
        ;
      }
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.FK_CONSTRAINT_DEFINITION_NODE,
                                                     constraintName,
                                                     ConstraintDefinitionNode.ConstraintType.DROP,
                                                     StatementType.DROP_DEFAULT,
                                                     (grouping == null) ? Boolean.FALSE : Boolean.TRUE,
                                                     parserContext);}
    } else if (getToken(1).kind == UNIQUE) {
      jj_consume_token(UNIQUE);
      constraintName = qualifiedName();
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                     constraintName,
                                                     ConstraintDefinitionNode.ConstraintType.DROP,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     StatementType.DROP_DEFAULT,
                                                     ConstraintDefinitionNode.ConstraintType.UNIQUE,
                                                     parserContext);}
    } else {
      switch (jj_nt.kind) {
      case CHECK:
        jj_consume_token(CHECK);
        constraintName = qualifiedName();
        {if (true) return (TableElementNode)nodeFactory.getNode(NodeTypes.CONSTRAINT_DEFINITION_NODE,
                                                     constraintName,
                                                     ConstraintDefinitionNode.ConstraintType.DROP,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     StatementType.DROP_DEFAULT,
                                                     ConstraintDefinitionNode.ConstraintType.CHECK,
                                                     parserContext);}
        break;
      default:
        jj_la1[430] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropTableStatement() throws ParseException, StandardException {
    TableName tableName;
    int[] behavior = new int[1];
    ExistenceCheck cond;
    jj_consume_token(TABLE);
    cond = dropCondition();
    tableName = qualifiedName();
    dropBehavior(behavior);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_TABLE_NODE,
                                                  tableName,
                                                  new Integer(behavior[0]),
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropIndexStatement() throws ParseException, StandardException {
    String indexName;
    TableName tableName[] = new TableName[1];
    ExistenceCheck cond;
    jj_consume_token(INDEX);
    cond = dropCondition();
    indexName = indexName(tableName);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_INDEX_NODE,
                                                  tableName[0],
                                                  indexName,
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropAliasStatement() throws ParseException, StandardException {
    TableName aliasName;
    ExistenceCheck cond;
    switch (jj_nt.kind) {
    case PROCEDURE:
      jj_consume_token(PROCEDURE);
      cond = dropCondition();
      aliasName = qualifiedName();
        {if (true) return dropAliasNode(aliasName, AliasInfo.Type.PROCEDURE, cond);}
      break;
    case FUNCTION:
      jj_consume_token(FUNCTION);
      cond = dropCondition();
      aliasName = qualifiedName();
        {if (true) return dropAliasNode(aliasName, AliasInfo.Type.FUNCTION, cond);}
      break;
    case SYNONYM:
      jj_consume_token(SYNONYM);
      cond = dropCondition();
      aliasName = qualifiedName();
        {if (true) return dropAliasNode(aliasName, AliasInfo.Type.SYNONYM, cond);}
      break;
    case TYPE:
      jj_consume_token(TYPE);
      cond = dropCondition();
      aliasName = qualifiedName();
      jj_consume_token(RESTRICT);
        {if (true) return dropAliasNode(aliasName, AliasInfo.Type.UDT, cond);}
      break;
    default:
      jj_la1[431] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropViewStatement() throws ParseException, StandardException {
    TableName viewName;
    ExistenceCheck cond;
    jj_consume_token(VIEW);
    cond = dropCondition();
    viewName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_VIEW_NODE,
                                                  viewName,
                                                  cond,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropGroupStatement() throws ParseException, StandardException {
    TableName groupName;
    ExistenceCheck cond;
    jj_consume_token(GROUP);
    cond = dropCondition();
    groupName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_GROUP_NODE,
                                                   groupName,
                                                   cond,
                                                   parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode dropTriggerStatement() throws ParseException, StandardException {
    TableName triggerName;
    jj_consume_token(TRIGGER);
    triggerName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DROP_TRIGGER_NODE,
                                                  triggerName,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode truncateStatement() throws ParseException, StandardException {
    StatementNode statementNode;
    jj_consume_token(TRUNCATE);
    statementNode = truncateTableStatement();
        {if (true) return statementNode;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode truncateTableStatement() throws ParseException, StandardException {
    TableName tableName;
    jj_consume_token(TABLE);
    tableName = qualifiedName();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.ALTER_TABLE_NODE,
                                                  tableName,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode grantStatement() throws ParseException, StandardException {
    StatementNode node;
    if (getToken(1).kind == GRANT &&
                                     ((getToken(2).kind == TRIGGER &&
                                         ((getToken(3).kind == COMMA &&
                                             isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                            getToken(3).kind == ON)) ||
                                        isPrivilegeKeywordExceptTrigger(getToken(2).kind))) {
      jj_consume_token(GRANT);
      switch (jj_nt.kind) {
      case ALL:
      case DELETE:
      case INSERT:
      case REFERENCES:
      case SELECT:
      case UPDATE:
      case TRIGGER:
        node = tableGrantStatement();
        break;
      case EXECUTE:
        node = routineGrantStatement();
        break;
      case USAGE:
        node = usageGrantStatement();
        break;
      default:
        jj_la1[432] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return node;}
    } else if (getToken(1).kind == GRANT &&
                                     ((getToken(2).kind == TRIGGER &&
                                         ((getToken(3).kind == COMMA &&
                                             !isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                            getToken(3).kind == TO)) ||
                                        !isPrivilegeKeywordExceptTrigger(getToken(2).kind))) {
      jj_consume_token(GRANT);
      node = roleGrantStatement();
        {if (true) return node;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode tableGrantStatement() throws ParseException, StandardException {
 PrivilegeNode privileges;
 List grantees;
    privileges = tablePrivileges();
    jj_consume_token(TO);
    grantees = granteeList();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.GRANT_NODE,
                                                  privileges, grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public PrivilegeNode tablePrivileges() throws ParseException, StandardException {
    TablePrivilegesNode tablePrivilegesNode = null;
    TableName objectName = null;
    tablePrivilegesNode = tableActions();
    jj_consume_token(ON);
    switch (jj_nt.kind) {
    case TABLE:
      jj_consume_token(TABLE);
      break;
    default:
      jj_la1[433] = jj_gen;
      ;
    }
    objectName = qualifiedName();
        {if (true) return (PrivilegeNode)nodeFactory.getNode(NodeTypes.PRIVILEGE_NODE,
                                                  PrivilegeNode.ObjectType.TABLE_PRIVILEGES,
                                                  objectName, tablePrivilegesNode,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public TablePrivilegesNode tableActions() throws ParseException, StandardException {
    TablePrivilegesNode tableActionsNode = (TablePrivilegesNode)
        nodeFactory.getNode(NodeTypes.TABLE_PRIVILEGES_NODE, parserContext);
    switch (jj_nt.kind) {
    case ALL:
      jj_consume_token(ALL);
      jj_consume_token(PRIVILEGES);
        tableActionsNode.addAll();
        {if (true) return tableActionsNode;}
      break;
    case DELETE:
    case INSERT:
    case REFERENCES:
    case SELECT:
    case UPDATE:
    case TRIGGER:
      tableAction(tableActionsNode);
      label_51:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[434] = jj_gen;
          break label_51;
        }
        jj_consume_token(COMMA);
        tableAction(tableActionsNode);
      }
        {if (true) return tableActionsNode;}
      break;
    default:
      jj_la1[435] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode routineGrantStatement() throws ParseException, StandardException {
    List grantees;
    RoutineDesignator routine;
    jj_consume_token(EXECUTE);
    jj_consume_token(ON);
    routine = routineDesignator();
    jj_consume_token(TO);
    grantees = granteeList();
        PrivilegeNode routinePrivilege = (PrivilegeNode)
                    nodeFactory.getNode(NodeTypes.PRIVILEGE_NODE,
                                        PrivilegeNode.ObjectType.ROUTINE_PRIVILEGES,
                                        routine, null,
                                        parserContext);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.GRANT_NODE,
                                                  routinePrivilege, grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode usageGrantStatement() throws ParseException, StandardException {
    List grantees;
    TableName name;
    PrivilegeNode.ObjectType objectType;
    jj_consume_token(USAGE);
    jj_consume_token(ON);
    objectType = usableObjects();
    name = qualifiedName();
    jj_consume_token(TO);
    grantees = granteeList();
                PrivilegeNode privilegeNode = (PrivilegeNode)nodeFactory.getNode(NodeTypes.PRIVILEGE_NODE,
                                                                                 objectType,
                                                                                 name,
                                                                                 PrivilegeNode.USAGE_PRIV,
                                                                                 Boolean.FALSE,
                                                                                 parserContext);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.GRANT_NODE,
                                                  privilegeNode,
                                                  grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public PrivilegeNode.ObjectType usableObjects() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case SEQUENCE:
      jj_consume_token(SEQUENCE);
        {if (true) return PrivilegeNode.ObjectType.SEQUENCE_PRIVILEGES;}
      break;
    case TYPE:
      jj_consume_token(TYPE);
        {if (true) return PrivilegeNode.ObjectType.UDT_PRIVILEGES;}
      break;
    default:
      jj_la1[436] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public RoutineDesignator routineDesignator() throws ParseException, StandardException {
 Token procOrFunction;
 TableName name;
 List paramTypeList = null;
    switch (jj_nt.kind) {
    case FUNCTION:
      procOrFunction = jj_consume_token(FUNCTION);
      break;
    case PROCEDURE:
      procOrFunction = jj_consume_token(PROCEDURE);
      break;
    default:
      jj_la1[437] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    name = qualifiedName();
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      jj_consume_token(LEFT_PAREN);
      paramTypeList = parameterTypeList();
      jj_consume_token(RIGHT_PAREN);
      break;
    default:
      jj_la1[438] = jj_gen;
      ;
    }
        {if (true) return new RoutineDesignator(false,
                                     name,
                                     (procOrFunction.kind == FUNCTION),
                                     paramTypeList);}
    throw new Error("Missing return statement in function");
  }

  final public List<DataTypeDescriptor> parameterTypeList() throws ParseException, StandardException {
    List<DataTypeDescriptor> list = new ArrayList<DataTypeDescriptor>();
    DataTypeDescriptor type;
    if (jj_2_107(1)) {
      type = catalogType();
            list.add(type);
      label_52:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[439] = jj_gen;
          break label_52;
        }
        jj_consume_token(COMMA);
        type = catalogType();
         list.add(type);
      }
    } else {
      ;
    }
         {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void tableAction(TablePrivilegesNode tablePrivilegesNode) throws ParseException, StandardException {
    ResultColumnList columnList = null;
    switch (jj_nt.kind) {
    case SELECT:
      jj_consume_token(SELECT);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        columnList = privilegeColumnList();
        break;
      default:
        jj_la1[440] = jj_gen;
        ;
      }
        tablePrivilegesNode.addAction(TablePrivilegesNode.SELECT_ACTION, columnList);
      break;
    case DELETE:
      jj_consume_token(DELETE);
        tablePrivilegesNode.addAction(TablePrivilegesNode.DELETE_ACTION, (ResultColumnList)null);
      break;
    case INSERT:
      jj_consume_token(INSERT);
        tablePrivilegesNode.addAction(TablePrivilegesNode.INSERT_ACTION, (ResultColumnList)null);
      break;
    case UPDATE:
      jj_consume_token(UPDATE);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        columnList = privilegeColumnList();
        break;
      default:
        jj_la1[441] = jj_gen;
        ;
      }
        tablePrivilegesNode.addAction(TablePrivilegesNode.UPDATE_ACTION, columnList);
      break;
    case REFERENCES:
      jj_consume_token(REFERENCES);
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        columnList = privilegeColumnList();
        break;
      default:
        jj_la1[442] = jj_gen;
        ;
      }
        tablePrivilegesNode.addAction(TablePrivilegesNode.REFERENCES_ACTION, columnList);
      break;
    case TRIGGER:
      jj_consume_token(TRIGGER);
        tablePrivilegesNode.addAction(TablePrivilegesNode.TRIGGER_ACTION, (ResultColumnList)null);
      break;
    default:
      jj_la1[443] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public ResultColumnList privilegeColumnList() throws ParseException, StandardException {
    ResultColumnList cl = (ResultColumnList)nodeFactory.getNode(NodeTypes.RESULT_COLUMN_LIST,
                                                                parserContext);
    jj_consume_token(LEFT_PAREN);
    columnNameList(cl);
    jj_consume_token(RIGHT_PAREN);
        {if (true) return cl;}
    throw new Error("Missing return statement in function");
  }

  final public List<String> granteeList() throws ParseException, StandardException {
List<String> list = new ArrayList<String>();
    grantee(list);
    label_53:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[444] = jj_gen;
        break label_53;
      }
      jj_consume_token(COMMA);
      grantee(list);
    }
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void grantee(List<String> list) throws ParseException, StandardException {
    String str;
    if (jj_2_108(1)) {
      str = identifier();
        list.add(str);
    } else {
      switch (jj_nt.kind) {
      case PUBLIC:
        jj_consume_token(PUBLIC);
        list.add(PUBLIC_AUTHORIZATION_ID);
        break;
      default:
        jj_la1[445] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public StatementNode roleGrantStatement() throws ParseException, StandardException {
    List rolesGranted;
    List grantees;
    /*
         * GRANT <rolename> {, <rolename>}* TO <authentication identifier>
         *                                  {, <authentication identifier>}*
         *
         * not implemented: WITH ADMIN OPTION, GRANTED BY clauses
         */
        rolesGranted = roleList();
    jj_consume_token(TO);
    grantees = granteeList();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.GRANT_ROLE_NODE,
                                                  rolesGranted,
                                                  grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public List<String> roleList() throws ParseException, StandardException {
    List<String> list = new ArrayList<String>();
    roleElement(list);
    label_54:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[446] = jj_gen;
        break label_54;
      }
      jj_consume_token(COMMA);
      roleElement(list);
    }
        {if (true) return list;}
    throw new Error("Missing return statement in function");
  }

  final public void roleElement(List<String> list) throws ParseException, StandardException {
    String str;
    str = identifier();
        list.add(str);
  }

  final public StatementNode revokeStatement() throws ParseException, StandardException {
    StatementNode node;
    if (getToken(1).kind == REVOKE &&
                                     ((getToken(2).kind == TRIGGER &&
                                         ((getToken(3).kind == COMMA &&
                                             isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                            getToken(3).kind == ON)) ||
                                        isPrivilegeKeywordExceptTrigger(getToken(2).kind))) {
      jj_consume_token(REVOKE);
      switch (jj_nt.kind) {
      case ALL:
      case DELETE:
      case INSERT:
      case REFERENCES:
      case SELECT:
      case UPDATE:
      case TRIGGER:
        node = tableRevokeStatement();
        break;
      case EXECUTE:
        node = routineRevokeStatement();
        break;
      case USAGE:
        node = usageRevokeStatement();
        break;
      default:
        jj_la1[447] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
        {if (true) return node;}
    } else if (getToken(1).kind == REVOKE &&
                                     ((getToken(2).kind == TRIGGER &&
                                         ((getToken(3).kind == COMMA &&
                                             !isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                            getToken(3).kind == FROM)) ||
                                        !isPrivilegeKeywordExceptTrigger(getToken(2).kind))) {
      jj_consume_token(REVOKE);
      node = roleRevokeStatement();
        {if (true) return node;}
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode tableRevokeStatement() throws ParseException, StandardException {
    PrivilegeNode privileges = null;
    List grantees;
    privileges = tablePrivileges();
    jj_consume_token(FROM);
    grantees = granteeList();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.REVOKE_NODE,
                                                  privileges, grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode routineRevokeStatement() throws ParseException, StandardException {
    List grantees;
    RoutineDesignator routine = null;
    jj_consume_token(EXECUTE);
    jj_consume_token(ON);
    routine = routineDesignator();
    jj_consume_token(FROM);
    grantees = granteeList();
    jj_consume_token(RESTRICT);
                PrivilegeNode routinePrivilege = (PrivilegeNode)
                    nodeFactory.getNode(NodeTypes.PRIVILEGE_NODE,
                                        PrivilegeNode.ObjectType.ROUTINE_PRIVILEGES,
                                        routine, null,
                                        parserContext);
                {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.REVOKE_NODE,
                                                          routinePrivilege, grantees,
                                                          parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode usageRevokeStatement() throws ParseException, StandardException {
    List grantees;
    TableName name;
    PrivilegeNode.ObjectType objectType;
    jj_consume_token(USAGE);
    jj_consume_token(ON);
    objectType = usableObjects();
    name = qualifiedName();
    jj_consume_token(FROM);
    grantees = granteeList();
    jj_consume_token(RESTRICT);
        PrivilegeNode privilegeNode = (PrivilegeNode)nodeFactory.getNode(NodeTypes.PRIVILEGE_NODE,
                                                                         objectType,
                                                                         name,
                                                                         PrivilegeNode.USAGE_PRIV,
                                                                         Boolean.TRUE,
                                                                         parserContext);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.REVOKE_NODE,
                                                  privilegeNode,
                                                  grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode roleRevokeStatement() throws ParseException, StandardException {
    List rolesRevokeed;
    List grantees;
    rolesRevokeed = roleList();
    jj_consume_token(FROM);
    grantees = granteeList();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.REVOKE_ROLE_NODE,
                                                  rolesRevokeed,
                                                  grantees,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode cursorStatement() throws ParseException, StandardException {
    String name;
    StatementNode stmt;
    int count = 1;
    Token[] tokenHolder = new Token[1];
    switch (jj_nt.kind) {
    case DECLARE:
      jj_consume_token(DECLARE);
      name = identifier();
      switch (jj_nt.kind) {
      case NO:
        jj_consume_token(NO);
        jj_consume_token(SCROLL);
        break;
      default:
        jj_la1[448] = jj_gen;
        ;
      }
      jj_consume_token(CURSOR);
      jj_consume_token(FOR);
      stmt = declarableStatement(tokenHolder);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DECLARE_STATEMENT_NODE,
                                                  name, stmt,
                                                  parserContext);}
      break;
    case FETCH:
      jj_consume_token(FETCH);
      switch (jj_nt.kind) {
      case ALL:
      case NEXT:
      case EXACT_NUMERIC:
        switch (jj_nt.kind) {
        case NEXT:
          jj_consume_token(NEXT);
          break;
        case EXACT_NUMERIC:
          count = uint_value();
          break;
        case ALL:
          jj_consume_token(ALL);
              count = -1;
          break;
        default:
          jj_la1[449] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
        break;
      default:
        jj_la1[450] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case FROM:
        jj_consume_token(FROM);
        break;
      default:
        jj_la1[451] = jj_gen;
        ;
      }
      name = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.FETCH_STATEMENT_NODE,
                                                  name, count,
                                                  parserContext);}
      break;
    case CLOSE:
      jj_consume_token(CLOSE);
      name = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.CLOSE_STATEMENT_NODE,
                                                  name,
                                                  parserContext);}
      break;
    case PREPARE:
      jj_consume_token(PREPARE);
      name = identifier();
      jj_consume_token(AS);
      stmt = proceduralStatement(tokenHolder);
        stmt.setBeginOffset(tokenHolder[0].beginOffset);
        stmt.setEndOffset(getToken(0).endOffset);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.PREPARE_STATEMENT_NODE,
                                                  name, stmt,
                                                  parserContext);}
      break;
    case DEALLOCATE:
      jj_consume_token(DEALLOCATE);
      name = identifier();
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.DEALLOCATE_STATEMENT_NODE,
                                                  name,
                                                  parserContext);}
      break;
    default:
      jj_la1[452] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode declarableStatement(Token[] tokenHolder) throws ParseException, StandardException {
    StatementNode stmt;
    tokenHolder[0] = getToken(1);
    switch (jj_nt.kind) {
    case DELETE:
    case INSERT:
    case SELECT:
    case UPDATE:
    case VALUES:
    case CALL:
    case LEFT_BRACE:
    case LEFT_PAREN:
    case QUESTION_MARK:
    case DOLLAR_N:
      stmt = proceduralStatement(tokenHolder);
      break;
    case EXECUTE:
      stmt = executeStatement();
      break;
    default:
      jj_la1[453] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        stmt.setBeginOffset(tokenHolder[0].beginOffset);
        stmt.setEndOffset(getToken(0).endOffset);
        {if (true) return stmt;}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode executeStatement() throws ParseException, StandardException {
    String name;
    List<ValueNode> params = new ArrayList<ValueNode>();
    jj_consume_token(EXECUTE);
    name = identifier();
    switch (jj_nt.kind) {
    case LEFT_PAREN:
      methodCallParameterList(params);
      break;
    default:
      jj_la1[454] = jj_gen;
      ;
    }
        ValueNodeList parameterList = (ValueNodeList)
            nodeFactory.getNode(NodeTypes.VALUE_NODE_LIST,
                                parserContext);
        for (ValueNode param : params)
            parameterList.add(param);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.EXECUTE_STATEMENT_NODE,
                                                  name, parameterList,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public StatementNode explainStatement() throws ParseException, StandardException {
    StatementNode stmt;
    Token[] tokenHolder = new Token[1];
    ExplainStatementNode.Detail detail = ExplainStatementNode.Detail.NORMAL;
    jj_consume_token(EXPLAIN);
    switch (jj_nt.kind) {
    case BRIEF:
    case VERBOSE:
      detail = explainDetail();
      break;
    default:
      jj_la1[455] = jj_gen;
      ;
    }
    stmt = declarableStatement(tokenHolder);
        {if (true) return (StatementNode)nodeFactory.getNode(NodeTypes.EXPLAIN_STATEMENT_NODE,
                                                  stmt, detail,
                                                  parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public ExplainStatementNode.Detail explainDetail() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case BRIEF:
      jj_consume_token(BRIEF);
      {if (true) return ExplainStatementNode.Detail.BRIEF;}
      break;
    case VERBOSE:
      jj_consume_token(VERBOSE);
      {if (true) return ExplainStatementNode.Detail.VERBOSE;}
      break;
    default:
      jj_la1[456] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public StatementNode copyStatement() throws ParseException, StandardException {
    CopyStatementNode stmt;
    jj_consume_token(COPY);
    stmt = copyStatementBase();
    switch (jj_nt.kind) {
    case WITH:
    case LEFT_PAREN:
      switch (jj_nt.kind) {
      case WITH:
        jj_consume_token(WITH);
        break;
      default:
        jj_la1[457] = jj_gen;
        ;
      }
      jj_consume_token(LEFT_PAREN);
      copyOption(stmt);
      label_55:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[458] = jj_gen;
          break label_55;
        }
        jj_consume_token(COMMA);
        copyOption(stmt);
      }
      jj_consume_token(RIGHT_PAREN);
      break;
    default:
      jj_la1[459] = jj_gen;
      ;
    }
        {if (true) return stmt;}
    throw new Error("Missing return statement in function");
  }

  final public CopyStatementNode copyStatementBase() throws ParseException, StandardException {
    CopyStatementNode.Mode mode;
    TableName tableName = null;
    ResultColumnList columnList = null;
    SubqueryNode subquery = null;
    String filename = null;
    if (getToken(1).kind == LEFT_PAREN && getToken(2).kind == SELECT) {
      subquery = derivedTable();
      jj_consume_token(TO);
      switch (jj_nt.kind) {
      case DOUBLEQUOTED_STRING:
      case DOUBLEDOLLAR_STRING:
      case SINGLEQUOTED_STRING:
        filename = string();
        break;
      case STDOUT:
        jj_consume_token(STDOUT);
        break;
      default:
        jj_la1[460] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      mode = CopyStatementNode.Mode.FROM_SUBQUERY;
    } else if (jj_2_109(1)) {
      tableName = qualifiedName();
      switch (jj_nt.kind) {
      case LEFT_PAREN:
        jj_consume_token(LEFT_PAREN);
        columnList = insertColumnList();
        jj_consume_token(RIGHT_PAREN);
        break;
      default:
        jj_la1[461] = jj_gen;
        ;
      }
      switch (jj_nt.kind) {
      case TO:
        jj_consume_token(TO);
        switch (jj_nt.kind) {
        case DOUBLEQUOTED_STRING:
        case DOUBLEDOLLAR_STRING:
        case SINGLEQUOTED_STRING:
          filename = string();
          break;
        case STDOUT:
          jj_consume_token(STDOUT);
          break;
        default:
          jj_la1[462] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
         mode = CopyStatementNode.Mode.FROM_TABLE;
        break;
      case FROM:
        jj_consume_token(FROM);
        switch (jj_nt.kind) {
        case DOUBLEQUOTED_STRING:
        case DOUBLEDOLLAR_STRING:
        case SINGLEQUOTED_STRING:
          filename = string();
          break;
        case STDIN:
          jj_consume_token(STDIN);
          break;
        default:
          jj_la1[463] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
         mode = CopyStatementNode.Mode.TO_TABLE;
        break;
      default:
        jj_la1[464] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
        if (subquery != null)
            {if (true) return (CopyStatementNode)nodeFactory.getNode(NodeTypes.COPY_STATEMENT_NODE,
                                                          mode,
                                                          subquery,
                                                          filename,
                                                          parserContext);}
        else
            {if (true) return (CopyStatementNode)nodeFactory.getNode(NodeTypes.COPY_STATEMENT_NODE,
                                                          mode,
                                                          tableName, columnList,
                                                          filename,
                                                          parserContext);}
    throw new Error("Missing return statement in function");
  }

  final public void copyOption(CopyStatementNode stmt) throws ParseException, StandardException {
    String value;
    long lvalue;
    CopyStatementNode.Format format;
    switch (jj_nt.kind) {
    case FORMAT:
      jj_consume_token(FORMAT);
      format = copyFormat();
        stmt.setFormat(format);
      break;
    case DELIMITER:
      jj_consume_token(DELIMITER);
      value = string();
        stmt.setDelimiter(value);
      break;
    case NULL:
      jj_consume_token(NULL);
      value = string();
        stmt.setNullString(value);
      break;
    case HEADER:
      jj_consume_token(HEADER);
      token = booleanLiteral();
        stmt.setHeader(token.kind == TRUE);
      break;
    case _QUOTE:
      jj_consume_token(_QUOTE);
      value = string();
        stmt.setQuote(value);
      break;
    case ESCAPE:
      jj_consume_token(ESCAPE);
      value = string();
        stmt.setEscape(value);
      break;
    case ENCODING:
      jj_consume_token(ENCODING);
      value = string();
        stmt.setEncoding(value);
      break;
    case COMMIT:
      jj_consume_token(COMMIT);
      lvalue = exactNumber();
      switch (jj_nt.kind) {
      case ROWS:
        jj_consume_token(ROWS);
        break;
      default:
        jj_la1[465] = jj_gen;
        ;
      }
        stmt.setCommitFrequency(lvalue);
      break;
    default:
      jj_la1[466] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public CopyStatementNode.Format copyFormat() throws ParseException, StandardException {
    switch (jj_nt.kind) {
    case CSV:
      jj_consume_token(CSV);
            {if (true) return CopyStatementNode.Format.CSV;}
      break;
    case MYSQL_DUMP:
      jj_consume_token(MYSQL_DUMP);
                   {if (true) return CopyStatementNode.Format.MYSQL_DUMP;}
      break;
    default:
      jj_la1[467] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
    throw new Error("Missing return statement in function");
  }

  final public String internalIdentifier() throws ParseException, StandardException {
    String str;
    Token tok;
    switch (jj_nt.kind) {
    case IDENTIFIER:
      tok = jj_consume_token(IDENTIFIER);
        str = SQLToIdentifierCase(tok.image);

        // Remember last identifier token and whether it was delimited.
        nextToLastTokenDelimitedIdentifier = lastTokenDelimitedIdentifier;
        lastTokenDelimitedIdentifier = Boolean.FALSE;
        nextToLastIdentifierToken = lastIdentifierToken;
        lastIdentifierToken = tok;
        {if (true) return str;}
      break;
    case BACKQUOTED_IDENTIFIER:
    case DOUBLEQUOTED_IDENTIFIER:
      str = delimitedIdentifier();
        {if (true) return str;}
      break;
    default:
      jj_la1[468] = jj_gen;
      if (jj_2_110(1)) {
        str = nonReservedKeyword();
        {if (true) return SQLToIdentifierCase(str);}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public String identifier() throws ParseException, StandardException {
    String id;
    id = internalIdentifier();
        parserContext.checkIdentifierLengthLimit(id);
        {if (true) return id;}
    throw new Error("Missing return statement in function");
  }

// Same as above, but without length check, which caller will perform.
// This avoids errors too soon from Java name components that are too
// long to be SQL identifier components.
  final public String identifierDeferCheckLength() throws ParseException, StandardException {
    String id;
    id = internalIdentifier();
        {if (true) return id;}
    throw new Error("Missing return statement in function");
  }

  final public String delimitedIdentifier() throws ParseException {
    String str;
    Token tok;
    switch (jj_nt.kind) {
    case DOUBLEQUOTED_IDENTIFIER:
      tok = jj_consume_token(DOUBLEQUOTED_IDENTIFIER);
        // Strip quotes and correct interior ones.
        str = trimAndCompressQuotes(tok.image, DOUBLEQUOTES, false);
      break;
    case BACKQUOTED_IDENTIFIER:
      tok = jj_consume_token(BACKQUOTED_IDENTIFIER);
        str = trimAndCompressQuotes(tok.image, BACKQUOTES, false);
      break;
    default:
      jj_la1[469] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        // Remember last identifier token and whether it was delimited.
        nextToLastTokenDelimitedIdentifier = lastTokenDelimitedIdentifier;
        lastTokenDelimitedIdentifier = Boolean.TRUE;
        nextToLastIdentifierToken = lastIdentifierToken;
        lastIdentifierToken = tok;
        {if (true) return str;}
    throw new Error("Missing return statement in function");
  }

  final public String reservedKeyword() throws ParseException {
    Token tok;
    switch (jj_nt.kind) {
    case ADD:
      /* SQL92 reserved keywords */
        tok = jj_consume_token(ADD);
      break;
    case ALL:
      tok = jj_consume_token(ALL);
      break;
    case ALLOCATE:
      tok = jj_consume_token(ALLOCATE);
      break;
    case ALTER:
      tok = jj_consume_token(ALTER);
      break;
    case AND:
      tok = jj_consume_token(AND);
      break;
    case ANY:
      tok = jj_consume_token(ANY);
      break;
    case ARE:
      tok = jj_consume_token(ARE);
      break;
    case AS:
      tok = jj_consume_token(AS);
      break;
    case AT:
      tok = jj_consume_token(AT);
      break;
    case AUTHORIZATION:
      tok = jj_consume_token(AUTHORIZATION);
      break;
    case AVG:
      tok = jj_consume_token(AVG);
      break;
    case BEGIN:
      tok = jj_consume_token(BEGIN);
      break;
    case BETWEEN:
      tok = jj_consume_token(BETWEEN);
      break;
    case BIT:
      tok = jj_consume_token(BIT);
      break;
    case BOTH:
      tok = jj_consume_token(BOTH);
      break;
    case BY:
      tok = jj_consume_token(BY);
      break;
    case CASCADED:
      tok = jj_consume_token(CASCADED);
      break;
    case CASE:
      tok = jj_consume_token(CASE);
      break;
    case CAST:
      tok = jj_consume_token(CAST);
      break;
    case CHAR:
      tok = jj_consume_token(CHAR);
      break;
    case CHARACTER_LENGTH:
      tok = jj_consume_token(CHARACTER_LENGTH);
      break;
    case CHAR_LENGTH:
      tok = jj_consume_token(CHAR_LENGTH);
      break;
    case CHECK:
      tok = jj_consume_token(CHECK);
      break;
    case CLOSE:
      tok = jj_consume_token(CLOSE);
      break;
    case COLLATE:
      tok = jj_consume_token(COLLATE);
      break;
    case COLUMN:
      tok = jj_consume_token(COLUMN);
      break;
    case COMMIT:
      tok = jj_consume_token(COMMIT);
      break;
    case CONNECT:
      tok = jj_consume_token(CONNECT);
      break;
    case CONNECTION:
      tok = jj_consume_token(CONNECTION);
      break;
    case CONSTRAINT:
      tok = jj_consume_token(CONSTRAINT);
      break;
    case CONTINUE:
      tok = jj_consume_token(CONTINUE);
      break;
    case CONVERT:
      tok = jj_consume_token(CONVERT);
      break;
    case CORRESPONDING:
      tok = jj_consume_token(CORRESPONDING);
      break;
    case CREATE:
      tok = jj_consume_token(CREATE);
      break;
    case CROSS:
      tok = jj_consume_token(CROSS);
      break;
    case CURRENT:
      tok = jj_consume_token(CURRENT);
      break;
    case CURRENT_DATE:
      tok = jj_consume_token(CURRENT_DATE);
      break;
    case CURRENT_TIME:
      tok = jj_consume_token(CURRENT_TIME);
      break;
    case CURRENT_TIMESTAMP:
      tok = jj_consume_token(CURRENT_TIMESTAMP);
      break;
    case CURRENT_USER:
      tok = jj_consume_token(CURRENT_USER);
      break;
    case CURSOR:
      tok = jj_consume_token(CURSOR);
      break;
    case DEALLOCATE:
      tok = jj_consume_token(DEALLOCATE);
      break;
    case DEC:
      tok = jj_consume_token(DEC);
      break;
    case DECIMAL:
      tok = jj_consume_token(DECIMAL);
      break;
    case DECLARE:
      tok = jj_consume_token(DECLARE);
      break;
    case _DEFAULT:
      tok = jj_consume_token(_DEFAULT);
      break;
    case DELETE:
      tok = jj_consume_token(DELETE);
      break;
    case DESCRIBE:
      tok = jj_consume_token(DESCRIBE);
      break;
    case DISCONNECT:
      tok = jj_consume_token(DISCONNECT);
      break;
    case DISTINCT:
      tok = jj_consume_token(DISTINCT);
      break;
    case DOUBLE:
      tok = jj_consume_token(DOUBLE);
      break;
    case DROP:
      tok = jj_consume_token(DROP);
      break;
    case ELSE:
      tok = jj_consume_token(ELSE);
      break;
    case END:
      tok = jj_consume_token(END);
      break;
    case ENDEXEC:
      tok = jj_consume_token(ENDEXEC);
      break;
    case ESCAPE:
      tok = jj_consume_token(ESCAPE);
      break;
    case EXCEPT:
      tok = jj_consume_token(EXCEPT);
      break;
    case EXEC:
      tok = jj_consume_token(EXEC);
      break;
    case EXECUTE:
      tok = jj_consume_token(EXECUTE);
      break;
    case EXISTS:
      tok = jj_consume_token(EXISTS);
      break;
    case EXTERNAL:
      tok = jj_consume_token(EXTERNAL);
      break;
    case FALSE:
      tok = jj_consume_token(FALSE);
      break;
    case FETCH:
      tok = jj_consume_token(FETCH);
      break;
    case FLOAT:
      tok = jj_consume_token(FLOAT);
      break;
    case FOR:
      tok = jj_consume_token(FOR);
      break;
    case FOREIGN:
      tok = jj_consume_token(FOREIGN);
      break;
    case FROM:
      tok = jj_consume_token(FROM);
      break;
    case FULL:
      tok = jj_consume_token(FULL);
      break;
    case FUNCTION:
      tok = jj_consume_token(FUNCTION);
      break;
    case GET:
      tok = jj_consume_token(GET);
      break;
    case GET_CURRENT_CONNECTION:
      tok = jj_consume_token(GET_CURRENT_CONNECTION);
      break;
    case GLOBAL:
      tok = jj_consume_token(GLOBAL);
      break;
    case GRANT:
      tok = jj_consume_token(GRANT);
      break;
    case GROUP:
      tok = jj_consume_token(GROUP);
      break;
    case GROUP_CONCAT:
      tok = jj_consume_token(GROUP_CONCAT);
      break;
    case HAVING:
      tok = jj_consume_token(HAVING);
      break;
    case HOUR:
      tok = jj_consume_token(HOUR);
      break;
    case IDENTITY:
      tok = jj_consume_token(IDENTITY);
      break;
    case IMMEDIATE:
      tok = jj_consume_token(IMMEDIATE);
      break;
    case IN:
      tok = jj_consume_token(IN);
      break;
    case INDEX:
      tok = jj_consume_token(INDEX);
      break;
    case INDICATOR:
      tok = jj_consume_token(INDICATOR);
      break;
    case INNER:
      tok = jj_consume_token(INNER);
      break;
    case INOUT:
      tok = jj_consume_token(INOUT);
      break;
    case INPUT:
      tok = jj_consume_token(INPUT);
      break;
    case INSENSITIVE:
      tok = jj_consume_token(INSENSITIVE);
      break;
    case INSERT:
      tok = jj_consume_token(INSERT);
      break;
    case INT:
      tok = jj_consume_token(INT);
      break;
    case INTEGER:
      tok = jj_consume_token(INTEGER);
      break;
    case INTERSECT:
      tok = jj_consume_token(INTERSECT);
      break;
    case INTERVAL:
      tok = jj_consume_token(INTERVAL);
      break;
    case INTO:
      tok = jj_consume_token(INTO);
      break;
    case IS:
      tok = jj_consume_token(IS);
      break;
    case JOIN:
      tok = jj_consume_token(JOIN);
      break;
    case LEADING:
      tok = jj_consume_token(LEADING);
      break;
    case LEFT:
      tok = jj_consume_token(LEFT);
      break;
    case LIKE:
      tok = jj_consume_token(LIKE);
      break;
    case LIMIT:
      tok = jj_consume_token(LIMIT);
      break;
    case LOWER:
      tok = jj_consume_token(LOWER);
      break;
    case MATCH:
      tok = jj_consume_token(MATCH);
      break;
    case MAX:
      tok = jj_consume_token(MAX);
      break;
    case MIN:
      tok = jj_consume_token(MIN);
      break;
    case MINUTE:
      tok = jj_consume_token(MINUTE);
      break;
    case NATIONAL:
      tok = jj_consume_token(NATIONAL);
      break;
    case NATURAL:
      tok = jj_consume_token(NATURAL);
      break;
    case NCHAR:
      tok = jj_consume_token(NCHAR);
      break;
    case NVARCHAR:
      tok = jj_consume_token(NVARCHAR);
      break;
    case NEXT:
      tok = jj_consume_token(NEXT);
      break;
    case NO:
      tok = jj_consume_token(NO);
      break;
    case NONE:
      tok = jj_consume_token(NONE);
      break;
    case NOT:
      tok = jj_consume_token(NOT);
      break;
    case NULL:
      tok = jj_consume_token(NULL);
      break;
    case NULLIF:
      tok = jj_consume_token(NULLIF);
      break;
    case NUMERIC:
      tok = jj_consume_token(NUMERIC);
      break;
    case OCTET_LENGTH:
      tok = jj_consume_token(OCTET_LENGTH);
      break;
    case OF:
      tok = jj_consume_token(OF);
      break;
    case ON:
      tok = jj_consume_token(ON);
      break;
    case ONLY:
      tok = jj_consume_token(ONLY);
      break;
    case OPEN:
      tok = jj_consume_token(OPEN);
      break;
    case OR:
      tok = jj_consume_token(OR);
      break;
    case ORDER:
      tok = jj_consume_token(ORDER);
      break;
    case OUT:
      tok = jj_consume_token(OUT);
      break;
    case OUTER:
      tok = jj_consume_token(OUTER);
      break;
    case OUTPUT:
      tok = jj_consume_token(OUTPUT);
      break;
    case OVERLAPS:
      tok = jj_consume_token(OVERLAPS);
      break;
    case PARTITION:
      tok = jj_consume_token(PARTITION);
      break;
    case PREPARE:
      tok = jj_consume_token(PREPARE);
      break;
    case PRIMARY:
      tok = jj_consume_token(PRIMARY);
      break;
    case PROCEDURE:
      tok = jj_consume_token(PROCEDURE);
      break;
    case PUBLIC:
      tok = jj_consume_token(PUBLIC);
      break;
    case REAL:
      tok = jj_consume_token(REAL);
      break;
    case REFERENCES:
      tok = jj_consume_token(REFERENCES);
      break;
    case RESTRICT:
      tok = jj_consume_token(RESTRICT);
      break;
    case RETURNING:
      tok = jj_consume_token(RETURNING);
      break;
    case REVOKE:
      tok = jj_consume_token(REVOKE);
      break;
    case RIGHT:
      tok = jj_consume_token(RIGHT);
      break;
    case ROLLBACK:
      tok = jj_consume_token(ROLLBACK);
      break;
    case ROWS:
      tok = jj_consume_token(ROWS);
      break;
    case SCHEMA:
      tok = jj_consume_token(SCHEMA);
      break;
    case SCROLL:
      tok = jj_consume_token(SCROLL);
      break;
    case SECOND:
      tok = jj_consume_token(SECOND);
      break;
    case SELECT:
      tok = jj_consume_token(SELECT);
      break;
    case SESSION_USER:
      tok = jj_consume_token(SESSION_USER);
      break;
    case SET:
      tok = jj_consume_token(SET);
      break;
    case SMALLINT:
      tok = jj_consume_token(SMALLINT);
      break;
    case SOME:
      tok = jj_consume_token(SOME);
      break;
    case SQL:
      tok = jj_consume_token(SQL);
      break;
    case SQLCODE:
      tok = jj_consume_token(SQLCODE);
      break;
    case SQLERROR:
      tok = jj_consume_token(SQLERROR);
      break;
    case SQLSTATE:
      tok = jj_consume_token(SQLSTATE);
      break;
    case STRAIGHT_JOIN:
      tok = jj_consume_token(STRAIGHT_JOIN);
      break;
    case SUBSTRING:
      tok = jj_consume_token(SUBSTRING);
      break;
    case SUM:
      tok = jj_consume_token(SUM);
      break;
    case SYSTEM_USER:
      tok = jj_consume_token(SYSTEM_USER);
      break;
    case TABLE:
      tok = jj_consume_token(TABLE);
      break;
    case TIMEZONE_HOUR:
      tok = jj_consume_token(TIMEZONE_HOUR);
      break;
    case TIMEZONE_MINUTE:
      tok = jj_consume_token(TIMEZONE_MINUTE);
      break;
    case TO:
      tok = jj_consume_token(TO);
      break;
    case TRAILING:
      tok = jj_consume_token(TRAILING);
      break;
    case TRANSLATE:
      tok = jj_consume_token(TRANSLATE);
      break;
    case TRANSLATION:
      tok = jj_consume_token(TRANSLATION);
      break;
    case TRUE:
      tok = jj_consume_token(TRUE);
      break;
    case UNION:
      tok = jj_consume_token(UNION);
      break;
    case UNIQUE:
      tok = jj_consume_token(UNIQUE);
      break;
    case UNKNOWN:
      tok = jj_consume_token(UNKNOWN);
      break;
    case UPDATE:
      tok = jj_consume_token(UPDATE);
      break;
    case UPPER:
      tok = jj_consume_token(UPPER);
      break;
    case USER:
      tok = jj_consume_token(USER);
      break;
    case USING:
      tok = jj_consume_token(USING);
      break;
    case VALUES:
      tok = jj_consume_token(VALUES);
      break;
    case VARCHAR:
      tok = jj_consume_token(VARCHAR);
      break;
    case VARYING:
      tok = jj_consume_token(VARYING);
      break;
    case WHENEVER:
      tok = jj_consume_token(WHENEVER);
      break;
    case WHERE:
      tok = jj_consume_token(WHERE);
      break;
    case WITH:
      tok = jj_consume_token(WITH);
      break;
    case YEAR:
      tok = jj_consume_token(YEAR);
      break;
    case BOOLEAN:
      tok = jj_consume_token(BOOLEAN);
      break;
    case CALL:
      tok = jj_consume_token(CALL);
      break;
    case CURRENT_ROLE:
      tok = jj_consume_token(CURRENT_ROLE);
      break;
    case CURRENT_SCHEMA:
      tok = jj_consume_token(CURRENT_SCHEMA);
      break;
    case EXPLAIN:
      tok = jj_consume_token(EXPLAIN);
      break;
    case GROUPING:
      tok = jj_consume_token(GROUPING);
      break;
    case LTRIM:
      tok = jj_consume_token(LTRIM);
      break;
    case RTRIM:
      tok = jj_consume_token(RTRIM);
      break;
    case TRIM:
      tok = jj_consume_token(TRIM);
      break;
    case SUBSTR:
      tok = jj_consume_token(SUBSTR);
      break;
    case XML:
      tok = jj_consume_token(XML);
      break;
    case XMLPARSE:
      tok = jj_consume_token(XMLPARSE);
      break;
    case XMLSERIALIZE:
      tok = jj_consume_token(XMLSERIALIZE);
      break;
    case XMLEXISTS:
      tok = jj_consume_token(XMLEXISTS);
      break;
    case XMLQUERY:
      tok = jj_consume_token(XMLQUERY);
      break;
    case Z_ORDER_LAT_LON:
      tok = jj_consume_token(Z_ORDER_LAT_LON);
      break;
    default:
      jj_la1[470] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
        // Remember whether last token was a delimited identifier
        nextToLastTokenDelimitedIdentifier = lastTokenDelimitedIdentifier;
        lastTokenDelimitedIdentifier = Boolean.FALSE;
        {if (true) return tok.image;}
    throw new Error("Missing return statement in function");
  }

  final public String nonReservedKeyword() throws ParseException {
    Token tok;
    switch (jj_nt.kind) {
    case ABS:
      tok = jj_consume_token(ABS);
      break;
    case ABSVAL:
      tok = jj_consume_token(ABSVAL);
      break;
    case ACTION:
      tok = jj_consume_token(ACTION);
      break;
    case AFTER:
      tok = jj_consume_token(AFTER);
      break;
    case ALWAYS:
      tok = jj_consume_token(ALWAYS);
      break;
    case ASC:
      tok = jj_consume_token(ASC);
      break;
    case ASSERTION:
      tok = jj_consume_token(ASSERTION);
      break;
    case BEFORE:
      tok = jj_consume_token(BEFORE);
      break;
    case BINARY:
      tok = jj_consume_token(BINARY);
      break;
    case BLOB:
      tok = jj_consume_token(BLOB);
      break;
    case BRIEF:
      tok = jj_consume_token(BRIEF);
      break;
    case BTREE:
      tok = jj_consume_token(BTREE);
      break;
    case C:
      tok = jj_consume_token(C);
      break;
    case CALLED:
      tok = jj_consume_token(CALLED);
      break;
    case CASCADE:
      tok = jj_consume_token(CASCADE);
      break;
    case CHARACTER:
      tok = jj_consume_token(CHARACTER);
      break;
    case CHARACTERISTICS:
      tok = jj_consume_token(CHARACTERISTICS);
      break;
    case CLASS:
      tok = jj_consume_token(CLASS);
      break;
    case CLOB:
      tok = jj_consume_token(CLOB);
      break;
    case COALESCE:
      tok = jj_consume_token(COALESCE);
      break;
    case COBOL:
      tok = jj_consume_token(COBOL);
      break;
    case COLLATION:
      tok = jj_consume_token(COLLATION);
      break;
    case COMMITTED:
      tok = jj_consume_token(COMMITTED);
      break;
    case COMPRESS:
      tok = jj_consume_token(COMPRESS);
      break;
    case CONCAT:
      tok = jj_consume_token(CONCAT);
      break;
    case CONSTRAINTS:
      tok = jj_consume_token(CONSTRAINTS);
      break;
    case CONTAINS:
      tok = jj_consume_token(CONTAINS);
      break;
    case CONTENT:
      tok = jj_consume_token(CONTENT);
      break;
    case COPY:
      tok = jj_consume_token(COPY);
      break;
    case COUNT:
      tok = jj_consume_token(COUNT);
      break;
    case CS:
      tok = jj_consume_token(CS);
      break;
    case CSV:
      tok = jj_consume_token(CSV);
      break;
    case CURDATE:
      tok = jj_consume_token(CURDATE);
      break;
    case CURTIME:
      tok = jj_consume_token(CURTIME);
      break;
    case D:
      tok = jj_consume_token(D);
      break;
    case DATA:
      tok = jj_consume_token(DATA);
      break;
    case DATABASE:
      tok = jj_consume_token(DATABASE);
      break;
    case DATE:
      tok = jj_consume_token(DATE);
      break;
    case DATETIME:
      tok = jj_consume_token(DATETIME);
      break;
    case DAY:
      tok = jj_consume_token(DAY);
      break;
    case DAY_HOUR:
      tok = jj_consume_token(DAY_HOUR);
      break;
    case DAY_MICROSECOND:
      tok = jj_consume_token(DAY_MICROSECOND);
      break;
    case DAY_MINUTE:
      tok = jj_consume_token(DAY_MINUTE);
      break;
    case DAY_SECOND:
      tok = jj_consume_token(DAY_SECOND);
      break;
    case DB2SQL:
      tok = jj_consume_token(DB2SQL);
      break;
    case DEFERRABLE:
      tok = jj_consume_token(DEFERRABLE);
      break;
    case DEFERRED:
      tok = jj_consume_token(DEFERRED);
      break;
    case DEFRAGMENT:
      tok = jj_consume_token(DEFRAGMENT);
      break;
    case DELIMITER:
      tok = jj_consume_token(DELIMITER);
      break;
    case DESC:
      tok = jj_consume_token(DESC);
      break;
    case DIAGNOSTICS:
      tok = jj_consume_token(DIAGNOSTICS);
      break;
    case DIRTY:
      tok = jj_consume_token(DIRTY);
      break;
    case DIV:
      tok = jj_consume_token(DIV);
      break;
    case DOCUMENT:
      tok = jj_consume_token(DOCUMENT);
      break;
    case DUMMY:
      tok = jj_consume_token(DUMMY);
      break;
    case DYNAMIC:
      tok = jj_consume_token(DYNAMIC);
      break;
    case EACH:
      tok = jj_consume_token(EACH);
      break;
    case EMPTY:
      tok = jj_consume_token(EMPTY);
      break;
    case ENCODING:
      tok = jj_consume_token(ENCODING);
      break;
    case EXCEPTION:
      tok = jj_consume_token(EXCEPTION);
      break;
    case EXCLUSIVE:
      tok = jj_consume_token(EXCLUSIVE);
      break;
    case EXTRACT:
      tok = jj_consume_token(EXTRACT);
      break;
    case FIRST:
      tok = jj_consume_token(FIRST);
      break;
    case FN:
      tok = jj_consume_token(FN);
      break;
    case FORCE:
      tok = jj_consume_token(FORCE);
      break;
    case FORMAT:
      tok = jj_consume_token(FORMAT);
      break;
    case FORTRAN:
      tok = jj_consume_token(FORTRAN);
      break;
    case FOUND:
      tok = jj_consume_token(FOUND);
      break;
    case FULL_TEXT:
      tok = jj_consume_token(FULL_TEXT);
      break;
    case GENERATED:
      tok = jj_consume_token(GENERATED);
      break;
    case GO:
      tok = jj_consume_token(GO);
      break;
    case GOTO:
      tok = jj_consume_token(GOTO);
      break;
    case HEADER:
      tok = jj_consume_token(HEADER);
      break;
    case HOUR_MICROSECOND:
      tok = jj_consume_token(HOUR_MICROSECOND);
      break;
    case HOUR_MINUTE:
      tok = jj_consume_token(HOUR_MINUTE);
      break;
    case HOUR_SECOND:
      tok = jj_consume_token(HOUR_SECOND);
      break;
    case IDENTITY_VAL_LOCAL:
      tok = jj_consume_token(IDENTITY_VAL_LOCAL);
      break;
    case IF:
      tok = jj_consume_token(IF);
      break;
    case IGNORE:
      tok = jj_consume_token(IGNORE);
      break;
    case INCREMENT:
      tok = jj_consume_token(INCREMENT);
      break;
    case INITIAL:
      tok = jj_consume_token(INITIAL);
      break;
    case INITIALLY:
      tok = jj_consume_token(INITIALLY);
      break;
    case INPLACE:
      tok = jj_consume_token(INPLACE);
      break;
    case INTERRUPT:
      tok = jj_consume_token(INTERRUPT);
      break;
    case IN_MEMORY:
      tok = jj_consume_token(IN_MEMORY);
      break;
    case ISOLATION:
      tok = jj_consume_token(ISOLATION);
      break;
    case JAVA:
      tok = jj_consume_token(JAVA);
      break;
    case KEY:
      tok = jj_consume_token(KEY);
      break;
    case KILL:
      tok = jj_consume_token(KILL);
      break;
    case LANGUAGE:
      tok = jj_consume_token(LANGUAGE);
      break;
    case LARGE:
      tok = jj_consume_token(LARGE);
      break;
    case LAST:
      tok = jj_consume_token(LAST);
      break;
    case LCASE:
      tok = jj_consume_token(LCASE);
      break;
    case LENGTH:
      tok = jj_consume_token(LENGTH);
      break;
    case LEVEL:
      tok = jj_consume_token(LEVEL);
      break;
    case LOCATE:
      tok = jj_consume_token(LOCATE);
      break;
    case LOCK:
      tok = jj_consume_token(LOCK);
      break;
    case LOCKS:
      tok = jj_consume_token(LOCKS);
      break;
    case LOCKSIZE:
      tok = jj_consume_token(LOCKSIZE);
      break;
    case LOGGED:
      tok = jj_consume_token(LOGGED);
      break;
    case LONG:
      tok = jj_consume_token(LONG);
      break;
    case LONGBLOB:
      tok = jj_consume_token(LONGBLOB);
      break;
    case LONGINT:
      tok = jj_consume_token(LONGINT);
      break;
    case LONGTEXT:
      tok = jj_consume_token(LONGTEXT);
      break;
    case MEDIUMBLOB:
      tok = jj_consume_token(MEDIUMBLOB);
      break;
    case MEDIUMINT:
      tok = jj_consume_token(MEDIUMINT);
      break;
    case MEDIUMTEXT:
      tok = jj_consume_token(MEDIUMTEXT);
      break;
    case MESSAGE_LOCALE:
      tok = jj_consume_token(MESSAGE_LOCALE);
      break;
    case METHOD:
      tok = jj_consume_token(METHOD);
      break;
    case MICROSECOND:
      tok = jj_consume_token(MICROSECOND);
      break;
    case MINUTE_MICROSECOND:
      tok = jj_consume_token(MINUTE_MICROSECOND);
      break;
    case MINUTE_SECOND:
      tok = jj_consume_token(MINUTE_SECOND);
      break;
    case MOD:
      tok = jj_consume_token(MOD);
      break;
    case MODE:
      tok = jj_consume_token(MODE);
      break;
    case MODIFIES:
      tok = jj_consume_token(MODIFIES);
      break;
    case MODIFY:
      tok = jj_consume_token(MODIFY);
      break;
    case MODULE:
      tok = jj_consume_token(MODULE);
      break;
    case _MORE:
      tok = jj_consume_token(_MORE);
      break;
    case MONTH:
      tok = jj_consume_token(MONTH);
      break;
    case MUMPS:
      tok = jj_consume_token(MUMPS);
      break;
    case MYSQL_DUMP:
      tok = jj_consume_token(MYSQL_DUMP);
      break;
    case NAME:
      tok = jj_consume_token(NAME);
      break;
    case NCLOB:
      tok = jj_consume_token(NCLOB);
      break;
    case NEW:
      tok = jj_consume_token(NEW);
      break;
    case NEW_TABLE:
      tok = jj_consume_token(NEW_TABLE);
      break;
    case NULLABLE:
      tok = jj_consume_token(NULLABLE);
      break;
    case NULLS:
      tok = jj_consume_token(NULLS);
      break;
    case NUMBER:
      tok = jj_consume_token(NUMBER);
      break;
    case OBJECT:
      tok = jj_consume_token(OBJECT);
      break;
    case OFF:
      tok = jj_consume_token(OFF);
      break;
    default:
      jj_la1[471] = jj_gen;
      if (getToken(1).kind == OFFSET && !seeingOffsetClause()) {
        tok = jj_consume_token(OFFSET);
      } else {
        switch (jj_nt.kind) {
        case OJ:
          tok = jj_consume_token(OJ);
          break;
        case OLD:
          tok = jj_consume_token(OLD);
          break;
        case OLD_TABLE:
          tok = jj_consume_token(OLD_TABLE);
          break;
        case OPTION:
          tok = jj_consume_token(OPTION);
          break;
        case OVER:
          tok = jj_consume_token(OVER);
          break;
        case PAD:
          tok = jj_consume_token(PAD);
          break;
        case PARTIAL:
          tok = jj_consume_token(PARTIAL);
          break;
        case PASCAL:
          tok = jj_consume_token(PASCAL);
          break;
        case PASSING:
          tok = jj_consume_token(PASSING);
          break;
        case PLI:
          tok = jj_consume_token(PLI);
          break;
        case POSITION:
          tok = jj_consume_token(POSITION);
          break;
        case PRECISION:
          tok = jj_consume_token(PRECISION);
          break;
        case PRESERVE:
          tok = jj_consume_token(PRESERVE);
          break;
        case PRIOR:
          tok = jj_consume_token(PRIOR);
          break;
        case PRIVILEGES:
          tok = jj_consume_token(PRIVILEGES);
          break;
        case PROPERTIES:
          tok = jj_consume_token(PROPERTIES);
          break;
        case PURGE:
          tok = jj_consume_token(PURGE);
          break;
        case QUARTER:
          tok = jj_consume_token(QUARTER);
          break;
        case _QUOTE:
          tok = jj_consume_token(_QUOTE);
          break;
        case READ:
          tok = jj_consume_token(READ);
          break;
        case READS:
          tok = jj_consume_token(READS);
          break;
        case RELATIVE:
          tok = jj_consume_token(RELATIVE);
          break;
        case REF:
          tok = jj_consume_token(REF);
          break;
        case RELEASE:
          tok = jj_consume_token(RELEASE);
          break;
        case RENAME:
          tok = jj_consume_token(RENAME);
          break;
        case REPEATABLE:
          tok = jj_consume_token(REPEATABLE);
          break;
        case REPLACE:
          tok = jj_consume_token(REPLACE);
          break;
        case REFERENCING:
          tok = jj_consume_token(REFERENCING);
          break;
        case RESET:
          tok = jj_consume_token(RESET);
          break;
        case RESTART:
          tok = jj_consume_token(RESTART);
          break;
        case RESULT:
          tok = jj_consume_token(RESULT);
          break;
        case RETAIN:
          tok = jj_consume_token(RETAIN);
          break;
        case RETURNS:
          tok = jj_consume_token(RETURNS);
          break;
        case ROLE:
          tok = jj_consume_token(ROLE);
          break;
        case ROLLUP:
          tok = jj_consume_token(ROLLUP);
          break;
        case ROW:
          tok = jj_consume_token(ROW);
          break;
        case ROWNUMBER:
          tok = jj_consume_token(ROWNUMBER);
          break;
        case RR:
          tok = jj_consume_token(RR);
          break;
        case RS:
          tok = jj_consume_token(RS);
          break;
        case SCALE:
          tok = jj_consume_token(SCALE);
          break;
        case SAVEPOINT:
          tok = jj_consume_token(SAVEPOINT);
          break;
        case SECOND_MICROSECOND:
          tok = jj_consume_token(SECOND_MICROSECOND);
          break;
        case SECURITY:
          tok = jj_consume_token(SECURITY);
          break;
        case SEPARATOR:
          tok = jj_consume_token(SEPARATOR);
          break;
        case SERVER:
          tok = jj_consume_token(SERVER);
          break;
        case SEQUENCE:
          tok = jj_consume_token(SEQUENCE);
          break;
        case SEQUENTIAL:
          tok = jj_consume_token(SEQUENTIAL);
          break;
        case SERIALIZABLE:
          tok = jj_consume_token(SERIALIZABLE);
          break;
        case SESSION:
          tok = jj_consume_token(SESSION);
          break;
        case SETS:
          tok = jj_consume_token(SETS);
          break;
        case SHARE:
          tok = jj_consume_token(SHARE);
          break;
        case SHUTDOWN:
          tok = jj_consume_token(SHUTDOWN);
          break;
        case SPACE:
          tok = jj_consume_token(SPACE);
          break;
        case SPECIFIC:
          tok = jj_consume_token(SPECIFIC);
          break;
        case SQLID:
          tok = jj_consume_token(SQLID);
          break;
        case SQL_TSI_DAY:
          tok = jj_consume_token(SQL_TSI_DAY);
          break;
        case SQL_TSI_FRAC_SECOND:
          tok = jj_consume_token(SQL_TSI_FRAC_SECOND);
          break;
        case SQL_TSI_HOUR:
          tok = jj_consume_token(SQL_TSI_HOUR);
          break;
        case SQL_TSI_MINUTE:
          tok = jj_consume_token(SQL_TSI_MINUTE);
          break;
        case SQL_TSI_MONTH:
          tok = jj_consume_token(SQL_TSI_MONTH);
          break;
        case SQL_TSI_QUARTER:
          tok = jj_consume_token(SQL_TSI_QUARTER);
          break;
        case SQL_TSI_SECOND:
          tok = jj_consume_token(SQL_TSI_SECOND);
          break;
        case SQL_TSI_WEEK:
          tok = jj_consume_token(SQL_TSI_WEEK);
          break;
        case SQL_TSI_YEAR:
          tok = jj_consume_token(SQL_TSI_YEAR);
          break;
        case SQRT:
          tok = jj_consume_token(SQRT);
          break;
        case STABILITY:
          tok = jj_consume_token(STABILITY);
          break;
        case START:
          tok = jj_consume_token(START);
          break;
        case STATEMENT:
          tok = jj_consume_token(STATEMENT);
          break;
        case STATISTICS:
          tok = jj_consume_token(STATISTICS);
          break;
        case STDIN:
          tok = jj_consume_token(STDIN);
          break;
        case STDOUT:
          tok = jj_consume_token(STDOUT);
          break;
        case STRIP:
          tok = jj_consume_token(STRIP);
          break;
        case STYLE:
          tok = jj_consume_token(STYLE);
          break;
        case SYNONYM:
          tok = jj_consume_token(SYNONYM);
          break;
        case T:
          tok = jj_consume_token(T);
          break;
        case TEMPORARY:
          tok = jj_consume_token(TEMPORARY);
          break;
        case TEXT:
          tok = jj_consume_token(TEXT);
          break;
        case THEN:
          tok = jj_consume_token(THEN);
          break;
        case TIME:
          tok = jj_consume_token(TIME);
          break;
        case TIMESTAMP:
          tok = jj_consume_token(TIMESTAMP);
          break;
        case TIMESTAMPADD:
          tok = jj_consume_token(TIMESTAMPADD);
          break;
        case TIMESTAMPDIFF:
          tok = jj_consume_token(TIMESTAMPDIFF);
          break;
        case TINYBLOB:
          tok = jj_consume_token(TINYBLOB);
          break;
        case TINYINT:
          tok = jj_consume_token(TINYINT);
          break;
        case TINYTEXT:
          tok = jj_consume_token(TINYTEXT);
          break;
        case TRANSACTION:
          tok = jj_consume_token(TRANSACTION);
          break;
        case TRIGGER:
          tok = jj_consume_token(TRIGGER);
          break;
        case TRUNCATE:
          tok = jj_consume_token(TRUNCATE);
          break;
        case TRUNCATE_END:
          tok = jj_consume_token(TRUNCATE_END);
          break;
        case TS:
          tok = jj_consume_token(TS);
          break;
        case TYPE:
          tok = jj_consume_token(TYPE);
          break;
        case UCASE:
          tok = jj_consume_token(UCASE);
          break;
        case UNCOMMITTED:
          tok = jj_consume_token(UNCOMMITTED);
          break;
        case UNSIGNED:
          tok = jj_consume_token(UNSIGNED);
          break;
        case UR:
          tok = jj_consume_token(UR);
          break;
        case USAGE:
          tok = jj_consume_token(USAGE);
          break;
        case USE:
          tok = jj_consume_token(USE);
          break;
        case VIEW:
          tok = jj_consume_token(VIEW);
          break;
        case WORK:
          tok = jj_consume_token(WORK);
          break;
        case WRITE:
          tok = jj_consume_token(WRITE);
          break;
        case VALUE:
          tok = jj_consume_token(VALUE);
          break;
        case VARBINARY:
          tok = jj_consume_token(VARBINARY);
          break;
        case PARAMETER:
          tok = jj_consume_token(PARAMETER);
          break;
        case VERBOSE:
          tok = jj_consume_token(VERBOSE);
          break;
        case WEEK:
          tok = jj_consume_token(WEEK);
          break;
        case WHEN:
          tok = jj_consume_token(WHEN);
          break;
        case WHITESPACE:
          tok = jj_consume_token(WHITESPACE);
          break;
        case YEAR_MONTH:
          tok = jj_consume_token(YEAR_MONTH);
          break;
        default:
          jj_la1[472] = jj_gen;
          jj_consume_token(-1);
          throw new ParseException();
        }
      }
    }
        // Remember last identifier token and whether it was delimited.
        nextToLastTokenDelimitedIdentifier = lastTokenDelimitedIdentifier;
        lastTokenDelimitedIdentifier = Boolean.FALSE;
        nextToLastIdentifierToken = lastIdentifierToken;
        lastIdentifierToken = tok;
        {if (true) return tok.image;}
    throw new Error("Missing return statement in function");
  }

  final public String caseSensitiveIdentifierPlusReservedWords() throws ParseException {
    String str;
    if (jj_2_111(1)) {
      str = caseSensitiveIdentifier();
        {if (true) return str;}
    } else {
      switch (jj_nt.kind) {
      case ADD:
      case ALL:
      case ALLOCATE:
      case ALTER:
      case AND:
      case ANY:
      case ARE:
      case AS:
      case AT:
      case AUTHORIZATION:
      case AVG:
      case BEGIN:
      case BETWEEN:
      case BIT:
      case BOTH:
      case BY:
      case CASCADED:
      case CASE:
      case CAST:
      case CHAR:
      case CHARACTER_LENGTH:
      case CHAR_LENGTH:
      case CHECK:
      case CLOSE:
      case COLLATE:
      case COLUMN:
      case COMMIT:
      case CONNECT:
      case CONNECTION:
      case CONSTRAINT:
      case CONTINUE:
      case CONVERT:
      case CORRESPONDING:
      case CREATE:
      case CROSS:
      case CURRENT:
      case CURRENT_DATE:
      case CURRENT_TIME:
      case CURRENT_TIMESTAMP:
      case CURRENT_USER:
      case CURSOR:
      case DEALLOCATE:
      case DEC:
      case DECIMAL:
      case DECLARE:
      case _DEFAULT:
      case DELETE:
      case DESCRIBE:
      case DISCONNECT:
      case DISTINCT:
      case DOUBLE:
      case DROP:
      case ELSE:
      case END:
      case ENDEXEC:
      case ESCAPE:
      case EXCEPT:
      case EXEC:
      case EXECUTE:
      case EXISTS:
      case EXTERNAL:
      case FALSE:
      case FETCH:
      case FLOAT:
      case FOR:
      case FOREIGN:
      case FROM:
      case FULL:
      case FUNCTION:
      case GET:
      case GLOBAL:
      case GRANT:
      case GROUP:
      case GROUP_CONCAT:
      case HAVING:
      case HOUR:
      case IDENTITY:
      case IMMEDIATE:
      case IN:
      case INDEX:
      case INDICATOR:
      case INNER:
      case INPUT:
      case INSENSITIVE:
      case INSERT:
      case INT:
      case INTEGER:
      case INTERSECT:
      case INTO:
      case IS:
      case JOIN:
      case LEADING:
      case LEFT:
      case LIKE:
      case LOWER:
      case MATCH:
      case MAX:
      case MIN:
      case MINUTE:
      case NATIONAL:
      case NATURAL:
      case NCHAR:
      case NEXT:
      case NO:
      case NOT:
      case NULL:
      case NULLIF:
      case NUMERIC:
      case OCTET_LENGTH:
      case OF:
      case ON:
      case ONLY:
      case OPEN:
      case OR:
      case ORDER:
      case OUTER:
      case OUTPUT:
      case OVERLAPS:
      case PARTITION:
      case PREPARE:
      case PRIMARY:
      case PROCEDURE:
      case PUBLIC:
      case REAL:
      case REFERENCES:
      case RESTRICT:
      case REVOKE:
      case RIGHT:
      case ROLLBACK:
      case ROWS:
      case SCHEMA:
      case SCROLL:
      case SECOND:
      case SELECT:
      case SESSION_USER:
      case SET:
      case SMALLINT:
      case SOME:
      case SQL:
      case SQLCODE:
      case SQLERROR:
      case SQLSTATE:
      case SUBSTRING:
      case SUM:
      case SYSTEM_USER:
      case TABLE:
      case TIMEZONE_HOUR:
      case TIMEZONE_MINUTE:
      case TO:
      case TRANSLATE:
      case TRANSLATION:
      case TRAILING:
      case TRIM:
      case TRUE:
      case UNION:
      case UNIQUE:
      case UNKNOWN:
      case UPDATE:
      case UPPER:
      case USER:
      case USING:
      case VALUES:
      case VARCHAR:
      case VARYING:
      case WHENEVER:
      case WHERE:
      case WITH:
      case YEAR:
      case INOUT:
      case INTERVAL:
      case BOOLEAN:
      case CALL:
      case CURRENT_ROLE:
      case CURRENT_SCHEMA:
      case GET_CURRENT_CONNECTION:
      case GROUPING:
      case EXPLAIN:
      case LIMIT:
      case LTRIM:
      case NONE:
      case RETURNING:
      case RTRIM:
      case STRAIGHT_JOIN:
      case SUBSTR:
      case XML:
      case XMLEXISTS:
      case XMLPARSE:
      case XMLQUERY:
      case XMLSERIALIZE:
      case Z_ORDER_LAT_LON:
      case NVARCHAR:
      case OUT:
        str = reservedKeyword();
        {if (true) return str;}
        break;
      default:
        jj_la1[473] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public String caseInsensitiveIdentifierPlusReservedWords() throws ParseException, StandardException {
    String str;
    if (jj_2_112(1)) {
      str = identifier();
        {if (true) return str;}
    } else {
      switch (jj_nt.kind) {
      case ADD:
      case ALL:
      case ALLOCATE:
      case ALTER:
      case AND:
      case ANY:
      case ARE:
      case AS:
      case AT:
      case AUTHORIZATION:
      case AVG:
      case BEGIN:
      case BETWEEN:
      case BIT:
      case BOTH:
      case BY:
      case CASCADED:
      case CASE:
      case CAST:
      case CHAR:
      case CHARACTER_LENGTH:
      case CHAR_LENGTH:
      case CHECK:
      case CLOSE:
      case COLLATE:
      case COLUMN:
      case COMMIT:
      case CONNECT:
      case CONNECTION:
      case CONSTRAINT:
      case CONTINUE:
      case CONVERT:
      case CORRESPONDING:
      case CREATE:
      case CROSS:
      case CURRENT:
      case CURRENT_DATE:
      case CURRENT_TIME:
      case CURRENT_TIMESTAMP:
      case CURRENT_USER:
      case CURSOR:
      case DEALLOCATE:
      case DEC:
      case DECIMAL:
      case DECLARE:
      case _DEFAULT:
      case DELETE:
      case DESCRIBE:
      case DISCONNECT:
      case DISTINCT:
      case DOUBLE:
      case DROP:
      case ELSE:
      case END:
      case ENDEXEC:
      case ESCAPE:
      case EXCEPT:
      case EXEC:
      case EXECUTE:
      case EXISTS:
      case EXTERNAL:
      case FALSE:
      case FETCH:
      case FLOAT:
      case FOR:
      case FOREIGN:
      case FROM:
      case FULL:
      case FUNCTION:
      case GET:
      case GLOBAL:
      case GRANT:
      case GROUP:
      case GROUP_CONCAT:
      case HAVING:
      case HOUR:
      case IDENTITY:
      case IMMEDIATE:
      case IN:
      case INDEX:
      case INDICATOR:
      case INNER:
      case INPUT:
      case INSENSITIVE:
      case INSERT:
      case INT:
      case INTEGER:
      case INTERSECT:
      case INTO:
      case IS:
      case JOIN:
      case LEADING:
      case LEFT:
      case LIKE:
      case LOWER:
      case MATCH:
      case MAX:
      case MIN:
      case MINUTE:
      case NATIONAL:
      case NATURAL:
      case NCHAR:
      case NEXT:
      case NO:
      case NOT:
      case NULL:
      case NULLIF:
      case NUMERIC:
      case OCTET_LENGTH:
      case OF:
      case ON:
      case ONLY:
      case OPEN:
      case OR:
      case ORDER:
      case OUTER:
      case OUTPUT:
      case OVERLAPS:
      case PARTITION:
      case PREPARE:
      case PRIMARY:
      case PROCEDURE:
      case PUBLIC:
      case REAL:
      case REFERENCES:
      case RESTRICT:
      case REVOKE:
      case RIGHT:
      case ROLLBACK:
      case ROWS:
      case SCHEMA:
      case SCROLL:
      case SECOND:
      case SELECT:
      case SESSION_USER:
      case SET:
      case SMALLINT:
      case SOME:
      case SQL:
      case SQLCODE:
      case SQLERROR:
      case SQLSTATE:
      case SUBSTRING:
      case SUM:
      case SYSTEM_USER:
      case TABLE:
      case TIMEZONE_HOUR:
      case TIMEZONE_MINUTE:
      case TO:
      case TRANSLATE:
      case TRANSLATION:
      case TRAILING:
      case TRIM:
      case TRUE:
      case UNION:
      case UNIQUE:
      case UNKNOWN:
      case UPDATE:
      case UPPER:
      case USER:
      case USING:
      case VALUES:
      case VARCHAR:
      case VARYING:
      case WHENEVER:
      case WHERE:
      case WITH:
      case YEAR:
      case INOUT:
      case INTERVAL:
      case BOOLEAN:
      case CALL:
      case CURRENT_ROLE:
      case CURRENT_SCHEMA:
      case GET_CURRENT_CONNECTION:
      case GROUPING:
      case EXPLAIN:
      case LIMIT:
      case LTRIM:
      case NONE:
      case RETURNING:
      case RTRIM:
      case STRAIGHT_JOIN:
      case SUBSTR:
      case XML:
      case XMLEXISTS:
      case XMLPARSE:
      case XMLQUERY:
      case XMLSERIALIZE:
      case Z_ORDER_LAT_LON:
      case NVARCHAR:
      case OUT:
        str = reservedKeyword();
        {if (true) return SQLToIdentifierCase(str);}
        break;
      default:
        jj_la1[474] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public String caseSensitiveIdentifier() throws ParseException {
    String  str;
    Token       tok;
    switch (jj_nt.kind) {
    case IDENTIFIER:
      tok = jj_consume_token(IDENTIFIER);
        // Remember whether last token was a delimited identifier.
        nextToLastTokenDelimitedIdentifier = lastTokenDelimitedIdentifier;
        lastTokenDelimitedIdentifier = Boolean.FALSE;
        {if (true) return tok.image;}
      break;
    case BACKQUOTED_IDENTIFIER:
    case DOUBLEQUOTED_IDENTIFIER:
      str = delimitedIdentifier();
        {if (true) return str;}
      break;
    default:
      jj_la1[475] = jj_gen;
      if (jj_2_113(1)) {
        str = nonReservedKeyword();
        {if (true) return str;}
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
  }

  final public void groupIndexItemList(IndexColumnList columnList) throws ParseException, StandardException {
    if (getToken(1).kind == FULL_TEXT && getToken(2).kind == LEFT_PAREN) {
      fullTextColumnItemList(columnList);
    } else if (jj_2_114(1)) {
      groupIndexItem(columnList);
      label_56:
      while (true) {
        switch (jj_nt.kind) {
        case COMMA:
          ;
          break;
        default:
          jj_la1[476] = jj_gen;
          break label_56;
        }
        jj_consume_token(COMMA);
        groupIndexItem(columnList);
      }
    } else {
      jj_consume_token(-1);
      throw new ParseException();
    }
  }

  final public void groupIndexItem(IndexColumnList columnList) throws ParseException, StandardException {
    int latPosition;
    switch (jj_nt.kind) {
    case Z_ORDER_LAT_LON:
      jj_consume_token(Z_ORDER_LAT_LON);
      jj_consume_token(LEFT_PAREN);
        latPosition = columnList.size();
      unorderedGroupIndexColumnItem(columnList);
      jj_consume_token(COMMA);
      unorderedGroupIndexColumnItem(columnList);
      jj_consume_token(RIGHT_PAREN);
          columnList.applyFunction(IndexColumnList.FunctionType.Z_ORDER_LAT_LON,
                                   latPosition,
                                   2);
      break;
    default:
      jj_la1[477] = jj_gen;
      if (jj_2_115(1)) {
        groupIndexColumnItem(columnList);
      } else {
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
  }

  final public void fullTextColumnItemList(IndexColumnList columnList) throws ParseException, StandardException {
    jj_consume_token(FULL_TEXT);
    jj_consume_token(LEFT_PAREN);
    groupIndexColumnItem(columnList);
    label_57:
    while (true) {
      switch (jj_nt.kind) {
      case COMMA:
        ;
        break;
      default:
        jj_la1[478] = jj_gen;
        break label_57;
      }
      jj_consume_token(COMMA);
      groupIndexColumnItem(columnList);
    }
    jj_consume_token(RIGHT_PAREN);
          columnList.applyFunction(IndexColumnList.FunctionType.FULL_TEXT,
                                   0, columnList.size());
  }

  final public void groupIndexColumnItem(IndexColumnList columnList) throws ParseException, StandardException {
    boolean asc = true;
    ColumnReference groupIndexColumnName;
    groupIndexColumnName = groupIndexColumnName(columnList);
    switch (jj_nt.kind) {
    case ASC:
    case DESC:
      switch (jj_nt.kind) {
      case ASC:
        jj_consume_token(ASC);
        break;
      case DESC:
        jj_consume_token(DESC);
                       asc = false;
        break;
      default:
        jj_la1[479] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
      break;
    default:
      jj_la1[480] = jj_gen;
      ;
    }
        IndexColumn indexColumn = (IndexColumn)
            nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                groupIndexColumnName.getTableNameNode(),
                                groupIndexColumnName.getColumnName(),
                                asc ? Boolean.TRUE : Boolean.FALSE,
                                parserContext);
        columnList.add(indexColumn);
  }

  final public void unorderedGroupIndexColumnItem(IndexColumnList columnList) throws ParseException, StandardException {
    ColumnReference groupIndexColumnName;
    groupIndexColumnName = groupIndexColumnName(columnList);
        IndexColumn indexColumn = (IndexColumn)
            nodeFactory.getNode(NodeTypes.INDEX_COLUMN,
                                groupIndexColumnName.getTableNameNode(),
                                groupIndexColumnName.getColumnName(),
                                Boolean.TRUE,
                                parserContext);
        columnList.add(indexColumn);
  }

  final public ColumnReference groupIndexColumnName(IndexColumnList columnList) throws ParseException, StandardException {
    String firstName;
    String secondName = null;
    String thirdName = null;
    firstName = identifierDeferCheckLength();
    if (getToken(1).kind == PERIOD) {
      jj_consume_token(PERIOD);
      secondName = identifierDeferCheckLength();
      if (getToken(1).kind == PERIOD) {
        jj_consume_token(PERIOD);
        thirdName = identifierDeferCheckLength();
      } else {
        ;
      }
    } else {
      ;
    }
        if (secondName == null) {
            thirdName = firstName;
            firstName = null;
        }
        else if (thirdName == null) {
            thirdName = secondName;
            secondName = firstName;
            firstName = null;
        }
        if (firstName != null)
            parserContext.checkIdentifierLengthLimit(firstName);
        if (secondName != null)
            parserContext.checkIdentifierLengthLimit(secondName);
        parserContext.checkIdentifierLengthLimit(thirdName);
        TableName tableName = null;
        if (secondName != null)
            tableName = (TableName)nodeFactory.getNode(NodeTypes.TABLE_NAME,
                                                       firstName,
                                                       secondName,
                                                       new Integer(nextToLastIdentifierToken.beginOffset),
                                                       new Integer(nextToLastIdentifierToken.endOffset),
                                                       parserContext);
        {if (true) return (ColumnReference)nodeFactory.getNode(NodeTypes.COLUMN_REFERENCE,
                                                    thirdName,
                                                    tableName,
                                                    new Integer(lastIdentifierToken.beginOffset),
                                                    new Integer(lastIdentifierToken.endOffset),
                                                    parserContext);}
    throw new Error("Missing return statement in function");
  }

  private boolean jj_2_1(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_1(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(0, xla); }
  }

  private boolean jj_2_2(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_2(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(1, xla); }
  }

  private boolean jj_2_3(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_3(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(2, xla); }
  }

  private boolean jj_2_4(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_4(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(3, xla); }
  }

  private boolean jj_2_5(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_5(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(4, xla); }
  }

  private boolean jj_2_6(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_6(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(5, xla); }
  }

  private boolean jj_2_7(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_7(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(6, xla); }
  }

  private boolean jj_2_8(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_8(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(7, xla); }
  }

  private boolean jj_2_9(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_9(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(8, xla); }
  }

  private boolean jj_2_10(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_10(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(9, xla); }
  }

  private boolean jj_2_11(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_11(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(10, xla); }
  }

  private boolean jj_2_12(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_12(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(11, xla); }
  }

  private boolean jj_2_13(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_13(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(12, xla); }
  }

  private boolean jj_2_14(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_14(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(13, xla); }
  }

  private boolean jj_2_15(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_15(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(14, xla); }
  }

  private boolean jj_2_16(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_16(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(15, xla); }
  }

  private boolean jj_2_17(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_17(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(16, xla); }
  }

  private boolean jj_2_18(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_18(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(17, xla); }
  }

  private boolean jj_2_19(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_19(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(18, xla); }
  }

  private boolean jj_2_20(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_20(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(19, xla); }
  }

  private boolean jj_2_21(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_21(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(20, xla); }
  }

  private boolean jj_2_22(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_22(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(21, xla); }
  }

  private boolean jj_2_23(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_23(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(22, xla); }
  }

  private boolean jj_2_24(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_24(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(23, xla); }
  }

  private boolean jj_2_25(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_25(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(24, xla); }
  }

  private boolean jj_2_26(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_26(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(25, xla); }
  }

  private boolean jj_2_27(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_27(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(26, xla); }
  }

  private boolean jj_2_28(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_28(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(27, xla); }
  }

  private boolean jj_2_29(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_29(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(28, xla); }
  }

  private boolean jj_2_30(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_30(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(29, xla); }
  }

  private boolean jj_2_31(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_31(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(30, xla); }
  }

  private boolean jj_2_32(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_32(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(31, xla); }
  }

  private boolean jj_2_33(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_33(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(32, xla); }
  }

  private boolean jj_2_34(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_34(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(33, xla); }
  }

  private boolean jj_2_35(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_35(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(34, xla); }
  }

  private boolean jj_2_36(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_36(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(35, xla); }
  }

  private boolean jj_2_37(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_37(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(36, xla); }
  }

  private boolean jj_2_38(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_38(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(37, xla); }
  }

  private boolean jj_2_39(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_39(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(38, xla); }
  }

  private boolean jj_2_40(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_40(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(39, xla); }
  }

  private boolean jj_2_41(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_41(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(40, xla); }
  }

  private boolean jj_2_42(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_42(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(41, xla); }
  }

  private boolean jj_2_43(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_43(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(42, xla); }
  }

  private boolean jj_2_44(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_44(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(43, xla); }
  }

  private boolean jj_2_45(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_45(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(44, xla); }
  }

  private boolean jj_2_46(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_46(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(45, xla); }
  }

  private boolean jj_2_47(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_47(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(46, xla); }
  }

  private boolean jj_2_48(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_48(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(47, xla); }
  }

  private boolean jj_2_49(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_49(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(48, xla); }
  }

  private boolean jj_2_50(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_50(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(49, xla); }
  }

  private boolean jj_2_51(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_51(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(50, xla); }
  }

  private boolean jj_2_52(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_52(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(51, xla); }
  }

  private boolean jj_2_53(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_53(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(52, xla); }
  }

  private boolean jj_2_54(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_54(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(53, xla); }
  }

  private boolean jj_2_55(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_55(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(54, xla); }
  }

  private boolean jj_2_56(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_56(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(55, xla); }
  }

  private boolean jj_2_57(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_57(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(56, xla); }
  }

  private boolean jj_2_58(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_58(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(57, xla); }
  }

  private boolean jj_2_59(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_59(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(58, xla); }
  }

  private boolean jj_2_60(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_60(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(59, xla); }
  }

  private boolean jj_2_61(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_61(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(60, xla); }
  }

  private boolean jj_2_62(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_62(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(61, xla); }
  }

  private boolean jj_2_63(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_63(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(62, xla); }
  }

  private boolean jj_2_64(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_64(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(63, xla); }
  }

  private boolean jj_2_65(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_65(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(64, xla); }
  }

  private boolean jj_2_66(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_66(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(65, xla); }
  }

  private boolean jj_2_67(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_67(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(66, xla); }
  }

  private boolean jj_2_68(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_68(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(67, xla); }
  }

  private boolean jj_2_69(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_69(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(68, xla); }
  }

  private boolean jj_2_70(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_70(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(69, xla); }
  }

  private boolean jj_2_71(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_71(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(70, xla); }
  }

  private boolean jj_2_72(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_72(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(71, xla); }
  }

  private boolean jj_2_73(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_73(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(72, xla); }
  }

  private boolean jj_2_74(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_74(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(73, xla); }
  }

  private boolean jj_2_75(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_75(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(74, xla); }
  }

  private boolean jj_2_76(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_76(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(75, xla); }
  }

  private boolean jj_2_77(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_77(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(76, xla); }
  }

  private boolean jj_2_78(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_78(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(77, xla); }
  }

  private boolean jj_2_79(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_79(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(78, xla); }
  }

  private boolean jj_2_80(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_80(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(79, xla); }
  }

  private boolean jj_2_81(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_81(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(80, xla); }
  }

  private boolean jj_2_82(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_82(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(81, xla); }
  }

  private boolean jj_2_83(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_83(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(82, xla); }
  }

  private boolean jj_2_84(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_84(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(83, xla); }
  }

  private boolean jj_2_85(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_85(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(84, xla); }
  }

  private boolean jj_2_86(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_86(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(85, xla); }
  }

  private boolean jj_2_87(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_87(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(86, xla); }
  }

  private boolean jj_2_88(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_88(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(87, xla); }
  }

  private boolean jj_2_89(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_89(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(88, xla); }
  }

  private boolean jj_2_90(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_90(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(89, xla); }
  }

  private boolean jj_2_91(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_91(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(90, xla); }
  }

  private boolean jj_2_92(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_92(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(91, xla); }
  }

  private boolean jj_2_93(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_93(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(92, xla); }
  }

  private boolean jj_2_94(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_94(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(93, xla); }
  }

  private boolean jj_2_95(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_95(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(94, xla); }
  }

  private boolean jj_2_96(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_96(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(95, xla); }
  }

  private boolean jj_2_97(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_97(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(96, xla); }
  }

  private boolean jj_2_98(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_98(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(97, xla); }
  }

  private boolean jj_2_99(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_99(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(98, xla); }
  }

  private boolean jj_2_100(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_100(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(99, xla); }
  }

  private boolean jj_2_101(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_101(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(100, xla); }
  }

  private boolean jj_2_102(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_102(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(101, xla); }
  }

  private boolean jj_2_103(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_103(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(102, xla); }
  }

  private boolean jj_2_104(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_104(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(103, xla); }
  }

  private boolean jj_2_105(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_105(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(104, xla); }
  }

  private boolean jj_2_106(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_106(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(105, xla); }
  }

  private boolean jj_2_107(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_107(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(106, xla); }
  }

  private boolean jj_2_108(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_108(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(107, xla); }
  }

  private boolean jj_2_109(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_109(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(108, xla); }
  }

  private boolean jj_2_110(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_110(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(109, xla); }
  }

  private boolean jj_2_111(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_111(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(110, xla); }
  }

  private boolean jj_2_112(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_112(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(111, xla); }
  }

  private boolean jj_2_113(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_113(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(112, xla); }
  }

  private boolean jj_2_114(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_114(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(113, xla); }
  }

  private boolean jj_2_115(int xla) {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return !jj_3_115(); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(114, xla); }
  }

  private boolean jj_3R_277() {
    if (jj_3R_153()) return true;
    return false;
  }

  private boolean jj_3_84() {
    if (jj_3R_125()) return true;
    return false;
  }

  private boolean jj_3R_289() {
    if (jj_3R_360()) return true;
    return false;
  }

  private boolean jj_3R_288() {
    if (jj_3R_359()) return true;
    return false;
  }

  private boolean jj_3R_98() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_287() {
    if (jj_3R_358()) return true;
    return false;
  }

  private boolean jj_3R_411() {
    if (jj_scan_token(LEFT_PAREN)) return true;
    return false;
  }

  private boolean jj_3_4() {
    if (jj_3R_61()) return true;
    return false;
  }

  private boolean jj_3_3() {
    if (jj_3R_60()) return true;
    return false;
  }

  private boolean jj_3R_286() {
    if (jj_3R_357()) return true;
    return false;
  }

  private boolean jj_3_106() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3_2() {
    if (jj_3R_59()) return true;
    return false;
  }

  private boolean jj_3_56() {
    if (jj_3R_104()) return true;
    return false;
  }

  private boolean jj_3R_285() {
    if (jj_3R_356()) return true;
    return false;
  }

  private boolean jj_3R_284() {
    if (jj_3R_355()) return true;
    return false;
  }

  private boolean jj_3R_283() {
    if (jj_3R_354()) return true;
    return false;
  }

  private boolean jj_3R_282() {
    if (jj_3R_353()) return true;
    return false;
  }

  private boolean jj_3R_281() {
    if (jj_3R_352()) return true;
    return false;
  }

  private boolean jj_3R_280() {
    if (jj_3R_351()) return true;
    return false;
  }

  private boolean jj_3R_143() {
    if (jj_3R_277()) return true;
    return false;
  }

  private boolean jj_3R_279() {
    if (jj_3R_350()) return true;
    return false;
  }

  private boolean jj_3R_278() {
    if (jj_3R_349()) return true;
    return false;
  }

  private boolean jj_3R_273() {
    if (jj_scan_token(CHECK)) return true;
    return false;
  }

  private boolean jj_3R_144() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_278()) {
    jj_scanpos = xsp;
    if (jj_3R_279()) {
    jj_scanpos = xsp;
    if (jj_3R_280()) {
    jj_scanpos = xsp;
    if (jj_3R_281()) {
    jj_scanpos = xsp;
    if (jj_3R_282()) {
    jj_scanpos = xsp;
    if (jj_3R_283()) {
    jj_scanpos = xsp;
    if (jj_3R_284()) {
    jj_scanpos = xsp;
    if (jj_3R_285()) {
    jj_scanpos = xsp;
    if (jj_3_2()) {
    jj_scanpos = xsp;
    if (jj_3R_286()) {
    jj_scanpos = xsp;
    if (jj_3_3()) {
    jj_scanpos = xsp;
    if (jj_3_4()) {
    jj_scanpos = xsp;
    if (jj_3R_287()) {
    jj_scanpos = xsp;
    if (jj_3R_288()) {
    jj_scanpos = xsp;
    if (jj_3R_289()) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_438() {
    if (jj_scan_token(UR)) return true;
    return false;
  }

  private boolean jj_3_83() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3_70() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_437() {
    if (jj_scan_token(CS)) return true;
    return false;
  }

  private boolean jj_3R_272() {
    if (jj_scan_token(UNIQUE)) return true;
    return false;
  }

  private boolean jj_3R_436() {
    if (jj_scan_token(RS)) return true;
    return false;
  }

  private boolean jj_3_115() {
    if (jj_3R_143()) return true;
    return false;
  }

  private boolean jj_3R_435() {
    if (jj_scan_token(RR)) return true;
    return false;
  }

  private boolean jj_3R_346() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_435()) {
    jj_scanpos = xsp;
    if (jj_3R_436()) {
    jj_scanpos = xsp;
    if (jj_3R_437()) {
    jj_scanpos = xsp;
    if (jj_3R_438()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_271() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(377)) jj_scanpos = xsp;
    if (jj_scan_token(FOREIGN)) return true;
    return false;
  }

  private boolean jj_3R_142() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_276()) {
    jj_scanpos = xsp;
    if (jj_3_115()) return true;
    }
    return false;
  }

  private boolean jj_3R_276() {
    if (jj_scan_token(Z_ORDER_LAT_LON)) return true;
    return false;
  }

  private boolean jj_3R_268() {
    if (jj_scan_token(READ)) return true;
    return false;
  }

  private boolean jj_3R_267() {
    if (jj_scan_token(READ)) return true;
    return false;
  }

  private boolean jj_3_40() {
    if (jj_3R_92()) return true;
    return false;
  }

  private boolean jj_3R_270() {
    if (jj_scan_token(PRIMARY)) return true;
    return false;
  }

  private boolean jj_3_114() {
    if (jj_3R_142()) return true;
    return false;
  }

  private boolean jj_3R_266() {
    if (jj_scan_token(DIRTY)) return true;
    return false;
  }

  private boolean jj_3_1() {
    if (jj_3R_58()) return true;
    return false;
  }

  private boolean jj_3R_265() {
    if (jj_scan_token(CURSOR)) return true;
    return false;
  }

  private boolean jj_3R_58() {
    if (jj_3R_144()) return true;
    return false;
  }

  private boolean jj_3R_347() {
    if (jj_scan_token(REPEATABLE)) return true;
    return false;
  }

  private boolean jj_3R_236() {
    if (jj_scan_token(TABLE)) return true;
    return false;
  }

  private boolean jj_3R_264() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_347()) {
    jj_scanpos = xsp;
    if (jj_scan_token(346)) return true;
    }
    return false;
  }

  private boolean jj_3_113() {
    if (jj_3R_140()) return true;
    return false;
  }

  private boolean jj_3R_138() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == CONSTRAINT;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_269()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == PRIMARY;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_270()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = (getToken(1).kind == FOREIGN) ||
                 (groupConstructFollows(GROUPING) && (getToken(2).kind == FOREIGN));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_271()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == UNIQUE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_272()) {
    jj_scanpos = xsp;
    if (jj_3R_273()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_269() {
    if (jj_scan_token(CONSTRAINT)) return true;
    return false;
  }

  private boolean jj_3R_530() {
    if (jj_scan_token(MINUTE)) return true;
    return false;
  }

  private boolean jj_3R_263() {
    if (jj_3R_346()) return true;
    return false;
  }

  private boolean jj_3R_275() {
    if (jj_3R_348()) return true;
    return false;
  }

  private boolean jj_3R_529() {
    if (jj_scan_token(HOUR)) return true;
    return false;
  }

  private boolean jj_3R_132() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_263()) {
    jj_scanpos = xsp;
    if (jj_3R_264()) {
    jj_scanpos = xsp;
    if (jj_3R_265()) {
    jj_scanpos = xsp;
    if (jj_3R_266()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == READ && getToken(2).kind == COMMITTED;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_267()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == READ && getToken(2).kind == UNCOMMITTED;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_268()) return true;
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_528() {
    if (jj_scan_token(DAY)) return true;
    return false;
  }

  private boolean jj_3R_141() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_274()) {
    jj_scanpos = xsp;
    if (jj_3R_275()) {
    jj_scanpos = xsp;
    if (jj_3_113()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_274() {
    if (jj_scan_token(IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3R_486() {
    if (jj_3R_507()) return true;
    return false;
  }

  private boolean jj_3R_527() {
    if (jj_scan_token(MONTH)) return true;
    return false;
  }

  private boolean jj_3_92() {
    if (jj_3R_132()) return true;
    return false;
  }

  private boolean jj_3R_526() {
    if (jj_scan_token(YEAR)) return true;
    return false;
  }

  private boolean jj_3R_508() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_526()) {
    jj_scanpos = xsp;
    if (jj_3R_527()) {
    jj_scanpos = xsp;
    if (jj_3R_528()) {
    jj_scanpos = xsp;
    if (jj_3R_529()) {
    jj_scanpos = xsp;
    if (jj_3R_530()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_507() {
    if (jj_3R_196()) return true;
    return false;
  }

  private boolean jj_3_112() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_91() {
    if (jj_3R_196()) return true;
    return false;
  }

  private boolean jj_3R_73() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_315() {
    if (jj_3R_381()) return true;
    return false;
  }

  private boolean jj_3R_196() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_111()) {
    jj_scanpos = xsp;
    if (jj_3R_315()) return true;
    }
    return false;
  }

  private boolean jj_3_111() {
    if (jj_3R_141()) return true;
    return false;
  }

  private boolean jj_3R_235() {
    if (jj_scan_token(NEW)) return true;
    return false;
  }

  private boolean jj_3R_290() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3_39() {
    if (jj_3R_91()) return true;
    return false;
  }

  private boolean jj_3R_151() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(176)) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == CURRENT && getToken(2).kind == ISOLATION;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_290()) return true;
    }
    return false;
  }

  private boolean jj_3R_62() {
    if (jj_3R_151()) return true;
    return false;
  }

  private boolean jj_3R_462() {
    if (jj_scan_token(CURRENT_SCHEMA)) return true;
    return false;
  }

  private boolean jj_3R_327() {
    if (jj_scan_token(LEFT_PAREN)) return true;
    return false;
  }

  private boolean jj_3R_361() {
    if (jj_3R_454()) return true;
    return false;
  }

  private boolean jj_3R_335() {
    if (jj_scan_token(CURRENT_ROLE)) return true;
    return false;
  }

  private boolean jj_3R_503() {
    if (jj_scan_token(XML)) return true;
    return false;
  }

  private boolean jj_3_38() {
    if (jj_3R_90()) return true;
    return false;
  }

  private boolean jj_3R_421() {
    if (jj_scan_token(SESSION_USER)) return true;
    return false;
  }

  private boolean jj_3R_154() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = javaClassFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_292()) {
    jj_scanpos = xsp;
    if (jj_3_38()) return true;
    }
    return false;
  }

  private boolean jj_3R_292() {
    if (jj_3R_361()) return true;
    return false;
  }

  private boolean jj_3R_326() {
    if (jj_3R_411()) return true;
    return false;
  }

  private boolean jj_3R_350() {
    if (jj_scan_token(LOCK)) return true;
    return false;
  }

  private boolean jj_3R_420() {
    if (jj_scan_token(CURRENT_USER)) return true;
    return false;
  }

  private boolean jj_3R_334() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_419()) {
    jj_scanpos = xsp;
    if (jj_3R_420()) {
    jj_scanpos = xsp;
    if (jj_3R_421()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_419() {
    if (jj_scan_token(USER)) return true;
    return false;
  }

  private boolean jj_3R_104() {
    if (jj_3R_93()) return true;
    return false;
  }

  private boolean jj_3R_394() {
    if (jj_3R_462()) return true;
    return false;
  }

  private boolean jj_3R_501() {
    if (jj_scan_token(LONG)) return true;
    return false;
  }

  private boolean jj_3R_393() {
    if (jj_3R_335()) return true;
    return false;
  }

  private boolean jj_3R_136() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(97)) jj_scanpos = xsp;
    if (jj_3R_69()) return true;
    return false;
  }

  private boolean jj_3_69() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_392() {
    if (jj_3R_334()) return true;
    return false;
  }

  private boolean jj_3R_172() {
    if (jj_scan_token(DOUBLE)) return true;
    return false;
  }

  private boolean jj_3R_318() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_391()) {
    jj_scanpos = xsp;
    if (jj_3R_392()) {
    jj_scanpos = xsp;
    if (jj_3R_393()) {
    jj_scanpos = xsp;
    if (jj_3R_394()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_391() {
    if (jj_3R_461()) return true;
    return false;
  }

  private boolean jj_3R_80() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == PRECISION;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_172()) {
    jj_scanpos = xsp;
    if (jj_scan_token(129)) return true;
    }
    return false;
  }

  private boolean jj_3R_115() {
    if (jj_3R_236()) return true;
    return false;
  }

  private boolean jj_3R_114() {
    if (jj_3R_235()) return true;
    return false;
  }

  private boolean jj_3_28() {
    if (jj_3R_80()) return true;
    return false;
  }

  private boolean jj_3R_426() {
    if (jj_scan_token(FALSE)) return true;
    return false;
  }

  private boolean jj_3_68() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = newInvocationFollows(1);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_114()) {
    jj_scanpos = xsp;
    if (jj_3R_115()) return true;
    }
    return false;
  }

  private boolean jj_3R_233() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_68()) {
    jj_scanpos = xsp;
    if (jj_3_69()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == LEFT_PAREN &&
                                 (getToken(2).kind == SELECT || getToken(2).kind == VALUES);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_326()) {
    jj_scanpos = xsp;
    if (jj_3R_327()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_171() {
    if (jj_scan_token(REAL)) return true;
    return false;
  }

  private boolean jj_3R_340() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_425()) {
    jj_scanpos = xsp;
    if (jj_3R_426()) return true;
    }
    return false;
  }

  private boolean jj_3R_425() {
    if (jj_scan_token(TRUE)) return true;
    return false;
  }

  private boolean jj_3R_112() {
    if (jj_3R_233()) return true;
    return false;
  }

  private boolean jj_3R_310() {
    if (jj_scan_token(PERIOD)) return true;
    return false;
  }

  private boolean jj_3R_170() {
    if (jj_scan_token(FLOAT)) return true;
    return false;
  }

  private boolean jj_3R_79() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_170()) {
    jj_scanpos = xsp;
    if (jj_3R_171()) {
    jj_scanpos = xsp;
    if (jj_3_28()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_402() {
    if (jj_3R_465()) return true;
    return false;
  }

  private boolean jj_3R_234() {
    if (jj_scan_token(PRIMARY)) return true;
    return false;
  }

  private boolean jj_3R_113() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_67()) {
    jj_scanpos = xsp;
    if (jj_3R_234()) return true;
    }
    return false;
  }

  private boolean jj_3_67() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_349() {
    if (jj_scan_token(RENAME)) return true;
    return false;
  }

  private boolean jj_3R_401() {
    if (jj_3R_464()) return true;
    return false;
  }

  private boolean jj_3R_409() {
    if (jj_scan_token(LONGINT)) return true;
    return false;
  }

  private boolean jj_3R_400() {
    if (jj_scan_token(TIMESTAMP)) return true;
    return false;
  }

  private boolean jj_3R_408() {
    if (jj_scan_token(SMALLINT)) return true;
    return false;
  }

  private boolean jj_3R_407() {
    if (jj_scan_token(TINYINT)) return true;
    return false;
  }

  private boolean jj_3R_186() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(3).kind == LEFT_PAREN;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_309()) {
    jj_scanpos = xsp;
    if (jj_3R_310()) return true;
    }
    return false;
  }

  private boolean jj_3R_309() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(536)) {
    jj_scanpos = xsp;
    if (jj_scan_token(518)) return true;
    }
    return false;
  }

  private boolean jj_3R_399() {
    if (jj_scan_token(DATE)) return true;
    return false;
  }

  private boolean jj_3R_406() {
    if (jj_scan_token(MEDIUMINT)) return true;
    return false;
  }

  private boolean jj_3R_398() {
    if (jj_scan_token(TIME)) return true;
    return false;
  }

  private boolean jj_3R_324() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_405()) {
    jj_scanpos = xsp;
    if (jj_3R_406()) {
    jj_scanpos = xsp;
    if (jj_3R_407()) {
    jj_scanpos = xsp;
    if (jj_3R_408()) {
    jj_scanpos = xsp;
    if (jj_3R_409()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_405() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(172)) {
    jj_scanpos = xsp;
    if (jj_scan_token(171)) return true;
    }
    return false;
  }

  private boolean jj_3R_89() {
    if (jj_3R_186()) return true;
    return false;
  }

  private boolean jj_3R_322() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_397()) {
    jj_scanpos = xsp;
    if (jj_3R_398()) {
    jj_scanpos = xsp;
    if (jj_3R_399()) {
    jj_scanpos = xsp;
    if (jj_3R_400()) {
    jj_scanpos = xsp;
    if (jj_3R_401()) {
    jj_scanpos = xsp;
    if (jj_3R_402()) return true;
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_397() {
    if (jj_scan_token(EXTRACT)) return true;
    return false;
  }

  private boolean jj_3R_369() {
    if (jj_3R_324()) return true;
    return false;
  }

  private boolean jj_3_37() {
    if (jj_3R_89()) return true;
    return false;
  }

  private boolean jj_3R_66() {
    if (jj_3R_154()) return true;
    return false;
  }

  private boolean jj_3_66() {
    if (jj_3R_113()) return true;
    return false;
  }

  private boolean jj_3R_137() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(97)) jj_scanpos = xsp;
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_185() {
    if (jj_scan_token(TILDE)) return true;
    return false;
  }

  private boolean jj_3R_224() {
    if (jj_3R_324()) return true;
    return false;
  }

  private boolean jj_3R_101() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_55()) {
    jj_scanpos = xsp;
    if (jj_3R_224()) return true;
    }
    return false;
  }

  private boolean jj_3_55() {
    if (jj_3R_80()) return true;
    return false;
  }

  private boolean jj_3R_139() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(250)) {
    jj_scanpos = xsp;
    if (jj_scan_token(72)) return true;
    }
    return false;
  }

  private boolean jj_3R_308() {
    if (jj_scan_token(MINUS_SIGN)) return true;
    return false;
  }

  private boolean jj_3R_458() {
    if (jj_scan_token(NUMERIC)) return true;
    return false;
  }

  private boolean jj_3R_184() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_307()) {
    jj_scanpos = xsp;
    if (jj_3R_308()) return true;
    }
    return false;
  }

  private boolean jj_3R_307() {
    if (jj_scan_token(PLUS_SIGN)) return true;
    return false;
  }

  private boolean jj_3R_304() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_368()) {
    jj_scanpos = xsp;
    if (jj_3R_369()) return true;
    }
    return false;
  }

  private boolean jj_3R_368() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_458()) {
    jj_scanpos = xsp;
    if (jj_scan_token(118)) {
    jj_scanpos = xsp;
    if (jj_scan_token(117)) return true;
    }
    }
    return false;
  }

  private boolean jj_3_105() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(246)) jj_scanpos = xsp;
    xsp = jj_scanpos;
    if (jj_3R_139()) jj_scanpos = xsp;
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_364() {
    if (jj_scan_token(CHECK)) return true;
    return false;
  }

  private boolean jj_3_104() {
    if (jj_3R_138()) return true;
    return false;
  }

  private boolean jj_3_27() {
    if (jj_3R_79()) return true;
    return false;
  }

  private boolean jj_3_103() {
    if (jj_3R_137()) return true;
    return false;
  }

  private boolean jj_3R_169() {
    if (jj_3R_304()) return true;
    return false;
  }

  private boolean jj_3R_228() {
    if (jj_scan_token(RETURNING)) return true;
    return false;
  }

  private boolean jj_3R_78() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_169()) {
    jj_scanpos = xsp;
    if (jj_3_27()) return true;
    }
    return false;
  }

  private boolean jj_3R_88() {
    if (jj_3R_185()) return true;
    return false;
  }

  private boolean jj_3R_227() {
    if (jj_scan_token(RETURNING)) return true;
    return false;
  }

  private boolean jj_3R_103() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == SEQUENCE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_227()) {
    jj_scanpos = xsp;
    if (jj_3R_228()) return true;
    }
    return false;
  }

  private boolean jj_3R_87() {
    if (jj_3R_184()) return true;
    return false;
  }

  private boolean jj_3_36() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = unaryArithmeticFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_87()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = unaryBitFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_88()) return true;
    }
    return false;
  }

  private boolean jj_3R_295() {
    if (jj_scan_token(CONSTRAINT)) return true;
    return false;
  }

  private boolean jj_3R_305() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_36()) jj_scanpos = xsp;
    if (jj_3R_370()) return true;
    return false;
  }

  private boolean jj_3_65() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(72)) jj_scanpos = xsp;
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3_102() {
    if (jj_3R_70()) return true;
    return false;
  }

  private boolean jj_3R_523() {
    if (jj_scan_token(LONGTEXT)) return true;
    return false;
  }

  private boolean jj_3_101() {
    if (jj_3R_136()) return true;
    return false;
  }

  private boolean jj_3R_522() {
    if (jj_scan_token(LONGBLOB)) return true;
    return false;
  }

  private boolean jj_3R_183() {
    if (jj_scan_token(CONCATENATION_OPERATOR)) return true;
    return false;
  }

  private boolean jj_3R_521() {
    if (jj_scan_token(MEDIUMTEXT)) return true;
    return false;
  }

  private boolean jj_3R_140() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(278)) {
    jj_scanpos = xsp;
    if (jj_scan_token(279)) {
    jj_scanpos = xsp;
    if (jj_scan_token(280)) {
    jj_scanpos = xsp;
    if (jj_scan_token(396)) {
    jj_scanpos = xsp;
    if (jj_scan_token(281)) {
    jj_scanpos = xsp;
    if (jj_scan_token(73)) {
    jj_scanpos = xsp;
    if (jj_scan_token(74)) {
    jj_scanpos = xsp;
    if (jj_scan_token(397)) {
    jj_scanpos = xsp;
    if (jj_scan_token(80)) {
    jj_scanpos = xsp;
    if (jj_scan_token(282)) {
    jj_scanpos = xsp;
    if (jj_scan_token(398)) {
    jj_scanpos = xsp;
    if (jj_scan_token(399)) {
    jj_scanpos = xsp;
    if (jj_scan_token(283)) {
    jj_scanpos = xsp;
    if (jj_scan_token(284)) {
    jj_scanpos = xsp;
    if (jj_scan_token(84)) {
    jj_scanpos = xsp;
    if (jj_scan_token(89)) {
    jj_scanpos = xsp;
    if (jj_scan_token(285)) {
    jj_scanpos = xsp;
    if (jj_scan_token(400)) {
    jj_scanpos = xsp;
    if (jj_scan_token(286)) {
    jj_scanpos = xsp;
    if (jj_scan_token(94)) {
    jj_scanpos = xsp;
    if (jj_scan_token(287)) {
    jj_scanpos = xsp;
    if (jj_scan_token(96)) {
    jj_scanpos = xsp;
    if (jj_scan_token(288)) {
    jj_scanpos = xsp;
    if (jj_scan_token(401)) {
    jj_scanpos = xsp;
    if (jj_scan_token(289)) {
    jj_scanpos = xsp;
    if (jj_scan_token(102)) {
    jj_scanpos = xsp;
    if (jj_scan_token(290)) {
    jj_scanpos = xsp;
    if (jj_scan_token(402)) {
    jj_scanpos = xsp;
    if (jj_scan_token(403)) {
    jj_scanpos = xsp;
    if (jj_scan_token(106)) {
    jj_scanpos = xsp;
    if (jj_scan_token(404)) {
    jj_scanpos = xsp;
    if (jj_scan_token(405)) {
    jj_scanpos = xsp;
    if (jj_scan_token(371)) {
    jj_scanpos = xsp;
    if (jj_scan_token(374)) {
    jj_scanpos = xsp;
    if (jj_scan_token(115)) {
    jj_scanpos = xsp;
    if (jj_scan_token(292)) {
    jj_scanpos = xsp;
    if (jj_scan_token(375)) {
    jj_scanpos = xsp;
    if (jj_scan_token(293)) {
    jj_scanpos = xsp;
    if (jj_scan_token(294)) {
    jj_scanpos = xsp;
    if (jj_scan_token(295)) {
    jj_scanpos = xsp;
    if (jj_scan_token(407)) {
    jj_scanpos = xsp;
    if (jj_scan_token(408)) {
    jj_scanpos = xsp;
    if (jj_scan_token(409)) {
    jj_scanpos = xsp;
    if (jj_scan_token(410)) {
    jj_scanpos = xsp;
    if (jj_scan_token(411)) {
    jj_scanpos = xsp;
    if (jj_scan_token(121)) {
    jj_scanpos = xsp;
    if (jj_scan_token(122)) {
    jj_scanpos = xsp;
    if (jj_scan_token(412)) {
    jj_scanpos = xsp;
    if (jj_scan_token(413)) {
    jj_scanpos = xsp;
    if (jj_scan_token(124)) {
    jj_scanpos = xsp;
    if (jj_scan_token(126)) {
    jj_scanpos = xsp;
    if (jj_scan_token(414)) {
    jj_scanpos = xsp;
    if (jj_scan_token(298)) {
    jj_scanpos = xsp;
    if (jj_scan_token(415)) {
    jj_scanpos = xsp;
    if (jj_scan_token(299)) {
    jj_scanpos = xsp;
    if (jj_scan_token(300)) {
    jj_scanpos = xsp;
    if (jj_scan_token(416)) {
    jj_scanpos = xsp;
    if (jj_scan_token(417)) {
    jj_scanpos = xsp;
    if (jj_scan_token(418)) {
    jj_scanpos = xsp;
    if (jj_scan_token(136)) {
    jj_scanpos = xsp;
    if (jj_scan_token(419)) {
    jj_scanpos = xsp;
    if (jj_scan_token(301)) {
    jj_scanpos = xsp;
    if (jj_scan_token(143)) {
    jj_scanpos = xsp;
    if (jj_scan_token(420)) {
    jj_scanpos = xsp;
    if (jj_scan_token(421)) {
    jj_scanpos = xsp;
    if (jj_scan_token(422)) {
    jj_scanpos = xsp;
    if (jj_scan_token(302)) {
    jj_scanpos = xsp;
    if (jj_scan_token(147)) {
    jj_scanpos = xsp;
    if (jj_scan_token(423)) {
    jj_scanpos = xsp;
    if (jj_scan_token(303)) {
    jj_scanpos = xsp;
    if (jj_scan_token(153)) {
    jj_scanpos = xsp;
    if (jj_scan_token(154)) {
    jj_scanpos = xsp;
    if (jj_scan_token(424)) {
    jj_scanpos = xsp;
    if (jj_scan_token(425)) {
    jj_scanpos = xsp;
    if (jj_scan_token(426)) {
    jj_scanpos = xsp;
    if (jj_scan_token(427)) {
    jj_scanpos = xsp;
    if (jj_scan_token(304)) {
    jj_scanpos = xsp;
    if (jj_scan_token(161)) {
    jj_scanpos = xsp;
    if (jj_scan_token(428)) {
    jj_scanpos = xsp;
    if (jj_scan_token(305)) {
    jj_scanpos = xsp;
    if (jj_scan_token(306)) {
    jj_scanpos = xsp;
    if (jj_scan_token(166)) {
    jj_scanpos = xsp;
    if (jj_scan_token(430)) {
    jj_scanpos = xsp;
    if (jj_scan_token(431)) {
    jj_scanpos = xsp;
    if (jj_scan_token(429)) {
    jj_scanpos = xsp;
    if (jj_scan_token(176)) {
    jj_scanpos = xsp;
    if (jj_scan_token(432)) {
    jj_scanpos = xsp;
    if (jj_scan_token(178)) {
    jj_scanpos = xsp;
    if (jj_scan_token(433)) {
    jj_scanpos = xsp;
    if (jj_scan_token(310)) {
    jj_scanpos = xsp;
    if (jj_scan_token(311)) {
    jj_scanpos = xsp;
    if (jj_scan_token(179)) {
    jj_scanpos = xsp;
    if (jj_scan_token(434)) {
    jj_scanpos = xsp;
    if (jj_scan_token(312)) {
    jj_scanpos = xsp;
    if (jj_scan_token(313)) {
    jj_scanpos = xsp;
    if (jj_scan_token(435)) {
    jj_scanpos = xsp;
    if (jj_scan_token(436)) {
    jj_scanpos = xsp;
    if (jj_scan_token(314)) {
    jj_scanpos = xsp;
    if (jj_scan_token(315)) {
    jj_scanpos = xsp;
    if (jj_scan_token(316)) {
    jj_scanpos = xsp;
    if (jj_scan_token(380)) {
    jj_scanpos = xsp;
    if (jj_scan_token(437)) {
    jj_scanpos = xsp;
    if (jj_scan_token(438)) {
    jj_scanpos = xsp;
    if (jj_scan_token(439)) {
    jj_scanpos = xsp;
    if (jj_scan_token(440)) {
    jj_scanpos = xsp;
    if (jj_scan_token(441)) {
    jj_scanpos = xsp;
    if (jj_scan_token(442)) {
    jj_scanpos = xsp;
    if (jj_scan_token(443)) {
    jj_scanpos = xsp;
    if (jj_scan_token(444)) {
    jj_scanpos = xsp;
    if (jj_scan_token(445)) {
    jj_scanpos = xsp;
    if (jj_scan_token(446)) {
    jj_scanpos = xsp;
    if (jj_scan_token(447)) {
    jj_scanpos = xsp;
    if (jj_scan_token(319)) {
    jj_scanpos = xsp;
    if (jj_scan_token(448)) {
    jj_scanpos = xsp;
    if (jj_scan_token(320)) {
    jj_scanpos = xsp;
    if (jj_scan_token(321)) {
    jj_scanpos = xsp;
    if (jj_scan_token(188)) {
    jj_scanpos = xsp;
    if (jj_scan_token(323)) {
    jj_scanpos = xsp;
    if (jj_scan_token(322)) {
    jj_scanpos = xsp;
    if (jj_scan_token(324)) {
    jj_scanpos = xsp;
    if (jj_scan_token(449)) {
    jj_scanpos = xsp;
    if (jj_scan_token(325)) {
    jj_scanpos = xsp;
    if (jj_scan_token(326)) {
    jj_scanpos = xsp;
    if (jj_scan_token(450)) {
    jj_scanpos = xsp;
    if (jj_scan_token(451)) {
    jj_scanpos = xsp;
    if (jj_scan_token(327)) {
    jj_scanpos = xsp;
    if (jj_scan_token(328)) {
    jj_scanpos = xsp;
    if (jj_scan_token(329)) {
    jj_scanpos = xsp;
    if (jj_scan_token(330)) {
    jj_scanpos = xsp;
    if (jj_scan_token(453)) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == OFFSET && !seeingOffsetClause();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_scan_token(331)) {
    jj_scanpos = xsp;
    if (jj_scan_token(454)) {
    jj_scanpos = xsp;
    if (jj_scan_token(455)) {
    jj_scanpos = xsp;
    if (jj_scan_token(456)) {
    jj_scanpos = xsp;
    if (jj_scan_token(203)) {
    jj_scanpos = xsp;
    if (jj_scan_token(383)) {
    jj_scanpos = xsp;
    if (jj_scan_token(209)) {
    jj_scanpos = xsp;
    if (jj_scan_token(210)) {
    jj_scanpos = xsp;
    if (jj_scan_token(332)) {
    jj_scanpos = xsp;
    if (jj_scan_token(459)) {
    jj_scanpos = xsp;
    if (jj_scan_token(333)) {
    jj_scanpos = xsp;
    if (jj_scan_token(334)) {
    jj_scanpos = xsp;
    if (jj_scan_token(335)) {
    jj_scanpos = xsp;
    if (jj_scan_token(213)) {
    jj_scanpos = xsp;
    if (jj_scan_token(215)) {
    jj_scanpos = xsp;
    if (jj_scan_token(216)) {
    jj_scanpos = xsp;
    if (jj_scan_token(460)) {
    jj_scanpos = xsp;
    if (jj_scan_token(461)) {
    jj_scanpos = xsp;
    if (jj_scan_token(462)) {
    jj_scanpos = xsp;
    if (jj_scan_token(463)) {
    jj_scanpos = xsp;
    if (jj_scan_token(219)) {
    jj_scanpos = xsp;
    if (jj_scan_token(464)) {
    jj_scanpos = xsp;
    if (jj_scan_token(222)) {
    jj_scanpos = xsp;
    if (jj_scan_token(465)) {
    jj_scanpos = xsp;
    if (jj_scan_token(336)) {
    jj_scanpos = xsp;
    if (jj_scan_token(467)) {
    jj_scanpos = xsp;
    if (jj_scan_token(337)) {
    jj_scanpos = xsp;
    if (jj_scan_token(468)) {
    jj_scanpos = xsp;
    if (jj_scan_token(466)) {
    jj_scanpos = xsp;
    if (jj_scan_token(469)) {
    jj_scanpos = xsp;
    if (jj_scan_token(338)) {
    jj_scanpos = xsp;
    if (jj_scan_token(470)) {
    jj_scanpos = xsp;
    if (jj_scan_token(471)) {
    jj_scanpos = xsp;
    if (jj_scan_token(339)) {
    jj_scanpos = xsp;
    if (jj_scan_token(385)) {
    jj_scanpos = xsp;
    if (jj_scan_token(340)) {
    jj_scanpos = xsp;
    if (jj_scan_token(341)) {
    jj_scanpos = xsp;
    if (jj_scan_token(386)) {
    jj_scanpos = xsp;
    if (jj_scan_token(472)) {
    jj_scanpos = xsp;
    if (jj_scan_token(473)) {
    jj_scanpos = xsp;
    if (jj_scan_token(343)) {
    jj_scanpos = xsp;
    if (jj_scan_token(342)) {
    jj_scanpos = xsp;
    if (jj_scan_token(474)) {
    jj_scanpos = xsp;
    if (jj_scan_token(344)) {
    jj_scanpos = xsp;
    if (jj_scan_token(345)) {
    jj_scanpos = xsp;
    if (jj_scan_token(475)) {
    jj_scanpos = xsp;
    if (jj_scan_token(476)) {
    jj_scanpos = xsp;
    if (jj_scan_token(477)) {
    jj_scanpos = xsp;
    if (jj_scan_token(346)) {
    jj_scanpos = xsp;
    if (jj_scan_token(232)) {
    jj_scanpos = xsp;
    if (jj_scan_token(478)) {
    jj_scanpos = xsp;
    if (jj_scan_token(479)) {
    jj_scanpos = xsp;
    if (jj_scan_token(480)) {
    jj_scanpos = xsp;
    if (jj_scan_token(237)) {
    jj_scanpos = xsp;
    if (jj_scan_token(481)) {
    jj_scanpos = xsp;
    if (jj_scan_token(482)) {
    jj_scanpos = xsp;
    if (jj_scan_token(351)) {
    jj_scanpos = xsp;
    if (jj_scan_token(347)) {
    jj_scanpos = xsp;
    if (jj_scan_token(350)) {
    jj_scanpos = xsp;
    if (jj_scan_token(349)) {
    jj_scanpos = xsp;
    if (jj_scan_token(353)) {
    jj_scanpos = xsp;
    if (jj_scan_token(354)) {
    jj_scanpos = xsp;
    if (jj_scan_token(348)) {
    jj_scanpos = xsp;
    if (jj_scan_token(352)) {
    jj_scanpos = xsp;
    if (jj_scan_token(355)) {
    jj_scanpos = xsp;
    if (jj_scan_token(483)) {
    jj_scanpos = xsp;
    if (jj_scan_token(484)) {
    jj_scanpos = xsp;
    if (jj_scan_token(356)) {
    jj_scanpos = xsp;
    if (jj_scan_token(357)) {
    jj_scanpos = xsp;
    if (jj_scan_token(485)) {
    jj_scanpos = xsp;
    if (jj_scan_token(486)) {
    jj_scanpos = xsp;
    if (jj_scan_token(487)) {
    jj_scanpos = xsp;
    if (jj_scan_token(488)) {
    jj_scanpos = xsp;
    if (jj_scan_token(489)) {
    jj_scanpos = xsp;
    if (jj_scan_token(358)) {
    jj_scanpos = xsp;
    if (jj_scan_token(245)) {
    jj_scanpos = xsp;
    if (jj_scan_token(247)) {
    jj_scanpos = xsp;
    if (jj_scan_token(490)) {
    jj_scanpos = xsp;
    if (jj_scan_token(359)) {
    jj_scanpos = xsp;
    if (jj_scan_token(360)) {
    jj_scanpos = xsp;
    if (jj_scan_token(361)) {
    jj_scanpos = xsp;
    if (jj_scan_token(362)) {
    jj_scanpos = xsp;
    if (jj_scan_token(363)) {
    jj_scanpos = xsp;
    if (jj_scan_token(491)) {
    jj_scanpos = xsp;
    if (jj_scan_token(492)) {
    jj_scanpos = xsp;
    if (jj_scan_token(493)) {
    jj_scanpos = xsp;
    if (jj_scan_token(251)) {
    jj_scanpos = xsp;
    if (jj_scan_token(494)) {
    jj_scanpos = xsp;
    if (jj_scan_token(364)) {
    jj_scanpos = xsp;
    if (jj_scan_token(495)) {
    jj_scanpos = xsp;
    if (jj_scan_token(257)) {
    jj_scanpos = xsp;
    if (jj_scan_token(365)) {
    jj_scanpos = xsp;
    if (jj_scan_token(496)) {
    jj_scanpos = xsp;
    if (jj_scan_token(366)) {
    jj_scanpos = xsp;
    if (jj_scan_token(497)) {
    jj_scanpos = xsp;
    if (jj_scan_token(498)) {
    jj_scanpos = xsp;
    if (jj_scan_token(367)) {
    jj_scanpos = xsp;
    if (jj_scan_token(499)) {
    jj_scanpos = xsp;
    if (jj_scan_token(270)) {
    jj_scanpos = xsp;
    if (jj_scan_token(275)) {
    jj_scanpos = xsp;
    if (jj_scan_token(276)) {
    jj_scanpos = xsp;
    if (jj_scan_token(265)) {
    jj_scanpos = xsp;
    if (jj_scan_token(267)) {
    jj_scanpos = xsp;
    if (jj_scan_token(458)) {
    jj_scanpos = xsp;
    if (jj_scan_token(500)) {
    jj_scanpos = xsp;
    if (jj_scan_token(501)) {
    jj_scanpos = xsp;
    if (jj_scan_token(368)) {
    jj_scanpos = xsp;
    if (jj_scan_token(502)) {
    jj_scanpos = xsp;
    if (jj_scan_token(503)) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_182() {
    if (jj_scan_token(SOLIDUS)) return true;
    return false;
  }

  private boolean jj_3R_520() {
    if (jj_scan_token(MEDIUMBLOB)) return true;
    return false;
  }

  private boolean jj_3R_339() {
    if (jj_scan_token(HEX_STRING)) return true;
    return false;
  }

  private boolean jj_3R_181() {
    if (jj_scan_token(ASTERISK)) return true;
    return false;
  }

  private boolean jj_3R_519() {
    if (jj_scan_token(TINYTEXT)) return true;
    return false;
  }

  private boolean jj_3R_226() {
    if (jj_scan_token(BY)) return true;
    return false;
  }

  private boolean jj_3R_518() {
    if (jj_scan_token(TINYBLOB)) return true;
    return false;
  }

  private boolean jj_3R_180() {
    if (jj_scan_token(DIV)) return true;
    return false;
  }

  private boolean jj_3R_225() {
    if (jj_scan_token(BY)) return true;
    return false;
  }

  private boolean jj_3R_102() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == REF;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_225()) {
    jj_scanpos = xsp;
    if (jj_3R_226()) return true;
    }
    return false;
  }

  private boolean jj_3R_517() {
    if (jj_scan_token(NATIONAL)) return true;
    return false;
  }

  private boolean jj_3R_232() {
    if (jj_scan_token(LEFT_BRACE)) return true;
    return false;
  }

  private boolean jj_3R_179() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(319)) {
    jj_scanpos = xsp;
    if (jj_scan_token(504)) return true;
    }
    return false;
  }

  private boolean jj_3R_86() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = infixModFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_179()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = divOperatorFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_180()) {
    jj_scanpos = xsp;
    if (jj_3R_181()) {
    jj_scanpos = xsp;
    if (jj_3R_182()) {
    jj_scanpos = xsp;
    if (jj_3R_183()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_516() {
    if (jj_3R_303()) return true;
    return false;
  }

  private boolean jj_3R_111() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_64()) {
    jj_scanpos = xsp;
    if (jj_3R_232()) return true;
    }
    return false;
  }

  private boolean jj_3_64() {
    if (jj_3R_112()) return true;
    return false;
  }

  private boolean jj_3R_515() {
    if (jj_scan_token(BINARY)) return true;
    return false;
  }

  private boolean jj_3R_514() {
    if (jj_scan_token(NCLOB)) return true;
    return false;
  }

  private boolean jj_3_63() {
    if (jj_3R_111()) return true;
    return false;
  }

  private boolean jj_3R_475() {
    if (jj_scan_token(DOUBLEQUOTED_STRING)) return true;
    return false;
  }

  private boolean jj_3R_513() {
    if (jj_scan_token(TEXT)) return true;
    return false;
  }

  private boolean jj_3R_474() {
    if (jj_scan_token(SINGLEQUOTED_STRING)) return true;
    return false;
  }

  private boolean jj_3R_512() {
    if (jj_scan_token(CLOB)) return true;
    return false;
  }

  private boolean jj_3R_424() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_474()) {
    jj_scanpos = xsp;
    if (jj_3R_475()) return true;
    }
    return false;
  }

  private boolean jj_3R_511() {
    if (jj_scan_token(BLOB)) return true;
    return false;
  }

  private boolean jj_3R_502() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_511()) {
    jj_scanpos = xsp;
    if (jj_3R_512()) {
    jj_scanpos = xsp;
    if (jj_3R_513()) {
    jj_scanpos = xsp;
    if (jj_3R_514()) {
    jj_scanpos = xsp;
    if (jj_3R_515()) {
    jj_scanpos = xsp;
    if (jj_3R_516()) {
    jj_scanpos = xsp;
    if (jj_3R_517()) {
    jj_scanpos = xsp;
    if (jj_3R_518()) {
    jj_scanpos = xsp;
    if (jj_3R_519()) {
    jj_scanpos = xsp;
    if (jj_3R_520()) {
    jj_scanpos = xsp;
    if (jj_3R_521()) {
    jj_scanpos = xsp;
    if (jj_3R_522()) {
    jj_scanpos = xsp;
    if (jj_3R_523()) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_338() {
    if (jj_3R_424()) return true;
    return false;
  }

  private boolean jj_3_35() {
    if (jj_3R_86()) return true;
    return false;
  }

  private boolean jj_3R_175() {
    if (jj_3R_305()) return true;
    return false;
  }

  private boolean jj_3_54() {
    if (jj_3R_102()) return true;
    return false;
  }

  private boolean jj_3R_375() {
    if (jj_scan_token(DOUBLE_GREATER)) return true;
    return false;
  }

  private boolean jj_3R_374() {
    if (jj_scan_token(DOUBLE_LESS)) return true;
    return false;
  }

  private boolean jj_3R_168() {
    if (jj_scan_token(NVARCHAR)) return true;
    return false;
  }

  private boolean jj_3R_373() {
    if (jj_scan_token(CARET)) return true;
    return false;
  }

  private boolean jj_3R_372() {
    if (jj_scan_token(VERTICAL_BAR)) return true;
    return false;
  }

  private boolean jj_3R_306() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_371()) {
    jj_scanpos = xsp;
    if (jj_3R_372()) {
    jj_scanpos = xsp;
    if (jj_3R_373()) {
    jj_scanpos = xsp;
    if (jj_3R_374()) {
    jj_scanpos = xsp;
    if (jj_3R_375()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_371() {
    if (jj_scan_token(AMPERSAND)) return true;
    return false;
  }

  private boolean jj_3R_167() {
    if (jj_scan_token(NCHAR)) return true;
    return false;
  }

  private boolean jj_3R_178() {
    if (jj_3R_306()) return true;
    return false;
  }

  private boolean jj_3R_166() {
    if (jj_scan_token(NATIONAL)) return true;
    return false;
  }

  private boolean jj_3R_177() {
    if (jj_scan_token(MINUS_SIGN)) return true;
    return false;
  }

  private boolean jj_3R_77() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_166()) {
    jj_scanpos = xsp;
    if (jj_3R_167()) {
    jj_scanpos = xsp;
    if (jj_3R_168()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_176() {
    if (jj_scan_token(PLUS_SIGN)) return true;
    return false;
  }

  private boolean jj_3R_85() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_176()) {
    jj_scanpos = xsp;
    if (jj_3R_177()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = infixBitFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_178()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_414() {
    if (jj_scan_token(NO)) return true;
    return false;
  }

  private boolean jj_3R_330() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_413()) {
    jj_scanpos = xsp;
    if (jj_3R_414()) return true;
    }
    return false;
  }

  private boolean jj_3R_413() {
    if (jj_scan_token(CYCLE)) return true;
    return false;
  }

  private boolean jj_3R_509() {
    if (jj_scan_token(EXISTS)) return true;
    return false;
  }

  private boolean jj_3R_243() {
    if (jj_3R_330()) return true;
    return false;
  }

  private boolean jj_3_52() {
    if (jj_3R_102()) return true;
    return false;
  }

  private boolean jj_3R_124() {
    if (jj_scan_token(NO)) return true;
    return false;
  }

  private boolean jj_3R_123() {
    if (jj_scan_token(MINVALUE)) return true;
    return false;
  }

  private boolean jj_3R_303() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(88)) {
    jj_scanpos = xsp;
    if (jj_scan_token(89)) return true;
    }
    return false;
  }

  private boolean jj_3_82() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_123()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == MINVALUE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_124()) return true;
    }
    return false;
  }

  private boolean jj_3R_122() {
    if (jj_scan_token(NO)) return true;
    return false;
  }

  private boolean jj_3R_121() {
    if (jj_scan_token(MAXVALUE)) return true;
    return false;
  }

  private boolean jj_3_81() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_121()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == MAXVALUE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_122()) return true;
    }
    return false;
  }

  private boolean jj_3_53() {
    if (jj_3R_103()) return true;
    return false;
  }

  private boolean jj_3_34() {
    if (jj_3R_85()) return true;
    return false;
  }

  private boolean jj_3R_242() {
    if (jj_scan_token(INCREMENT)) return true;
    return false;
  }

  private boolean jj_3R_84() {
    if (jj_3R_175()) return true;
    return false;
  }

  private boolean jj_3R_165() {
    if (jj_3R_303()) return true;
    return false;
  }

  private boolean jj_3R_241() {
    if (jj_scan_token(START)) return true;
    return false;
  }

  private boolean jj_3R_240() {
    if (jj_scan_token(AS)) return true;
    return false;
  }

  private boolean jj_3R_164() {
    if (jj_scan_token(VARCHAR)) return true;
    return false;
  }

  private boolean jj_3R_120() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_240()) {
    jj_scanpos = xsp;
    if (jj_3R_241()) {
    jj_scanpos = xsp;
    if (jj_3R_242()) {
    jj_scanpos = xsp;
    if (jj_3_81()) {
    jj_scanpos = xsp;
    if (jj_3_82()) {
    jj_scanpos = xsp;
    if (jj_3R_243()) return true;
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_75() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_164()) {
    jj_scanpos = xsp;
    if (jj_3R_165()) return true;
    }
    return false;
  }

  private boolean jj_3R_473() {
    if (jj_3R_503()) return true;
    return false;
  }

  private boolean jj_3R_472() {
    if (jj_3R_502()) return true;
    return false;
  }

  private boolean jj_3_91() {
    if (jj_3R_131()) return true;
    return false;
  }

  private boolean jj_3R_471() {
    if (jj_3R_501()) return true;
    return false;
  }

  private boolean jj_3R_76() {
    return false;
  }

  private boolean jj_3R_381() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(65)) {
    jj_scanpos = xsp;
    if (jj_scan_token(66)) {
    jj_scanpos = xsp;
    if (jj_scan_token(67)) {
    jj_scanpos = xsp;
    if (jj_scan_token(68)) {
    jj_scanpos = xsp;
    if (jj_scan_token(69)) {
    jj_scanpos = xsp;
    if (jj_scan_token(70)) {
    jj_scanpos = xsp;
    if (jj_scan_token(71)) {
    jj_scanpos = xsp;
    if (jj_scan_token(72)) {
    jj_scanpos = xsp;
    if (jj_scan_token(75)) {
    jj_scanpos = xsp;
    if (jj_scan_token(76)) {
    jj_scanpos = xsp;
    if (jj_scan_token(77)) {
    jj_scanpos = xsp;
    if (jj_scan_token(78)) {
    jj_scanpos = xsp;
    if (jj_scan_token(79)) {
    jj_scanpos = xsp;
    if (jj_scan_token(81)) {
    jj_scanpos = xsp;
    if (jj_scan_token(82)) {
    jj_scanpos = xsp;
    if (jj_scan_token(83)) {
    jj_scanpos = xsp;
    if (jj_scan_token(85)) {
    jj_scanpos = xsp;
    if (jj_scan_token(86)) {
    jj_scanpos = xsp;
    if (jj_scan_token(87)) {
    jj_scanpos = xsp;
    if (jj_scan_token(88)) {
    jj_scanpos = xsp;
    if (jj_scan_token(90)) {
    jj_scanpos = xsp;
    if (jj_scan_token(91)) {
    jj_scanpos = xsp;
    if (jj_scan_token(92)) {
    jj_scanpos = xsp;
    if (jj_scan_token(93)) {
    jj_scanpos = xsp;
    if (jj_scan_token(95)) {
    jj_scanpos = xsp;
    if (jj_scan_token(97)) {
    jj_scanpos = xsp;
    if (jj_scan_token(98)) {
    jj_scanpos = xsp;
    if (jj_scan_token(99)) {
    jj_scanpos = xsp;
    if (jj_scan_token(100)) {
    jj_scanpos = xsp;
    if (jj_scan_token(101)) {
    jj_scanpos = xsp;
    if (jj_scan_token(103)) {
    jj_scanpos = xsp;
    if (jj_scan_token(104)) {
    jj_scanpos = xsp;
    if (jj_scan_token(105)) {
    jj_scanpos = xsp;
    if (jj_scan_token(107)) {
    jj_scanpos = xsp;
    if (jj_scan_token(108)) {
    jj_scanpos = xsp;
    if (jj_scan_token(109)) {
    jj_scanpos = xsp;
    if (jj_scan_token(110)) {
    jj_scanpos = xsp;
    if (jj_scan_token(111)) {
    jj_scanpos = xsp;
    if (jj_scan_token(112)) {
    jj_scanpos = xsp;
    if (jj_scan_token(113)) {
    jj_scanpos = xsp;
    if (jj_scan_token(114)) {
    jj_scanpos = xsp;
    if (jj_scan_token(116)) {
    jj_scanpos = xsp;
    if (jj_scan_token(117)) {
    jj_scanpos = xsp;
    if (jj_scan_token(118)) {
    jj_scanpos = xsp;
    if (jj_scan_token(119)) {
    jj_scanpos = xsp;
    if (jj_scan_token(120)) {
    jj_scanpos = xsp;
    if (jj_scan_token(123)) {
    jj_scanpos = xsp;
    if (jj_scan_token(125)) {
    jj_scanpos = xsp;
    if (jj_scan_token(127)) {
    jj_scanpos = xsp;
    if (jj_scan_token(128)) {
    jj_scanpos = xsp;
    if (jj_scan_token(129)) {
    jj_scanpos = xsp;
    if (jj_scan_token(130)) {
    jj_scanpos = xsp;
    if (jj_scan_token(131)) {
    jj_scanpos = xsp;
    if (jj_scan_token(132)) {
    jj_scanpos = xsp;
    if (jj_scan_token(133)) {
    jj_scanpos = xsp;
    if (jj_scan_token(134)) {
    jj_scanpos = xsp;
    if (jj_scan_token(135)) {
    jj_scanpos = xsp;
    if (jj_scan_token(137)) {
    jj_scanpos = xsp;
    if (jj_scan_token(138)) {
    jj_scanpos = xsp;
    if (jj_scan_token(139)) {
    jj_scanpos = xsp;
    if (jj_scan_token(140)) {
    jj_scanpos = xsp;
    if (jj_scan_token(141)) {
    jj_scanpos = xsp;
    if (jj_scan_token(142)) {
    jj_scanpos = xsp;
    if (jj_scan_token(144)) {
    jj_scanpos = xsp;
    if (jj_scan_token(145)) {
    jj_scanpos = xsp;
    if (jj_scan_token(146)) {
    jj_scanpos = xsp;
    if (jj_scan_token(148)) {
    jj_scanpos = xsp;
    if (jj_scan_token(149)) {
    jj_scanpos = xsp;
    if (jj_scan_token(150)) {
    jj_scanpos = xsp;
    if (jj_scan_token(151)) {
    jj_scanpos = xsp;
    if (jj_scan_token(376)) {
    jj_scanpos = xsp;
    if (jj_scan_token(152)) {
    jj_scanpos = xsp;
    if (jj_scan_token(155)) {
    jj_scanpos = xsp;
    if (jj_scan_token(156)) {
    jj_scanpos = xsp;
    if (jj_scan_token(157)) {
    jj_scanpos = xsp;
    if (jj_scan_token(158)) {
    jj_scanpos = xsp;
    if (jj_scan_token(159)) {
    jj_scanpos = xsp;
    if (jj_scan_token(160)) {
    jj_scanpos = xsp;
    if (jj_scan_token(162)) {
    jj_scanpos = xsp;
    if (jj_scan_token(163)) {
    jj_scanpos = xsp;
    if (jj_scan_token(164)) {
    jj_scanpos = xsp;
    if (jj_scan_token(165)) {
    jj_scanpos = xsp;
    if (jj_scan_token(167)) {
    jj_scanpos = xsp;
    if (jj_scan_token(307)) {
    jj_scanpos = xsp;
    if (jj_scan_token(168)) {
    jj_scanpos = xsp;
    if (jj_scan_token(169)) {
    jj_scanpos = xsp;
    if (jj_scan_token(170)) {
    jj_scanpos = xsp;
    if (jj_scan_token(171)) {
    jj_scanpos = xsp;
    if (jj_scan_token(172)) {
    jj_scanpos = xsp;
    if (jj_scan_token(173)) {
    jj_scanpos = xsp;
    if (jj_scan_token(308)) {
    jj_scanpos = xsp;
    if (jj_scan_token(174)) {
    jj_scanpos = xsp;
    if (jj_scan_token(175)) {
    jj_scanpos = xsp;
    if (jj_scan_token(177)) {
    jj_scanpos = xsp;
    if (jj_scan_token(180)) {
    jj_scanpos = xsp;
    if (jj_scan_token(181)) {
    jj_scanpos = xsp;
    if (jj_scan_token(182)) {
    jj_scanpos = xsp;
    if (jj_scan_token(379)) {
    jj_scanpos = xsp;
    if (jj_scan_token(183)) {
    jj_scanpos = xsp;
    if (jj_scan_token(184)) {
    jj_scanpos = xsp;
    if (jj_scan_token(185)) {
    jj_scanpos = xsp;
    if (jj_scan_token(186)) {
    jj_scanpos = xsp;
    if (jj_scan_token(187)) {
    jj_scanpos = xsp;
    if (jj_scan_token(189)) {
    jj_scanpos = xsp;
    if (jj_scan_token(190)) {
    jj_scanpos = xsp;
    if (jj_scan_token(191)) {
    jj_scanpos = xsp;
    if (jj_scan_token(452)) {
    jj_scanpos = xsp;
    if (jj_scan_token(192)) {
    jj_scanpos = xsp;
    if (jj_scan_token(193)) {
    jj_scanpos = xsp;
    if (jj_scan_token(382)) {
    jj_scanpos = xsp;
    if (jj_scan_token(194)) {
    jj_scanpos = xsp;
    if (jj_scan_token(195)) {
    jj_scanpos = xsp;
    if (jj_scan_token(196)) {
    jj_scanpos = xsp;
    if (jj_scan_token(197)) {
    jj_scanpos = xsp;
    if (jj_scan_token(198)) {
    jj_scanpos = xsp;
    if (jj_scan_token(199)) {
    jj_scanpos = xsp;
    if (jj_scan_token(200)) {
    jj_scanpos = xsp;
    if (jj_scan_token(201)) {
    jj_scanpos = xsp;
    if (jj_scan_token(202)) {
    jj_scanpos = xsp;
    if (jj_scan_token(204)) {
    jj_scanpos = xsp;
    if (jj_scan_token(205)) {
    jj_scanpos = xsp;
    if (jj_scan_token(457)) {
    jj_scanpos = xsp;
    if (jj_scan_token(206)) {
    jj_scanpos = xsp;
    if (jj_scan_token(207)) {
    jj_scanpos = xsp;
    if (jj_scan_token(208)) {
    jj_scanpos = xsp;
    if (jj_scan_token(211)) {
    jj_scanpos = xsp;
    if (jj_scan_token(212)) {
    jj_scanpos = xsp;
    if (jj_scan_token(214)) {
    jj_scanpos = xsp;
    if (jj_scan_token(217)) {
    jj_scanpos = xsp;
    if (jj_scan_token(218)) {
    jj_scanpos = xsp;
    if (jj_scan_token(220)) {
    jj_scanpos = xsp;
    if (jj_scan_token(221)) {
    jj_scanpos = xsp;
    if (jj_scan_token(223)) {
    jj_scanpos = xsp;
    if (jj_scan_token(384)) {
    jj_scanpos = xsp;
    if (jj_scan_token(224)) {
    jj_scanpos = xsp;
    if (jj_scan_token(225)) {
    jj_scanpos = xsp;
    if (jj_scan_token(226)) {
    jj_scanpos = xsp;
    if (jj_scan_token(227)) {
    jj_scanpos = xsp;
    if (jj_scan_token(228)) {
    jj_scanpos = xsp;
    if (jj_scan_token(229)) {
    jj_scanpos = xsp;
    if (jj_scan_token(230)) {
    jj_scanpos = xsp;
    if (jj_scan_token(231)) {
    jj_scanpos = xsp;
    if (jj_scan_token(233)) {
    jj_scanpos = xsp;
    if (jj_scan_token(234)) {
    jj_scanpos = xsp;
    if (jj_scan_token(235)) {
    jj_scanpos = xsp;
    if (jj_scan_token(236)) {
    jj_scanpos = xsp;
    if (jj_scan_token(238)) {
    jj_scanpos = xsp;
    if (jj_scan_token(239)) {
    jj_scanpos = xsp;
    if (jj_scan_token(240)) {
    jj_scanpos = xsp;
    if (jj_scan_token(241)) {
    jj_scanpos = xsp;
    if (jj_scan_token(388)) {
    jj_scanpos = xsp;
    if (jj_scan_token(242)) {
    jj_scanpos = xsp;
    if (jj_scan_token(243)) {
    jj_scanpos = xsp;
    if (jj_scan_token(244)) {
    jj_scanpos = xsp;
    if (jj_scan_token(246)) {
    jj_scanpos = xsp;
    if (jj_scan_token(248)) {
    jj_scanpos = xsp;
    if (jj_scan_token(249)) {
    jj_scanpos = xsp;
    if (jj_scan_token(250)) {
    jj_scanpos = xsp;
    if (jj_scan_token(254)) {
    jj_scanpos = xsp;
    if (jj_scan_token(252)) {
    jj_scanpos = xsp;
    if (jj_scan_token(253)) {
    jj_scanpos = xsp;
    if (jj_scan_token(256)) {
    jj_scanpos = xsp;
    if (jj_scan_token(258)) {
    jj_scanpos = xsp;
    if (jj_scan_token(259)) {
    jj_scanpos = xsp;
    if (jj_scan_token(260)) {
    jj_scanpos = xsp;
    if (jj_scan_token(261)) {
    jj_scanpos = xsp;
    if (jj_scan_token(262)) {
    jj_scanpos = xsp;
    if (jj_scan_token(263)) {
    jj_scanpos = xsp;
    if (jj_scan_token(264)) {
    jj_scanpos = xsp;
    if (jj_scan_token(266)) {
    jj_scanpos = xsp;
    if (jj_scan_token(268)) {
    jj_scanpos = xsp;
    if (jj_scan_token(269)) {
    jj_scanpos = xsp;
    if (jj_scan_token(271)) {
    jj_scanpos = xsp;
    if (jj_scan_token(272)) {
    jj_scanpos = xsp;
    if (jj_scan_token(274)) {
    jj_scanpos = xsp;
    if (jj_scan_token(277)) {
    jj_scanpos = xsp;
    if (jj_scan_token(369)) {
    jj_scanpos = xsp;
    if (jj_scan_token(370)) {
    jj_scanpos = xsp;
    if (jj_scan_token(372)) {
    jj_scanpos = xsp;
    if (jj_scan_token(373)) {
    jj_scanpos = xsp;
    if (jj_scan_token(378)) {
    jj_scanpos = xsp;
    if (jj_scan_token(377)) {
    jj_scanpos = xsp;
    if (jj_scan_token(381)) {
    jj_scanpos = xsp;
    if (jj_scan_token(387)) {
    jj_scanpos = xsp;
    if (jj_scan_token(255)) {
    jj_scanpos = xsp;
    if (jj_scan_token(389)) {
    jj_scanpos = xsp;
    if (jj_scan_token(390)) {
    jj_scanpos = xsp;
    if (jj_scan_token(392)) {
    jj_scanpos = xsp;
    if (jj_scan_token(394)) {
    jj_scanpos = xsp;
    if (jj_scan_token(391)) {
    jj_scanpos = xsp;
    if (jj_scan_token(393)) {
    jj_scanpos = xsp;
    if (jj_scan_token(395)) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_341() {
    if (jj_scan_token(INTERVAL)) return true;
    return false;
  }

  private boolean jj_3R_470() {
    if (jj_scan_token(BOOLEAN)) return true;
    return false;
  }

  private boolean jj_3R_469() {
    if (jj_3R_500()) return true;
    return false;
  }

  private boolean jj_3R_74() {
    return false;
  }

  private boolean jj_3R_468() {
    if (jj_3R_343()) return true;
    return false;
  }

  private boolean jj_3_26() {
    if (jj_3R_78()) return true;
    return false;
  }

  private boolean jj_3_25() {
    jj_lookingAhead = true;
    jj_semLA = getToken(3).kind != LARGE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_76()) return true;
    if (jj_3R_77()) return true;
    return false;
  }

  private boolean jj_3_24() {
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind != LARGE;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_74()) return true;
    if (jj_3R_75()) return true;
    return false;
  }

  private boolean jj_3R_109() {
    return false;
  }

  private boolean jj_3_80() {
    if (jj_3R_120()) return true;
    return false;
  }

  private boolean jj_3R_440() {
    if (jj_scan_token(BACKQUOTED_IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3R_418() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_24()) {
    jj_scanpos = xsp;
    if (jj_3_25()) {
    jj_scanpos = xsp;
    if (jj_3_26()) {
    jj_scanpos = xsp;
    if (jj_3R_468()) {
    jj_scanpos = xsp;
    if (jj_3R_469()) {
    jj_scanpos = xsp;
    if (jj_3R_470()) {
    jj_scanpos = xsp;
    if (jj_3R_471()) {
    jj_scanpos = xsp;
    if (jj_3R_472()) {
    jj_scanpos = xsp;
    if (jj_3R_473()) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_439() {
    if (jj_scan_token(DOUBLEQUOTED_IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3_62() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3_61() {
    jj_lookingAhead = true;
    jj_semLA = rowValueConstructorListFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_109()) return true;
    if (jj_3R_110()) return true;
    return false;
  }

  private boolean jj_3R_348() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_439()) {
    jj_scanpos = xsp;
    if (jj_3R_440()) return true;
    }
    return false;
  }

  private boolean jj_3_23() {
    if (jj_3R_73()) return true;
    return false;
  }

  private boolean jj_3R_153() {
    if (jj_3R_155()) return true;
    return false;
  }

  private boolean jj_3R_127() {
    if (jj_3R_246()) return true;
    return false;
  }

  private boolean jj_3R_67() {
    if (jj_3R_155()) return true;
    return false;
  }

  private boolean jj_3_110() {
    if (jj_3R_140()) return true;
    return false;
  }

  private boolean jj_3R_333() {
    if (jj_3R_73()) return true;
    return false;
  }

  private boolean jj_3R_110() {
    if (jj_scan_token(LEFT_PAREN)) return true;
    return false;
  }

  private boolean jj_3R_294() {
    if (jj_3R_348()) return true;
    return false;
  }

  private boolean jj_3_79() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_246() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = commonDatatypeName(false);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_332()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind != GENERATED;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_333()) return true;
    }
    return false;
  }

  private boolean jj_3R_332() {
    if (jj_3R_418()) return true;
    return false;
  }

  private boolean jj_3_33() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_260() {
    if (jj_3R_343()) return true;
    return false;
  }

  private boolean jj_3_21() {
    if (jj_3R_72()) return true;
    return false;
  }

  private boolean jj_3R_155() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_293()) {
    jj_scanpos = xsp;
    if (jj_3R_294()) {
    jj_scanpos = xsp;
    if (jj_3_110()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_293() {
    if (jj_scan_token(IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3_20() {
    if (jj_3R_72()) return true;
    return false;
  }

  private boolean jj_3R_161() {
    if (jj_3R_299()) return true;
    return false;
  }

  private boolean jj_3R_130() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_259()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = isDATETIME(getToken(1).kind) && getToken(2).kind == SINGLEQUOTED_STRING;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_260()) return true;
    }
    return false;
  }

  private boolean jj_3R_259() {
    if (jj_scan_token(LEFT_BRACE)) return true;
    return false;
  }

  private boolean jj_3_19() {
    if (jj_3R_72()) return true;
    return false;
  }

  private boolean jj_3_22() {
    if (jj_3R_72()) return true;
    return false;
  }

  private boolean jj_3R_71() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_22()) {
    jj_scanpos = xsp;
    if (jj_3R_161()) return true;
    }
    return false;
  }

  private boolean jj_3R_302() {
    if (jj_3R_364()) return true;
    return false;
  }

  private boolean jj_3_17() {
    if (jj_3R_70()) return true;
    return false;
  }

  private boolean jj_3_100() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(377)) jj_scanpos = xsp;
    if (jj_3R_135()) return true;
    return false;
  }

  private boolean jj_3_18() {
    if (jj_3R_71()) return true;
    return false;
  }

  private boolean jj_3R_69() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_301() {
    if (jj_3R_367()) return true;
    return false;
  }

  private boolean jj_3_16() {
    if (jj_3R_69()) return true;
    return false;
  }

  private boolean jj_3R_497() {
    if (jj_3R_509()) return true;
    return false;
  }

  private boolean jj_3_32() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_163() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_300()) {
    jj_scanpos = xsp;
    if (jj_3R_301()) {
    jj_scanpos = xsp;
    if (jj_3_100()) {
    jj_scanpos = xsp;
    if (jj_3R_302()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_300() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(194)) jj_scanpos = xsp;
    if (jj_scan_token(NULL)) return true;
    return false;
  }

  private boolean jj_3R_496() {
    if (jj_3R_110()) return true;
    return false;
  }

  private boolean jj_3R_390() {
    if (jj_scan_token(XMLQUERY)) return true;
    return false;
  }

  private boolean jj_3R_423() {
    if (jj_scan_token(APPROXIMATE_NUMERIC)) return true;
    return false;
  }

  private boolean jj_3R_466() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = rowValueConstructorListFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_496()) {
    jj_scanpos = xsp;
    if (jj_3_32()) {
    jj_scanpos = xsp;
    if (jj_3R_497()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_389() {
    if (jj_scan_token(XMLEXISTS)) return true;
    return false;
  }

  private boolean jj_3R_239() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_337() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_422()) {
    jj_scanpos = xsp;
    if (jj_3R_423()) return true;
    }
    return false;
  }

  private boolean jj_3R_422() {
    if (jj_scan_token(EXACT_NUMERIC)) return true;
    return false;
  }

  private boolean jj_3R_388() {
    if (jj_scan_token(XMLSERIALIZE)) return true;
    return false;
  }

  private boolean jj_3R_410() {
    if (jj_3R_466()) return true;
    return false;
  }

  private boolean jj_3R_317() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_387()) {
    jj_scanpos = xsp;
    if (jj_3R_388()) {
    jj_scanpos = xsp;
    if (jj_3R_389()) {
    jj_scanpos = xsp;
    if (jj_3R_390()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_387() {
    if (jj_scan_token(XMLPARSE)) return true;
    return false;
  }

  private boolean jj_3_109() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_119() {
    if (jj_3R_239()) return true;
    return false;
  }

  private boolean jj_3_78() {
    if (jj_3R_119()) return true;
    return false;
  }

  private boolean jj_3R_162() {
    if (jj_3R_295()) return true;
    return false;
  }

  private boolean jj_3R_72() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_162()) jj_scanpos = xsp;
    if (jj_3R_163()) return true;
    return false;
  }

  private boolean jj_3R_258() {
    if (jj_3R_342()) return true;
    return false;
  }

  private boolean jj_3R_158() {
    if (jj_scan_token(ON)) return true;
    return false;
  }

  private boolean jj_3_60() {
    if (jj_3R_108()) return true;
    return false;
  }

  private boolean jj_3R_223() {
    if (jj_3R_323()) return true;
    return false;
  }

  private boolean jj_3R_257() {
    if (jj_3R_341()) return true;
    return false;
  }

  private boolean jj_3R_360() {
    if (jj_scan_token(COPY)) return true;
    return false;
  }

  private boolean jj_3R_157() {
    if (jj_scan_token(ON)) return true;
    return false;
  }

  private boolean jj_3R_256() {
    if (jj_3R_340()) return true;
    return false;
  }

  private boolean jj_3R_156() {
    if (jj_scan_token(NOT)) return true;
    return false;
  }

  private boolean jj_3_51() {
    if (jj_3R_101()) return true;
    return false;
  }

  private boolean jj_3_90() {
    if (jj_3R_130()) return true;
    return false;
  }

  private boolean jj_3R_68() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_156()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == ON && getToken(2).kind == COMMIT;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_157()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == ON && getToken(2).kind == ROLLBACK;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_158()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_222() {
    if (jj_3R_322()) return true;
    return false;
  }

  private boolean jj_3R_100() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_222()) {
    jj_scanpos = xsp;
    if (jj_3_51()) {
    jj_scanpos = xsp;
    if (jj_3R_223()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_255() {
    if (jj_3R_339()) return true;
    return false;
  }

  private boolean jj_3R_64() {
    if (jj_3R_93()) return true;
    return false;
  }

  private boolean jj_3R_254() {
    if (jj_3R_338()) return true;
    return false;
  }

  private boolean jj_3R_336() {
    if (jj_3R_184()) return true;
    return false;
  }

  private boolean jj_3R_325() {
    if (jj_3R_410()) return true;
    return false;
  }

  private boolean jj_3R_129() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_253()) {
    jj_scanpos = xsp;
    if (jj_3R_254()) {
    jj_scanpos = xsp;
    if (jj_3R_255()) {
    jj_scanpos = xsp;
    if (jj_3_90()) {
    jj_scanpos = xsp;
    if (jj_3R_256()) {
    jj_scanpos = xsp;
    if (jj_3R_257()) {
    jj_scanpos = xsp;
    if (jj_3R_258()) return true;
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_253() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_336()) jj_scanpos = xsp;
    if (jj_3R_337()) return true;
    return false;
  }

  private boolean jj_3R_358() {
    if (jj_scan_token(EXPLAIN)) return true;
    return false;
  }

  private boolean jj_3R_489() {
    if (jj_scan_token(DOLLAR_N)) return true;
    return false;
  }

  private boolean jj_3R_204() {
    if (jj_3R_317()) return true;
    return false;
  }

  private boolean jj_3_15() {
    if (jj_3R_68()) return true;
    return false;
  }

  private boolean jj_3R_461() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_488()) {
    jj_scanpos = xsp;
    if (jj_3R_489()) return true;
    }
    return false;
  }

  private boolean jj_3R_488() {
    if (jj_scan_token(QUESTION_MARK)) return true;
    return false;
  }

  private boolean jj_3R_203() {
    if (jj_scan_token(OCTET_LENGTH)) return true;
    return false;
  }

  private boolean jj_3_89() {
    if (jj_3R_129()) return true;
    return false;
  }

  private boolean jj_3R_202() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(91)) {
    jj_scanpos = xsp;
    if (jj_scan_token(90)) return true;
    }
    return false;
  }

  private boolean jj_3_88() {
    if (jj_3R_97()) return true;
    return false;
  }

  private boolean jj_3R_356() {
    if (jj_scan_token(EXECUTE)) return true;
    return false;
  }

  private boolean jj_3R_453() {
    if (jj_scan_token(ROLLBACK)) return true;
    return false;
  }

  private boolean jj_3R_231() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(194)) jj_scanpos = xsp;
    if (jj_3R_325()) return true;
    return false;
  }

  private boolean jj_3R_452() {
    if (jj_scan_token(COMMIT)) return true;
    return false;
  }

  private boolean jj_3R_404() {
    if (jj_scan_token(VARCHAR)) return true;
    return false;
  }

  private boolean jj_3R_451() {
    if (jj_scan_token(BEGIN)) return true;
    return false;
  }

  private boolean jj_3R_323() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_403()) {
    jj_scanpos = xsp;
    if (jj_3R_404()) return true;
    }
    return false;
  }

  private boolean jj_3R_403() {
    if (jj_scan_token(CHAR)) return true;
    return false;
  }

  private boolean jj_3R_252() {
    if (jj_3R_312()) return true;
    return false;
  }

  private boolean jj_3R_201() {
    if (jj_scan_token(LENGTH)) return true;
    return false;
  }

  private boolean jj_3R_208() {
    return false;
  }

  private boolean jj_3R_359() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_451()) {
    jj_scanpos = xsp;
    if (jj_3R_452()) {
    jj_scanpos = xsp;
    if (jj_3R_453()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_200() {
    if (jj_scan_token(VALUE)) return true;
    return false;
  }

  private boolean jj_3R_251() {
    if (jj_3R_312()) return true;
    return false;
  }

  private boolean jj_3R_199() {
    if (jj_scan_token(COALESCE)) return true;
    return false;
  }

  private boolean jj_3R_108() {
    if (jj_3R_231()) return true;
    return false;
  }

  private boolean jj_3R_96() {
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == CURRENT && getToken(2).kind == VALUE && getToken(3).kind == FOR;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_208()) return true;
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_250() {
    if (jj_3R_335()) return true;
    return false;
  }

  private boolean jj_3_50() {
    if (jj_3R_100()) return true;
    return false;
  }

  private boolean jj_3R_450() {
    if (jj_scan_token(DEALLOCATE)) return true;
    return false;
  }

  private boolean jj_3R_249() {
    if (jj_3R_334()) return true;
    return false;
  }

  private boolean jj_3_49() {
    if (jj_3R_99()) return true;
    return false;
  }

  private boolean jj_3_14() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_198() {
    if (jj_3R_316()) return true;
    return false;
  }

  private boolean jj_3R_93() {
    if (jj_3R_108()) return true;
    return false;
  }

  private boolean jj_3R_449() {
    if (jj_scan_token(PREPARE)) return true;
    return false;
  }

  private boolean jj_3R_128() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == SCHEMA || getToken(2).kind == SQLID;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_248()) {
    jj_scanpos = xsp;
    if (jj_3R_249()) {
    jj_scanpos = xsp;
    if (jj_3R_250()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == DATE ||
                             getToken(1).kind == TIME ||
                             getToken(1).kind == TIMESTAMP;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_251()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == LEFT_PAREN ||
                                 (getToken(4).kind == LEFT_PAREN && getToken(2).kind != COMMA);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_252()) {
    jj_scanpos = xsp;
    if (jj_3_88()) {
    jj_scanpos = xsp;
    if (jj_3_89()) return true;
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_248() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_197() {
    if (jj_scan_token(GET_CURRENT_CONNECTION)) return true;
    return false;
  }

  private boolean jj_3R_92() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_197()) {
    jj_scanpos = xsp;
    if (jj_3R_198()) {
    jj_scanpos = xsp;
    if (jj_3_49()) {
    jj_scanpos = xsp;
    if (jj_3_50()) {
    jj_scanpos = xsp;
    if (jj_3R_199()) {
    jj_scanpos = xsp;
    if (jj_3R_200()) {
    jj_scanpos = xsp;
    if (jj_3R_201()) {
    jj_scanpos = xsp;
    if (jj_3R_202()) {
    jj_scanpos = xsp;
    if (jj_3R_203()) {
    jj_scanpos = xsp;
    if (jj_3R_204()) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_314() {
    if (jj_scan_token(NEXT)) return true;
    return false;
  }

  private boolean jj_3R_448() {
    if (jj_scan_token(CLOSE)) return true;
    return false;
  }

  private boolean jj_3R_135() {
    if (jj_scan_token(REFERENCES)) return true;
    return false;
  }

  private boolean jj_3R_83() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(72)) jj_scanpos = xsp;
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_447() {
    if (jj_scan_token(FETCH)) return true;
    return false;
  }

  private boolean jj_3_48() {
    if (jj_3R_98()) return true;
    return false;
  }

  private boolean jj_3_87() {
    if (jj_3R_128()) return true;
    return false;
  }

  private boolean jj_3_47() {
    if (jj_3R_97()) return true;
    return false;
  }

  private boolean jj_3_31() {
    if (jj_3R_83()) return true;
    return false;
  }

  private boolean jj_3R_355() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_446()) {
    jj_scanpos = xsp;
    if (jj_3R_447()) {
    jj_scanpos = xsp;
    if (jj_3R_448()) {
    jj_scanpos = xsp;
    if (jj_3R_449()) {
    jj_scanpos = xsp;
    if (jj_3R_450()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_446() {
    if (jj_scan_token(DECLARE)) return true;
    return false;
  }

  private boolean jj_3R_134() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(377)) jj_scanpos = xsp;
    if (jj_scan_token(FOREIGN)) return true;
    return false;
  }

  private boolean jj_3R_82() {
    if (jj_3R_93()) return true;
    return false;
  }

  private boolean jj_3R_312() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = miscBuiltinCoreFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_380()) {
    jj_scanpos = xsp;
    if (jj_3_47()) {
    jj_scanpos = xsp;
    if (jj_3_48()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_380() {
    if (jj_3R_92()) return true;
    return false;
  }

  private boolean jj_3R_313() {
    if (jj_scan_token(CAST)) return true;
    return false;
  }

  private boolean jj_3_30() {
    if (jj_3R_82()) return true;
    return false;
  }

  private boolean jj_3_46() {
    if (jj_3R_96()) return true;
    return false;
  }

  private boolean jj_3R_195() {
    if (jj_3R_314()) return true;
    return false;
  }

  private boolean jj_3R_194() {
    if (jj_3R_313()) return true;
    return false;
  }

  private boolean jj_3_43() {
    if (jj_3R_93()) return true;
    return false;
  }

  private boolean jj_3R_487() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(185)) {
    jj_scanpos = xsp;
    if (jj_scan_token(77)) {
    jj_scanpos = xsp;
    if (jj_scan_token(186)) {
    jj_scanpos = xsp;
    if (jj_scan_token(243)) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3_13() {
    if (jj_3R_66()) return true;
    return false;
  }

  private boolean jj_3R_457() {
    if (jj_scan_token(PRIMARY)) return true;
    return false;
  }

  private boolean jj_3R_370() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == PERIOD && getToken(4).kind == LEFT_PAREN;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_459()) {
    jj_scanpos = xsp;
    if (jj_3_13()) return true;
    }
    return false;
  }

  private boolean jj_3R_459() {
    if (jj_3R_98()) return true;
    return false;
  }

  private boolean jj_3R_367() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_456()) {
    jj_scanpos = xsp;
    if (jj_3R_457()) return true;
    }
    return false;
  }

  private boolean jj_3R_456() {
    if (jj_scan_token(UNIQUE)) return true;
    return false;
  }

  private boolean jj_3R_193() {
    if (jj_scan_token(LEFT_PAREN)) return true;
    return false;
  }

  private boolean jj_3_45() {
    if (jj_3R_95()) return true;
    return false;
  }

  private boolean jj_3R_342() {
    if (jj_scan_token(NULL)) return true;
    return false;
  }

  private boolean jj_3R_460() {
    if (jj_3R_487()) return true;
    return false;
  }

  private boolean jj_3R_192() {
    if (jj_3R_312()) return true;
    return false;
  }

  private boolean jj_3R_363() {
    if (jj_3R_367()) return true;
    return false;
  }

  private boolean jj_3R_191() {
    if (jj_3R_311()) return true;
    return false;
  }

  private boolean jj_3_59() {
    if (jj_3R_108()) return true;
    return false;
  }

  private boolean jj_3R_190() {
    if (jj_3R_235()) return true;
    return false;
  }

  private boolean jj_3_77() {
    if (jj_3R_81()) return true;
    return false;
  }

  private boolean jj_3R_117() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_77()) jj_scanpos = xsp;
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3_44() {
    if (jj_3R_94()) return true;
    return false;
  }

  private boolean jj_3R_536() {
    if (jj_3R_461()) return true;
    return false;
  }

  private boolean jj_3R_174() {
    if (jj_scan_token(ALL)) return true;
    return false;
  }

  private boolean jj_3_99() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_189() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_362() {
    if (jj_scan_token(INDEX)) return true;
    return false;
  }

  private boolean jj_3R_173() {
    if (jj_scan_token(DISTINCT)) return true;
    return false;
  }

  private boolean jj_3R_81() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == DISTINCT &&
                                 !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_173()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == ALL &&
                                 !(getToken(2).kind == PERIOD || getToken(2).kind == DOUBLE_COLON);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_174()) return true;
    }
    return false;
  }

  private boolean jj_3R_188() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_535() {
    if (jj_scan_token(CALL)) return true;
    return false;
  }

  private boolean jj_3R_525() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_535()) {
    jj_scanpos = xsp;
    if (jj_3R_536()) return true;
    }
    return false;
  }

  private boolean jj_3R_187() {
    if (jj_scan_token(LEFT_BRACE)) return true;
    return false;
  }

  private boolean jj_3R_90() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = escapedValueFunctionFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_187()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == SCHEMA || getToken(2).kind == SQLID;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_188()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == ISOLATION;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_189()) {
    jj_scanpos = xsp;
    if (jj_3_44()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = newInvocationFollows(1);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_190()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = windowOrAggregateFunctionFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_191()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = miscBuiltinFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_192()) {
    jj_scanpos = xsp;
    if (jj_3_45()) {
    jj_scanpos = xsp;
    if (jj_3R_193()) {
    jj_scanpos = xsp;
    if (jj_3R_194()) {
    jj_scanpos = xsp;
    if (jj_3R_195()) {
    jj_scanpos = xsp;
    if (jj_3_46()) return true;
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_150() {
    if (jj_scan_token(REVOKE)) return true;
    return false;
  }

  private boolean jj_3_29() {
    if (jj_3R_81()) return true;
    return false;
  }

  private boolean jj_3R_298() {
    if (jj_3R_364()) return true;
    return false;
  }

  private boolean jj_3R_506() {
    if (jj_scan_token(LEFT_BRACE)) return true;
    return false;
  }

  private boolean jj_3R_542() {
    if (jj_scan_token(SELECT)) return true;
    return false;
  }

  private boolean jj_3R_505() {
    if (jj_3R_525()) return true;
    return false;
  }

  private boolean jj_3_98() {
    if (jj_3R_134()) return true;
    return false;
  }

  private boolean jj_3R_491() {
    if (jj_scan_token(LTRIM)) return true;
    return false;
  }

  private boolean jj_3R_149() {
    if (jj_scan_token(REVOKE)) return true;
    return false;
  }

  private boolean jj_3R_61() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == REVOKE &&
                                 ((getToken(2).kind == TRIGGER &&
                                     ((getToken(3).kind == COMMA &&
                                         isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                        getToken(3).kind == ON)) ||
                                    isPrivilegeKeywordExceptTrigger(getToken(2).kind));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_149()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == REVOKE &&
                                 ((getToken(2).kind == TRIGGER &&
                                     ((getToken(3).kind == COMMA &&
                                         !isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                        getToken(3).kind == FROM)) ||
                                    !isPrivilegeKeywordExceptTrigger(getToken(2).kind));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_150()) return true;
    }
    return false;
  }

  private boolean jj_3R_485() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_505()) {
    jj_scanpos = xsp;
    if (jj_3R_506()) return true;
    }
    return false;
  }

  private boolean jj_3R_297() {
    if (jj_3R_363()) return true;
    return false;
  }

  private boolean jj_3R_463() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_490()) {
    jj_scanpos = xsp;
    if (jj_3R_491()) return true;
    }
    return false;
  }

  private boolean jj_3R_490() {
    if (jj_scan_token(RTRIM)) return true;
    return false;
  }

  private boolean jj_3R_455() {
    if (jj_scan_token(GENERATED)) return true;
    return false;
  }

  private boolean jj_3R_160() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_296()) {
    jj_scanpos = xsp;
    if (jj_3R_297()) {
    jj_scanpos = xsp;
    if (jj_3_98()) {
    jj_scanpos = xsp;
    if (jj_3R_298()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_296() {
    if (jj_3R_362()) return true;
    return false;
  }

  private boolean jj_3R_541() {
    if (jj_3R_543()) return true;
    return false;
  }

  private boolean jj_3R_540() {
    if (jj_3R_542()) return true;
    return false;
  }

  private boolean jj_3R_539() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_540()) {
    jj_scanpos = xsp;
    if (jj_3R_541()) return true;
    }
    return false;
  }

  private boolean jj_3_76() {
    if (jj_3R_81()) return true;
    return false;
  }

  private boolean jj_3R_159() {
    if (jj_3R_295()) return true;
    return false;
  }

  private boolean jj_3R_70() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_159()) jj_scanpos = xsp;
    if (jj_3R_160()) return true;
    return false;
  }

  private boolean jj_3_11() {
    if (jj_3R_64()) return true;
    return false;
  }

  private boolean jj_3R_538() {
    if (jj_scan_token(LEFT_PAREN)) return true;
    return false;
  }

  private boolean jj_3R_366() {
    if (jj_3R_455()) return true;
    return false;
  }

  private boolean jj_3R_537() {
    if (jj_3R_539()) return true;
    return false;
  }

  private boolean jj_3R_534() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_537()) {
    jj_scanpos = xsp;
    if (jj_3R_538()) return true;
    }
    return false;
  }

  private boolean jj_3R_299() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_365()) {
    jj_scanpos = xsp;
    if (jj_3R_366()) return true;
    }
    return false;
  }

  private boolean jj_3R_365() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(274)) jj_scanpos = xsp;
    if (jj_scan_token(_DEFAULT)) return true;
    return false;
  }

  private boolean jj_3_41() {
    if (jj_scan_token(FROM)) return true;
    return false;
  }

  private boolean jj_3_12() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3_42() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_107() {
    if (jj_scan_token(OFFSET)) return true;
    return false;
  }

  private boolean jj_3R_106() {
    if (jj_scan_token(COMMA)) return true;
    return false;
  }

  private boolean jj_3_58() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_106()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == OFFSET &&
                                        getToken(3).kind != ROW &&
                                        getToken(3).kind != ROWS;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_107()) return true;
    }
    return false;
  }

  private boolean jj_3R_396() {
    if (jj_scan_token(TRIM)) return true;
    return false;
  }

  private boolean jj_3_108() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_133() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_484() {
    if (jj_scan_token(UPDATE)) return true;
    return false;
  }

  private boolean jj_3R_319() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_395()) {
    jj_scanpos = xsp;
    if (jj_3R_396()) return true;
    }
    return false;
  }

  private boolean jj_3R_395() {
    if (jj_3R_463()) return true;
    return false;
  }

  private boolean jj_3_75() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3_10() {
    if (jj_3R_65()) return true;
    return false;
  }

  private boolean jj_3R_238() {
    return false;
  }

  private boolean jj_3R_321() {
    if (jj_scan_token(RIGHT)) return true;
    return false;
  }

  private boolean jj_3R_118() {
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == LEFT_PAREN || getToken(2).kind == IDENTIFIER;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_238()) return true;
    if (jj_scan_token(OVER)) return true;
    return false;
  }

  private boolean jj_3R_320() {
    if (jj_scan_token(LEFT)) return true;
    return false;
  }

  private boolean jj_3R_221() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_320()) {
    jj_scanpos = xsp;
    if (jj_3R_321()) return true;
    }
    return false;
  }

  private boolean jj_3R_483() {
    if (jj_scan_token(INSERT)) return true;
    return false;
  }

  private boolean jj_3_97() {
    if (jj_3R_84()) return true;
    return false;
  }

  private boolean jj_3R_379() {
    if (jj_scan_token(ROWNUMBER)) return true;
    return false;
  }

  private boolean jj_3R_524() {
    if (jj_3R_534()) return true;
    return false;
  }

  private boolean jj_3R_220() {
    if (jj_scan_token(POSITION)) return true;
    return false;
  }

  private boolean jj_3_74() {
    if (jj_3R_118()) return true;
    return false;
  }

  private boolean jj_3R_378() {
    if (jj_3R_460()) return true;
    return false;
  }

  private boolean jj_3R_219() {
    if (jj_scan_token(LOCATE)) return true;
    return false;
  }

  private boolean jj_3R_377() {
    if (jj_scan_token(GROUP_CONCAT)) return true;
    return false;
  }

  private boolean jj_3R_218() {
    if (jj_3R_319()) return true;
    return false;
  }

  private boolean jj_3_73() {
    if (jj_3R_118()) return true;
    return false;
  }

  private boolean jj_3_72() {
    if (jj_3R_117()) return true;
    return false;
  }

  private boolean jj_3R_482() {
    if (jj_3R_504()) return true;
    return false;
  }

  private boolean jj_3R_217() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(496)) {
    jj_scanpos = xsp;
    if (jj_scan_token(434)) return true;
    }
    return false;
  }

  private boolean jj_3_107() {
    if (jj_3R_127()) return true;
    return false;
  }

  private boolean jj_3R_230() {
    if (jj_scan_token(NULLS)) return true;
    return false;
  }

  private boolean jj_3R_216() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(262)) {
    jj_scanpos = xsp;
    if (jj_scan_token(183)) return true;
    }
    return false;
  }

  private boolean jj_3R_229() {
    if (jj_scan_token(NULLS)) return true;
    return false;
  }

  private boolean jj_3R_105() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(2).kind == LAST;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_229()) {
    jj_scanpos = xsp;
    if (jj_3R_230()) return true;
    }
    return false;
  }

  private boolean jj_3R_311() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_376()) {
    jj_scanpos = xsp;
    if (jj_3R_377()) {
    jj_scanpos = xsp;
    if (jj_3R_378()) {
    jj_scanpos = xsp;
    if (jj_3R_379()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3R_376() {
    if (jj_scan_token(COUNT)) return true;
    return false;
  }

  private boolean jj_3_96() {
    if (jj_3R_133()) return true;
    return false;
  }

  private boolean jj_3R_215() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(389)) {
    jj_scanpos = xsp;
    if (jj_scan_token(242)) return true;
    }
    return false;
  }

  private boolean jj_3R_99() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_215()) {
    jj_scanpos = xsp;
    if (jj_3R_216()) {
    jj_scanpos = xsp;
    if (jj_3R_217()) {
    jj_scanpos = xsp;
    if (jj_3R_218()) {
    jj_scanpos = xsp;
    if (jj_3R_219()) {
    jj_scanpos = xsp;
    if (jj_3R_220()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = mysqlLeftRightFuncFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_221()) return true;
    }
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_504() {
    if (jj_3R_524()) return true;
    return false;
  }

  private boolean jj_3R_207() {
    if (jj_scan_token(CASE)) return true;
    return false;
  }

  private boolean jj_3R_214() {
    if (jj_scan_token(CURRENT_TIMESTAMP)) return true;
    return false;
  }

  private boolean jj_3R_493() {
    if (jj_scan_token(SECOND)) return true;
    return false;
  }

  private boolean jj_3R_464() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_492()) {
    jj_scanpos = xsp;
    if (jj_3R_493()) return true;
    }
    return false;
  }

  private boolean jj_3R_492() {
    if (jj_3R_508()) return true;
    return false;
  }

  private boolean jj_3R_213() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_212() {
    if (jj_scan_token(CURRENT_TIME)) return true;
    return false;
  }

  private boolean jj_3R_206() {
    if (jj_scan_token(NULLIF)) return true;
    return false;
  }

  private boolean jj_3R_211() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_205() {
    if (jj_3R_318()) return true;
    return false;
  }

  private boolean jj_3_9() {
    if (jj_3R_64()) return true;
    return false;
  }

  private boolean jj_3_57() {
    if (jj_3R_105()) return true;
    return false;
  }

  private boolean jj_3R_94() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_95()) {
    jj_scanpos = xsp;
    if (jj_3R_205()) {
    jj_scanpos = xsp;
    if (jj_3R_206()) {
    jj_scanpos = xsp;
    if (jj_3R_207()) return true;
    }
    }
    }
    return false;
  }

  private boolean jj_3_95() {
    if (jj_3R_129()) return true;
    return false;
  }

  private boolean jj_3R_210() {
    if (jj_scan_token(CURRENT_DATE)) return true;
    return false;
  }

  private boolean jj_3R_209() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_97() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = (getToken(1).kind == CURRENT && getToken(2).kind == DATE);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_209()) {
    jj_scanpos = xsp;
    if (jj_3R_210()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = (getToken(1).kind == CURRENT && getToken(2).kind == TIME);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_211()) {
    jj_scanpos = xsp;
    if (jj_3R_212()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = (getToken(1).kind == CURRENT && getToken(2).kind == TIMESTAMP);
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_213()) {
    jj_scanpos = xsp;
    if (jj_3R_214()) return true;
    }
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_65() {
    if (jj_3R_153()) return true;
    return false;
  }

  private boolean jj_3R_480() {
    if (jj_scan_token(MINUTE)) return true;
    return false;
  }

  private boolean jj_3R_479() {
    if (jj_scan_token(HOUR)) return true;
    return false;
  }

  private boolean jj_3R_386() {
    if (jj_scan_token(IDENTITY_VAL_LOCAL)) return true;
    return false;
  }

  private boolean jj_3R_478() {
    if (jj_scan_token(DAY)) return true;
    return false;
  }

  private boolean jj_3R_543() {
    if (jj_scan_token(VALUES)) return true;
    return false;
  }

  private boolean jj_3R_481() {
    if (jj_scan_token(DELETE)) return true;
    return false;
  }

  private boolean jj_3R_477() {
    if (jj_scan_token(MONTH)) return true;
    return false;
  }

  private boolean jj_3R_385() {
    if (jj_scan_token(MOD)) return true;
    return false;
  }

  private boolean jj_3R_432() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_476()) {
    jj_scanpos = xsp;
    if (jj_3R_477()) {
    jj_scanpos = xsp;
    if (jj_3R_478()) {
    jj_scanpos = xsp;
    if (jj_3R_479()) {
    jj_scanpos = xsp;
    if (jj_3R_480()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_476() {
    if (jj_scan_token(YEAR)) return true;
    return false;
  }

  private boolean jj_3R_384() {
    if (jj_scan_token(SQRT)) return true;
    return false;
  }

  private boolean jj_3R_383() {
    if (jj_scan_token(ABSVAL)) return true;
    return false;
  }

  private boolean jj_3R_445() {
    if (jj_3R_485()) return true;
    return false;
  }

  private boolean jj_3R_444() {
    if (jj_3R_484()) return true;
    return false;
  }

  private boolean jj_3R_316() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_382()) {
    jj_scanpos = xsp;
    if (jj_3R_383()) {
    jj_scanpos = xsp;
    if (jj_3R_384()) {
    jj_scanpos = xsp;
    if (jj_3R_385()) {
    jj_scanpos = xsp;
    if (jj_3R_386()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_382() {
    if (jj_scan_token(ABS)) return true;
    return false;
  }

  private boolean jj_3R_443() {
    if (jj_3R_483()) return true;
    return false;
  }

  private boolean jj_3R_434() {
    if (jj_scan_token(SECOND)) return true;
    return false;
  }

  private boolean jj_3R_442() {
    if (jj_3R_482()) return true;
    return false;
  }

  private boolean jj_3R_441() {
    if (jj_3R_481()) return true;
    return false;
  }

  private boolean jj_3R_345() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_433()) {
    jj_scanpos = xsp;
    if (jj_3R_434()) return true;
    }
    return false;
  }

  private boolean jj_3R_433() {
    if (jj_3R_432()) return true;
    return false;
  }

  private boolean jj_3R_354() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_441()) {
    jj_scanpos = xsp;
    if (jj_3R_442()) {
    jj_scanpos = xsp;
    if (jj_3R_443()) {
    jj_scanpos = xsp;
    if (jj_3R_444()) {
    jj_scanpos = xsp;
    if (jj_3R_445()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_148() {
    if (jj_scan_token(GRANT)) return true;
    return false;
  }

  private boolean jj_3_8() {
    if (jj_3R_62()) return true;
    return false;
  }

  private boolean jj_3_7() {
    if (jj_3R_63()) return true;
    return false;
  }

  private boolean jj_3R_146() {
    if (jj_scan_token(SET)) return true;
    return false;
  }

  private boolean jj_3R_412() {
    if (jj_3R_467()) return true;
    return false;
  }

  private boolean jj_3R_147() {
    if (jj_scan_token(GRANT)) return true;
    return false;
  }

  private boolean jj_3R_60() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == GRANT &&
                                 ((getToken(2).kind == TRIGGER &&
                                     ((getToken(3).kind == COMMA &&
                                         isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                        getToken(3).kind == ON)) ||
                                    isPrivilegeKeywordExceptTrigger(getToken(2).kind));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_147()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == GRANT &&
                                 ((getToken(2).kind == TRIGGER &&
                                     ((getToken(3).kind == COMMA &&
                                         !isPrivilegeKeywordExceptTrigger(getToken(4).kind)) ||
                                        getToken(3).kind == TO)) ||
                                    !isPrivilegeKeywordExceptTrigger(getToken(2).kind));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_148()) return true;
    }
    return false;
  }

  private boolean jj_3R_329() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_412()) jj_scanpos = xsp;
    if (jj_scan_token(JOIN)) return true;
    return false;
  }

  private boolean jj_3_6() {
    if (jj_3R_63()) return true;
    return false;
  }

  private boolean jj_3_5() {
    if (jj_3R_62()) return true;
    return false;
  }

  private boolean jj_3R_344() {
    if (jj_3R_432()) return true;
    return false;
  }

  private boolean jj_3R_237() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = straightJoinFollows();
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_328()) {
    jj_scanpos = xsp;
    if (jj_3R_329()) return true;
    }
    return false;
  }

  private boolean jj_3R_328() {
    if (jj_scan_token(STRAIGHT_JOIN)) return true;
    return false;
  }

  private boolean jj_3R_145() {
    if (jj_scan_token(SET)) return true;
    return false;
  }

  private boolean jj_3R_59() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == SET && getToken(2).kind != CURRENT;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_145()) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == SET && getToken(2).kind == CURRENT;
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_146()) return true;
    }
    return false;
  }

  private boolean jj_3R_262() {
    if (jj_3R_345()) return true;
    return false;
  }

  private boolean jj_3R_95() {
    if (jj_3R_153()) return true;
    return false;
  }

  private boolean jj_3_86() {
    if (jj_3R_127()) return true;
    return false;
  }

  private boolean jj_3R_533() {
    if (jj_scan_token(FULL)) return true;
    return false;
  }

  private boolean jj_3R_532() {
    if (jj_scan_token(RIGHT)) return true;
    return false;
  }

  private boolean jj_3R_261() {
    if (jj_3R_344()) return true;
    return false;
  }

  private boolean jj_3_94() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_353() {
    if (jj_scan_token(ALTER)) return true;
    return false;
  }

  private boolean jj_3R_531() {
    if (jj_scan_token(LEFT)) return true;
    return false;
  }

  private boolean jj_3R_131() {
    Token xsp;
    xsp = jj_scanpos;
    jj_lookingAhead = true;
    jj_semLA = (getToken(2).kind == TO) ||
                       ((getToken(2).kind == LEFT_PAREN) &&
                        (getToken(5).kind == TO));
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_261()) {
    jj_scanpos = xsp;
    if (jj_3R_262()) return true;
    }
    return false;
  }

  private boolean jj_3R_510() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_531()) {
    jj_scanpos = xsp;
    if (jj_3R_532()) {
    jj_scanpos = xsp;
    if (jj_3R_533()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_247() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_126() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_247()) jj_scanpos = xsp;
    if (jj_3R_246()) return true;
    return false;
  }

  private boolean jj_3R_291() {
    if (jj_scan_token(CURRENT)) return true;
    return false;
  }

  private boolean jj_3R_152() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_scan_token(228)) {
    jj_scanpos = xsp;
    jj_lookingAhead = true;
    jj_semLA = getToken(1).kind == CURRENT &&
                                 (getToken(2).kind == SCHEMA || getToken(2).kind == SQLID );
    jj_lookingAhead = false;
    if (!jj_semLA || jj_3R_291()) return true;
    }
    return false;
  }

  private boolean jj_3R_499() {
    if (jj_3R_510()) return true;
    return false;
  }

  private boolean jj_3R_500() {
    if (jj_scan_token(INTERVAL)) return true;
    return false;
  }

  private boolean jj_3R_467() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_498()) {
    jj_scanpos = xsp;
    if (jj_3R_499()) return true;
    }
    return false;
  }

  private boolean jj_3R_498() {
    if (jj_scan_token(INNER)) return true;
    return false;
  }

  private boolean jj_3_85() {
    if (jj_3R_126()) return true;
    return false;
  }

  private boolean jj_3R_357() {
    if (jj_scan_token(TRUNCATE)) return true;
    return false;
  }

  private boolean jj_3R_431() {
    if (jj_scan_token(YEAR)) return true;
    return false;
  }

  private boolean jj_3R_63() {
    if (jj_3R_152()) return true;
    return false;
  }

  private boolean jj_3R_352() {
    if (jj_scan_token(DROP)) return true;
    return false;
  }

  private boolean jj_3R_430() {
    if (jj_scan_token(DATETIME)) return true;
    return false;
  }

  private boolean jj_3R_429() {
    if (jj_scan_token(TIMESTAMP)) return true;
    return false;
  }

  private boolean jj_3R_428() {
    if (jj_scan_token(TIME)) return true;
    return false;
  }

  private boolean jj_3_93() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_343() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_427()) {
    jj_scanpos = xsp;
    if (jj_3R_428()) {
    jj_scanpos = xsp;
    if (jj_3R_429()) {
    jj_scanpos = xsp;
    if (jj_3R_430()) {
    jj_scanpos = xsp;
    if (jj_3R_431()) return true;
    }
    }
    }
    }
    return false;
  }

  private boolean jj_3R_427() {
    if (jj_scan_token(DATE)) return true;
    return false;
  }

  private boolean jj_3R_116() {
    if (jj_3R_237()) return true;
    return false;
  }

  private boolean jj_3R_495() {
    if (jj_scan_token(TIMESTAMPDIFF)) return true;
    return false;
  }

  private boolean jj_3R_351() {
    if (jj_scan_token(CREATE)) return true;
    return false;
  }

  private boolean jj_3R_417() {
    if (jj_scan_token(INOUT)) return true;
    return false;
  }

  private boolean jj_3R_416() {
    if (jj_scan_token(OUT)) return true;
    return false;
  }

  private boolean jj_3R_331() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_415()) {
    jj_scanpos = xsp;
    if (jj_3R_416()) {
    jj_scanpos = xsp;
    if (jj_3R_417()) return true;
    }
    }
    return false;
  }

  private boolean jj_3R_415() {
    if (jj_scan_token(IN)) return true;
    return false;
  }

  private boolean jj_3R_244() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_331()) jj_scanpos = xsp;
    return false;
  }

  private boolean jj_3R_454() {
    if (jj_3R_486()) return true;
    return false;
  }

  private boolean jj_3R_465() {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_494()) {
    jj_scanpos = xsp;
    if (jj_3R_495()) return true;
    }
    return false;
  }

  private boolean jj_3R_494() {
    if (jj_scan_token(TIMESTAMPADD)) return true;
    return false;
  }

  private boolean jj_3R_245() {
    if (jj_3R_67()) return true;
    return false;
  }

  private boolean jj_3R_125() {
    if (jj_3R_244()) return true;
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3R_245()) jj_scanpos = xsp;
    if (jj_3R_246()) return true;
    return false;
  }

  private boolean jj_3_71() {
    if (jj_3R_116()) return true;
    return false;
  }

  /** Generated Token Manager. */
  public SQLGrammarTokenManager token_source;
  /** Current token. */
  public Token token;
  /** Next token. */
  public Token jj_nt;
  private Token jj_scanpos, jj_lastpos;
  private int jj_la;
  /** Whether we are looking ahead. */
  private boolean jj_lookingAhead = false;
  private boolean jj_semLA;
  private int jj_gen;
  final private int[] jj_la1 = new int[481];
  static private int[] jj_la1_0;
  static private int[] jj_la1_1;
  static private int[] jj_la1_2;
  static private int[] jj_la1_3;
  static private int[] jj_la1_4;
  static private int[] jj_la1_5;
  static private int[] jj_la1_6;
  static private int[] jj_la1_7;
  static private int[] jj_la1_8;
  static private int[] jj_la1_9;
  static private int[] jj_la1_10;
  static private int[] jj_la1_11;
  static private int[] jj_la1_12;
  static private int[] jj_la1_13;
  static private int[] jj_la1_14;
  static private int[] jj_la1_15;
  static private int[] jj_la1_16;
  static private int[] jj_la1_17;
  static {
      jj_la1_init_0();
      jj_la1_init_1();
      jj_la1_init_2();
      jj_la1_init_3();
      jj_la1_init_4();
      jj_la1_init_5();
      jj_la1_init_6();
      jj_la1_init_7();
      jj_la1_init_8();
      jj_la1_init_9();
      jj_la1_init_10();
      jj_la1_init_11();
      jj_la1_init_12();
      jj_la1_init_13();
      jj_la1_init_14();
      jj_la1_init_15();
      jj_la1_init_16();
      jj_la1_init_17();
   }
   private static void jj_la1_init_0() {
      jj_la1_0 = new int[] {0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_1() {
      jj_la1_1 = new int[] {0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x8000000,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_2() {
      jj_la1_2 = new int[] {0x0,0x0,0x20000010,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x3010000,0x0,0x0,0x3000000,0x0,0x3000000,0x0,0x0,0x0,0x0,0x0,0x2000000,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x3010000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x4,0x4,0x4,0x0,0x0,0x4,0x4,0x0,0x0,0x0,0x0,0x100,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x8000,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x0,0x800000,0xc000000,0x0,0x4c000000,0x0,0x0,0x1000000,0x0,0x0,0x100,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200,0x200,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x44,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x200,0x200,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x2000,0x1000000,0x0,0x0,0x0,0x0,0x0,0x1000,0x1000,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x100,0x100,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x10000000,0x0,0x2,0x0,0x0,0x0,0x0,0x4,0x0,0x16,0x0,0x0,0x0,0x0,0x0,0x100,0x100,0x0,0x100,0x100,0x12,0x0,0x0,0x0,0x0,0x0,0x100,0x100000,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x0,0x4,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x4,0x4,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xbdeef9fe,0x42110600,0x0,0xbdeef9fe,0xbdeef9fe,0x0,0x0,0x0,0x0,0x200,0x200,};
   }
   private static void jj_la1_init_3() {
      jj_la1_3 = new int[] {0x0,0x8000000,0x8900800,0x0,0x4,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x8000000,0x0,0x0,0x1000000,0x1000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x600000,0x600000,0x0,0x0,0x600000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000,0x20000,0x0,0x0,0x10000000,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000000,0x0,0x0,0x0,0x0,0x0,0x1000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x10000000,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x8000,0x10000,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000000,0x0,0x0,0x0,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000000,0x20000,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x1000000,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x900000,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0xa9f7fbbe,0x56080441,0x0,0xa9f7fbbe,0xa9f7fbbe,0x0,0x0,0x0,0x0,0x10000000,0x10000000,};
   }
   private static void jj_la1_init_4() {
      jj_la1_4 = new int[] {0x0,0x0,0x4404,0x0,0x0,0x400000,0x10400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100000,0x0,0x4000,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10000,0x2,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x80000000,0x0,0x0,0x0,0x80000000,0x80000000,0x0,0x80,0x1,0x1,0x1,0x1,0x80,0x0,0x1,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x800,0x0,0x0,0x40,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x8000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x100000,0x0,0x10000000,0x40000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000,0x0,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x200000,0x200000,0x200000,0x200000,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x18,0x18,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x10000000,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x400000,0x400,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x100000,0x4000,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100000,0x0,0x40,0x0,0x0,0x0,0xf9f77eff,0x6088100,0x0,0xf9f77eff,0xf9f77eff,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_5() {
      jj_la1_5 = new int[] {0x0,0x400,0x400,0x0,0x0,0x10,0x10,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xa0000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x1800,0x0,0x0,0x0,0x1800,0x1800,0x1800,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x8000000,0x0,0x0,0x0,0x8000000,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x0,0x400008,0x0,0x0,0x400008,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x8000000,0x0,0x0,0x800000,0x0,0x8,0x0,0x8,0x200000,0x800000,0x0,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1800,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40010,0x0,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000000,0x200080,0x0,0x200080,0x200000,0x200080,0x220080,0x0,0x0,0x0,0x0,0x0,0x0,0x6000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x6000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x10000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40010,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xeff2ffbd,0x100d0042,0x0,0xeff2ffbd,0xeff2ffbd,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_6() {
      jj_la1_6 = new int[] {0x0,0x0,0x100000,0x0,0x0,0x2000000,0x2000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x4,0x200000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x20,0x0,0x0,0x20,0x0,0x0,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x4,0x0,0x8,0x0,0x4,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80,0x8000000,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x2000,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x2000,0x0,0x0,0x0,0x0,0x0,0x2,0x2,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x6,0x0,0x0,0x6,0x0,0x0,0x0,0x0,0x0,0x0,0x6,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x2,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x200,0x0,0x0,0x10,0x1000,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x400000,0x100,0x0,0x100,0x100,0x0,0x80000002,0x8,0x80000002,0x0,0x4,0x0,0x0,0x40000c,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000000,0x80000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000000,0x20000000,0x0,0x0,0x20000000,0x0,0x2000000,0x0,0x0,0x0,0x0,0x0,0x20000000,0x0,0x4000000,0x0,0x20000000,0x2,0x1,0x1,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0xb659f7ff,0x0,0x49a60800,0xb659f7ff,0xb659f7ff,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_7() {
      jj_la1_7 = new int[] {0x0,0x80,0x80,0x0,0x4,0x400010,0x400010,0x8000100,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000000,0x4,0x0,0x0,0x4,0x8000000,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x800,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x40,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x40,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x80040000,0x80000000,0x0,0x40000000,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x40,0x0,0x0,0x200,0x200,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x2,0x2,0x2,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x400000,0x400000,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x200,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x4000000,0x4000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x10,0x0,0x8000100,0x0,0x0,0x4000000,0x0,0x200,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000000,0x4000000,0x400000,0x4000000,0x4000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x80,0x400000,0x0,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000000,0x8,0x0,0x0,0x0,0x0,0xf75fdeff,0x0,0x8a02100,0xf75fdeff,0xf75fdeff,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_8() {
      jj_la1_8 = new int[] {0x0,0x420,0x420,0x0,0x0,0x4008,0x4000,0x0,0x420,0x10000,0x0,0x0,0x10000,0x0,0x0,0x0,0x0,0x0,0x40000,0x10000,0x0,0x0,0x0,0x10000,0x0,0x0,0x0,0x8,0x80000,0x0,0x0,0x8,0x0,0x0,0x80000,0x80000,0x0,0x0,0x0,0x0,0x40000,0x40000,0x44200000,0x0,0x2000,0x1000,0x0,0x0,0x0,0x2000,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x44000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x200000,0x200000,0x0,0x0,0x200000,0x0,0x0,0x0,0x200000,0x200000,0x0,0x4,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x400,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x11,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x200000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x200000,0xc00000,0x0,0x200000,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xc00000,0x200,0x0,0x200000,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x1,0x80,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10000,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x10000000,0x0,0x0,0x10000000,0x10000000,0x0,0x0,0x0,0x0,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x40000,0x2000000,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x1,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x100000,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x420,0x0,0x0,0x0,0x40000,0x0,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x25b5fd,0xffc00000,0x184a02,0x25b5fd,0x25b5fd,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_9() {
      jj_la1_9 = new int[] {0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x8000,0x100060,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x60,0x80,0x0,0x0,0x80,0x0,0x0,0x0,0x80,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x0,0x80,0x2,0x0,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x80,0x0,0x0,0x0,0x0,0x80,0x80010000,0x0,0x80,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x80010000,0x1000000,0x0,0x20a0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20a0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x40000000,0x20000,0x8,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x401204,0x1000,0x0,0x401204,0x0,0x200100,0x0,0x0,0x80000,0x80000,0x401204,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x0,0x20000,0x0,0x0,0x0,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x180000,0x9fc7fcf7,0x0,0x180000,0x180000,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_10() {
      jj_la1_10 = new int[] {0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x410000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x4,0x0,0x0,0x0,0x4,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x8000000,0x10000000,0x20000000,0x40000000,0x80000000,0x0,0x4,0x0,0x0,0xf8000004,0x8000000,0x10000000,0x20000000,0x40000000,0x80000000,0x0,0x4,0x0,0x0,0xf8000004,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x100,0x800,0x0,0x200000,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x80001,0x0,0x1000020,0x80001,0x80000,0x0,0x0,0x0,0x0,0x0,0x80001,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x200000,0x200000,0x200000,0x200000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4020000,0x4020000,0x0,0x4020000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x7ff,0xfffff000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_11() {
      jj_la1_11 = new int[] {0x0,0x40000,0x40000,0x1000,0x4000000,0x2040,0x2040,0x0,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10020300,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x300,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x480000,0xc00,0x0,0x0,0x0,0x0,0x0,0x1,0x2,0x4,0x8,0xf,0x0,0x0,0x0,0x0,0x0,0x1,0x2,0x4,0x8,0xf,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x20000000,0x0,0x0,0x20000000,0x0,0x0,0x0,0x1000000,0x0,0x0,0xf00,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xf00,0x0,0x0,0x300000,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x40000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x300000,0x0,0x10000,0x10000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000000,0x0,0x2040,0x8000,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x6f360000,0x10c80000,0x8001ffff,0x6f360000,0x6f360000,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_12() {
      jj_la1_12 = new int[] {0x0,0x0,0x0,0x0,0x80000,0x2,0x2,0x2,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x28,0x8,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x780,0x0,0x0,0x0,0x780,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x800,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x7800000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40100000,0x100000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x200000,0x0,0x0,0xff9,0xffbff000,0x6,0xff9,0xff9,0x0,0x0,0x800,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_13() {
      jj_la1_13 = new int[] {0x0,0x0,0x100000,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x5a00000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x5a00000,0x2400000,0x0,0x0,0x0,0x2400000,0x0,0x2400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x0,0x0,0x0,0xc0000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2400000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1020,0x0,0x0,0x0,0x0,0x0,0x1020,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xe0000e00,0x0,0x0,0x0,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x28000,0x28000,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x144,0x0,0x0,0x0,0x0,0xffffffff,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_14() {
      jj_la1_14 = new int[] {0x0,0x0,0x80000,0x0,0x0,0x10000000,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x410400,0x0,0x0,0x410400,0x0,0x0,0x0,0x0,0x200,0x200,0x410400,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x0,0x1,0x0,0x0,0x0,0x0,0x18c,0x0,0x0,0x0,0x0,0x18c,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4004000,0x0,0x0,0x0,0x0,0x0,0x80000000,0x0,0x0,0x0,0x200000,0x0,0x3000000,0x3000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000000,0x0,0x0,0x0,0x0,0x0,0x80000,0x2000,0x0,0x0,0x20000000,0x0,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x8000,0x2,0x0,0x0,0x210,0x2f,0xfffffdc0,0x210,0x210,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_15() {
      jj_la1_15 = new int[] {0x0,0x40000000,0x40000000,0x0,0x0,0x4000,0x4000,0x0,0x40000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2c00,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2c00,0x1000,0x0,0x0,0x0,0x1000,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000000,0x40000000,0x0,0x0,0x0,0x0,0xe000000,0x1000000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x0,0x0,0x200000,0x0,0x0,0x0,0x200000,0x8,0x0,0x0,0x0,0x0,0x0,0x10000,0x0,0x0,0x0,0x0,0x10000,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x8,0x0,0x0,0x0,0x0,0x0,0x100,0x0,0x0,0x0,0x0,0x0,0x1000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000000,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x80000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x40000000,0x0,0xa00000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x40000,0x40000,0x0,0x0,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x8000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x4000,0x0,0x0,0x0,0x0,0x0,0x40000000,0x0,0x100000,0x100000,0x0,0x0,0x0,0x80,0x0,0x80,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xffffff,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,};
   }
   private static void jj_la1_init_16() {
      jj_la1_16 = new int[] {0x400,0x20040001,0x20040001,0x0,0x0,0x0,0x0,0x10000000,0x20040001,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20040000,0x20040000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x1,0x0,0x1,0x0,0x0,0x0,0x0,0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x1,0x0,0x0,0x0,0x10,0x1,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x40,0x10000000,0x0,0x28,0x0,0x0,0x1,0x1,0x0,0x1,0x10,0x1,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x10,0x2000004,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x3f800,0x0,0x0,0x0,0x3f800,0x0,0x28,0xc00000,0x0,0x4000084,0x28,0x1000040,0x40,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x10,0x0,0x0,0x10,0x10,0x10,0x0,0x0,0x0,0x2,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x12,0x0,0x20040000,0x0,0x10,0x10,0x0,0x0,0x0,0x0,0x20040028,0x0,0x0,0x20040028,0x20040028,0x0,0x20040028,0x20040028,0x20040028,0x10,0x0,0x0,0x10,0x10,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x10,0x0,0x0,0x1,0x10,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x1,0x0,0x1,0x10,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x4,0x0,0x0,0x0,0x1,0x10,0x0,0x0,0x0,0x10,0x0,0x0,0x20040000,0x10,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x10,0x0,0x10,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x10,0x0,0x0,0x0,0x28,0x28,0x0,0x28,0x0,0x0,0x0,0x0,0x0,0x0,0x40,0x40,0x0,0x0,0x2000,0x2000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x20040000,0x2000,0x0,0x0,0x20040000,0x0,0x0,0x0,0x2000,0x0,0x20040000,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x28,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x10,0x0,0x0,0x0,0x1,0x10,0x1,0x1,0x1,0x0,0x10,0x0,0x10,0x0,0x0,0x0,0x0,0x0,0x0,0x20040001,0x1,0x0,0x0,0x0,0x10,0x1,0x0,0x1,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x0,0x0,0x0,0x0,0x0,0x0,0x10000000,0x10,0x0,0x10,0x0,0x0,};
   }
   private static void jj_la1_init_17() {
      jj_la1_17 = new int[] {0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x1400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x400,0x400,0x0,0x400,0x400,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0xe440,0x0,0x0,0x8400,0x0,0x0,0x0,0x2140,0x2040,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2140,0x0,0x0,0x0,0x2140,0x0,0x0,0x0,0x0,0x2040,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x400,0x400,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x0,0x2140,0x0,0x2140,0x2140,0x0,0x0,0x0,0x0,0x28,0x28,0x0,0x0,0x0,0x0,0x0,0x28,0x0,0x0,0x0,0x0,0x0,};
   }
  final private JJCalls[] jj_2_rtns = new JJCalls[115];
  private boolean jj_rescan = false;
  private int jj_gc = 0;

  /** Constructor with user supplied CharStream. */
  public SQLGrammar(CharStream stream) {
    token_source = new SQLGrammarTokenManager(this, stream);
    token = new Token();
    token.next = jj_nt = token_source.getNextToken();
    jj_gen = 0;
    for (int i = 0; i < 481; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Reinitialise. */
  public void ReInit(CharStream stream) {
    token_source.ReInit(stream);
    token = new Token();
    token.next = jj_nt = token_source.getNextToken();
    jj_lookingAhead = false;
    jj_gen = 0;
    for (int i = 0; i < 481; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Constructor with generated Token Manager. */
  public SQLGrammar(SQLGrammarTokenManager tm) {
    token_source = tm;
    token = new Token();
    token.next = jj_nt = token_source.getNextToken();
    jj_gen = 0;
    for (int i = 0; i < 481; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Reinitialise. */
  public void ReInit(SQLGrammarTokenManager tm) {
    token_source = tm;
    token = new Token();
    token.next = jj_nt = token_source.getNextToken();
    jj_gen = 0;
    for (int i = 0; i < 481; i++) jj_la1[i] = -1;
    for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  private Token jj_consume_token(int kind) throws ParseException {
    Token oldToken = token;
    if ((token = jj_nt).next != null) jj_nt = jj_nt.next;
    else jj_nt = jj_nt.next = token_source.getNextToken();
    if (token.kind == kind) {
      jj_gen++;
      if (++jj_gc > 100) {
        jj_gc = 0;
        for (int i = 0; i < jj_2_rtns.length; i++) {
          JJCalls c = jj_2_rtns[i];
          while (c != null) {
            if (c.gen < jj_gen) c.first = null;
            c = c.next;
          }
        }
      }
      return token;
    }
    jj_nt = token;
    token = oldToken;
    jj_kind = kind;
    throw generateParseException();
  }

  static private final class LookaheadSuccess extends java.lang.Error { }
  final private LookaheadSuccess jj_ls = new LookaheadSuccess();
  private boolean jj_scan_token(int kind) {
    if (jj_scanpos == jj_lastpos) {
      jj_la--;
      if (jj_scanpos.next == null) {
        jj_lastpos = jj_scanpos = jj_scanpos.next = token_source.getNextToken();
      } else {
        jj_lastpos = jj_scanpos = jj_scanpos.next;
      }
    } else {
      jj_scanpos = jj_scanpos.next;
    }
    if (jj_rescan) {
      int i = 0; Token tok = token;
      while (tok != null && tok != jj_scanpos) { i++; tok = tok.next; }
      if (tok != null) jj_add_error_token(kind, i);
    }
    if (jj_scanpos.kind != kind) return true;
    if (jj_la == 0 && jj_scanpos == jj_lastpos) throw jj_ls;
    return false;
  }


/** Get the next Token. */
  final public Token getNextToken() {
    if ((token = jj_nt).next != null) jj_nt = jj_nt.next;
    else jj_nt = jj_nt.next = token_source.getNextToken();
    jj_gen++;
    return token;
  }

/** Get the specific Token. */
  final public Token getToken(int index) {
    Token t = jj_lookingAhead ? jj_scanpos : token;
    for (int i = 0; i < index; i++) {
      if (t.next != null) t = t.next;
      else t = t.next = token_source.getNextToken();
    }
    return t;
  }

  private java.util.List<int[]> jj_expentries = new java.util.ArrayList<int[]>();
  private int[] jj_expentry;
  private int jj_kind = -1;
  private int[] jj_lasttokens = new int[100];
  private int jj_endpos;

  private void jj_add_error_token(int kind, int pos) {
    if (pos >= 100) return;
    if (pos == jj_endpos + 1) {
      jj_lasttokens[jj_endpos++] = kind;
    } else if (jj_endpos != 0) {
      jj_expentry = new int[jj_endpos];
      for (int i = 0; i < jj_endpos; i++) {
        jj_expentry[i] = jj_lasttokens[i];
      }
      jj_entries_loop: for (java.util.Iterator<?> it = jj_expentries.iterator(); it.hasNext();) {
        int[] oldentry = (int[])(it.next());
        if (oldentry.length == jj_expentry.length) {
          for (int i = 0; i < jj_expentry.length; i++) {
            if (oldentry[i] != jj_expentry[i]) {
              continue jj_entries_loop;
            }
          }
          jj_expentries.add(jj_expentry);
          break jj_entries_loop;
        }
      }
      if (pos != 0) jj_lasttokens[(jj_endpos = pos) - 1] = kind;
    }
  }

  /** Generate ParseException. */
  public ParseException generateParseException() {
    jj_expentries.clear();
    boolean[] la1tokens = new boolean[560];
    if (jj_kind >= 0) {
      la1tokens[jj_kind] = true;
      jj_kind = -1;
    }
    for (int i = 0; i < 481; i++) {
      if (jj_la1[i] == jj_gen) {
        for (int j = 0; j < 32; j++) {
          if ((jj_la1_0[i] & (1<<j)) != 0) {
            la1tokens[j] = true;
          }
          if ((jj_la1_1[i] & (1<<j)) != 0) {
            la1tokens[32+j] = true;
          }
          if ((jj_la1_2[i] & (1<<j)) != 0) {
            la1tokens[64+j] = true;
          }
          if ((jj_la1_3[i] & (1<<j)) != 0) {
            la1tokens[96+j] = true;
          }
          if ((jj_la1_4[i] & (1<<j)) != 0) {
            la1tokens[128+j] = true;
          }
          if ((jj_la1_5[i] & (1<<j)) != 0) {
            la1tokens[160+j] = true;
          }
          if ((jj_la1_6[i] & (1<<j)) != 0) {
            la1tokens[192+j] = true;
          }
          if ((jj_la1_7[i] & (1<<j)) != 0) {
            la1tokens[224+j] = true;
          }
          if ((jj_la1_8[i] & (1<<j)) != 0) {
            la1tokens[256+j] = true;
          }
          if ((jj_la1_9[i] & (1<<j)) != 0) {
            la1tokens[288+j] = true;
          }
          if ((jj_la1_10[i] & (1<<j)) != 0) {
            la1tokens[320+j] = true;
          }
          if ((jj_la1_11[i] & (1<<j)) != 0) {
            la1tokens[352+j] = true;
          }
          if ((jj_la1_12[i] & (1<<j)) != 0) {
            la1tokens[384+j] = true;
          }
          if ((jj_la1_13[i] & (1<<j)) != 0) {
            la1tokens[416+j] = true;
          }
          if ((jj_la1_14[i] & (1<<j)) != 0) {
            la1tokens[448+j] = true;
          }
          if ((jj_la1_15[i] & (1<<j)) != 0) {
            la1tokens[480+j] = true;
          }
          if ((jj_la1_16[i] & (1<<j)) != 0) {
            la1tokens[512+j] = true;
          }
          if ((jj_la1_17[i] & (1<<j)) != 0) {
            la1tokens[544+j] = true;
          }
        }
      }
    }
    for (int i = 0; i < 560; i++) {
      if (la1tokens[i]) {
        jj_expentry = new int[1];
        jj_expentry[0] = i;
        jj_expentries.add(jj_expentry);
      }
    }
    jj_endpos = 0;
    jj_rescan_token();
    jj_add_error_token(0, 0);
    int[][] exptokseq = new int[jj_expentries.size()][];
    for (int i = 0; i < jj_expentries.size(); i++) {
      exptokseq[i] = jj_expentries.get(i);
    }
    return new ParseException(token, exptokseq, tokenImage);
  }

  /** Enable tracing. */
  final public void enable_tracing() {
  }

  /** Disable tracing. */
  final public void disable_tracing() {
  }

  private void jj_rescan_token() {
    jj_rescan = true;
    for (int i = 0; i < 115; i++) {
    try {
      JJCalls p = jj_2_rtns[i];
      do {
        if (p.gen > jj_gen) {
          jj_la = p.arg; jj_lastpos = jj_scanpos = p.first;
          switch (i) {
            case 0: jj_3_1(); break;
            case 1: jj_3_2(); break;
            case 2: jj_3_3(); break;
            case 3: jj_3_4(); break;
            case 4: jj_3_5(); break;
            case 5: jj_3_6(); break;
            case 6: jj_3_7(); break;
            case 7: jj_3_8(); break;
            case 8: jj_3_9(); break;
            case 9: jj_3_10(); break;
            case 10: jj_3_11(); break;
            case 11: jj_3_12(); break;
            case 12: jj_3_13(); break;
            case 13: jj_3_14(); break;
            case 14: jj_3_15(); break;
            case 15: jj_3_16(); break;
            case 16: jj_3_17(); break;
            case 17: jj_3_18(); break;
            case 18: jj_3_19(); break;
            case 19: jj_3_20(); break;
            case 20: jj_3_21(); break;
            case 21: jj_3_22(); break;
            case 22: jj_3_23(); break;
            case 23: jj_3_24(); break;
            case 24: jj_3_25(); break;
            case 25: jj_3_26(); break;
            case 26: jj_3_27(); break;
            case 27: jj_3_28(); break;
            case 28: jj_3_29(); break;
            case 29: jj_3_30(); break;
            case 30: jj_3_31(); break;
            case 31: jj_3_32(); break;
            case 32: jj_3_33(); break;
            case 33: jj_3_34(); break;
            case 34: jj_3_35(); break;
            case 35: jj_3_36(); break;
            case 36: jj_3_37(); break;
            case 37: jj_3_38(); break;
            case 38: jj_3_39(); break;
            case 39: jj_3_40(); break;
            case 40: jj_3_41(); break;
            case 41: jj_3_42(); break;
            case 42: jj_3_43(); break;
            case 43: jj_3_44(); break;
            case 44: jj_3_45(); break;
            case 45: jj_3_46(); break;
            case 46: jj_3_47(); break;
            case 47: jj_3_48(); break;
            case 48: jj_3_49(); break;
            case 49: jj_3_50(); break;
            case 50: jj_3_51(); break;
            case 51: jj_3_52(); break;
            case 52: jj_3_53(); break;
            case 53: jj_3_54(); break;
            case 54: jj_3_55(); break;
            case 55: jj_3_56(); break;
            case 56: jj_3_57(); break;
            case 57: jj_3_58(); break;
            case 58: jj_3_59(); break;
            case 59: jj_3_60(); break;
            case 60: jj_3_61(); break;
            case 61: jj_3_62(); break;
            case 62: jj_3_63(); break;
            case 63: jj_3_64(); break;
            case 64: jj_3_65(); break;
            case 65: jj_3_66(); break;
            case 66: jj_3_67(); break;
            case 67: jj_3_68(); break;
            case 68: jj_3_69(); break;
            case 69: jj_3_70(); break;
            case 70: jj_3_71(); break;
            case 71: jj_3_72(); break;
            case 72: jj_3_73(); break;
            case 73: jj_3_74(); break;
            case 74: jj_3_75(); break;
            case 75: jj_3_76(); break;
            case 76: jj_3_77(); break;
            case 77: jj_3_78(); break;
            case 78: jj_3_79(); break;
            case 79: jj_3_80(); break;
            case 80: jj_3_81(); break;
            case 81: jj_3_82(); break;
            case 82: jj_3_83(); break;
            case 83: jj_3_84(); break;
            case 84: jj_3_85(); break;
            case 85: jj_3_86(); break;
            case 86: jj_3_87(); break;
            case 87: jj_3_88(); break;
            case 88: jj_3_89(); break;
            case 89: jj_3_90(); break;
            case 90: jj_3_91(); break;
            case 91: jj_3_92(); break;
            case 92: jj_3_93(); break;
            case 93: jj_3_94(); break;
            case 94: jj_3_95(); break;
            case 95: jj_3_96(); break;
            case 96: jj_3_97(); break;
            case 97: jj_3_98(); break;
            case 98: jj_3_99(); break;
            case 99: jj_3_100(); break;
            case 100: jj_3_101(); break;
            case 101: jj_3_102(); break;
            case 102: jj_3_103(); break;
            case 103: jj_3_104(); break;
            case 104: jj_3_105(); break;
            case 105: jj_3_106(); break;
            case 106: jj_3_107(); break;
            case 107: jj_3_108(); break;
            case 108: jj_3_109(); break;
            case 109: jj_3_110(); break;
            case 110: jj_3_111(); break;
            case 111: jj_3_112(); break;
            case 112: jj_3_113(); break;
            case 113: jj_3_114(); break;
            case 114: jj_3_115(); break;
          }
        }
        p = p.next;
      } while (p != null);
      } catch(LookaheadSuccess ls) { }
    }
    jj_rescan = false;
  }

  private void jj_save(int index, int xla) {
    JJCalls p = jj_2_rtns[index];
    while (p.gen > jj_gen) {
      if (p.next == null) { p = p.next = new JJCalls(); break; }
      p = p.next;
    }
    p.gen = jj_gen + xla - jj_la; p.first = token; p.arg = xla;
  }

  static final class JJCalls {
    int gen;
    Token first;
    int arg;
    JJCalls next;
  }

}