/* Copyright (c) 2001-2023, The HSQL Development Group
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * Neither the name of the HSQL Development Group nor the names of its
 * contributors may be used to endorse or promote products derived from this
 * software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL HSQL DEVELOPMENT GROUP, HSQLDB.ORG,
 * OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package org.hsqldb.jdbc;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.BatchUpdateException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Calendar;
import java.util.UUID;

import org.hsqldb.HsqlException;
import org.hsqldb.SchemaObject;
import org.hsqldb.SessionInterface;
import org.hsqldb.StatementTypes;
import org.hsqldb.error.ErrorCode;
import org.hsqldb.lib.CharArrayWriter;
import org.hsqldb.lib.CountdownInputStream;
import org.hsqldb.lib.HsqlByteArrayOutputStream;
import org.hsqldb.lib.StringConverter;
import org.hsqldb.navigator.RowSetNavigator;
import org.hsqldb.result.Result;
import org.hsqldb.result.ResultConstants;
import org.hsqldb.result.ResultLob;
import org.hsqldb.result.ResultMetaData;
import org.hsqldb.result.ResultProperties;
import org.hsqldb.types.BinaryData;
import org.hsqldb.types.BinaryUUIDType;
import org.hsqldb.types.BlobDataID;
import org.hsqldb.types.BlobInputStream;
import org.hsqldb.types.ClobDataID;
import org.hsqldb.types.ClobInputStream;
import org.hsqldb.types.DateTimeType;
import org.hsqldb.types.JavaObjectData;
import org.hsqldb.types.JavaObjectDataInternal;
import org.hsqldb.types.Type;
import org.hsqldb.types.Types;

//#ifdef JAVA8
import java.sql.JDBCType;
import java.sql.SQLType;

//#endif JAVA8

/* $Id$ */

// changes by fredt
// SimpleDateFormat objects moved out of methods to improve performance
// this is safe because only one thread at a time should access a
// PreparedStatement object until it has finished executing the statement
//
// fredt@users    20020215 - patch 517028 by peterhudson@users - method defined
// minor changes by fredt
// fredt@users    20020320 - patch 1.7.0 - JDBC 2 support and error trapping;
//                           JDBC 2 methods can now be called from jdk 1.1.x
//                           - see javadoc comments
// fredt@users    20020414 - patch 517028 by peterhudson@users - setDate method defined
//                                                             - setTime method defined
//                                                             - setTimestamp method defined
//                           changes by fredt                  - moved conversion to HsqlDateTime
// fredt@users    20020429 - patch 1.7.0 - setCharacterStream method defined
//
// boucherb &     20020409 - extensive review and update of docs and behaviour
// fredt@users  - 20020505   to comply with previous and latest java.sql specification
//
// campbell-burnet@users 20020509 - added "throws SQLException" to all methods where it
//                           was missing here but specified in the java.sql.PreparedStatement and
//                           java.sqlCallableStatement interfaces, updated generic documentation to
//                           JDK 1.4, and added JDBC3 methods and docs
// fredt@users    20020627 - patch 574234 for setCharacterStream by ohioedge@users
// fredt@users    20030620 - patch 1.7.2 - rewritten to support real prepared statements
// campbell-burnet@users 20030801 - patch 1.7.2 - support for batch execution
// campbell-burnet@users 20030801 - patch 1.7.2 - support for getMetaData and getParameterMetadata
// campbell-burnet@users 20030801 - patch 1.7.2 - updated some setXXX methods, incl. setCharacterStream
// campbell-burnet@users 20030801 - patch 1.7.2 - setBlob method implemented
// campbell-burnet@users 200403/4 - doc 1.7.2   - javadoc updates toward 1.7.2 final
// campbell-burnet@users 200403/4 - patch 1.7.2 - eliminate eager buffer allocation from setXXXStream/Blob/Clob
// campbell-burnet@users 20051207 - patch 1.8.0.x initial JDBC 4.0 support work
// fredt@users    20060215 - patch 1.8.0 - check for unset parameters
// fredt@users    20061008 - patch 1.9.0 - partial rewrite with enhancements - separated from jdbcStatement
// campbell-burnet@users 20060424 - patch 1.8.x - Mustang Build 81 JDBC 4.0 support
// campbell-burnet@users 20060424 - doc   1.9.0 - Full synch up to Mustang Build 84
// Revision 1.19  2006/07/12 12:24:17  boucherb
// patch 1.9.0
// - full synch up to Mustang b90

/**
 * <!-- start generic documentation -->
 *
 * An object that represents a precompiled SQL statement.
 * <P>A SQL statement is precompiled and stored in a
 * <code>PreparedStatement</code> object. This object can then be used to
 * efficiently execute this statement multiple times.
 *
 * <P><B>Note:</B> The setter methods (<code>setShort</code>, <code>setString</code>,
 * and so on) for setting IN parameter values
 * must specify types that are compatible with the defined SQL type of
 * the input parameter. For instance, if the IN parameter has SQL type
 * <code>INTEGER</code>, then the method <code>setInt</code> should be used.
 *
 * <p>If arbitrary parameter type conversions are required, the method
 * <code>setObject</code> should be used with a target SQL type.
 * <P>
 * In the following example of setting a parameter, <code>con</code> represents
 * an active connection:
 * <PRE>
 *   PreparedStatement pstmt = con.prepareStatement("UPDATE EMPLOYEES
 *                                     SET SALARY = ? WHERE ID = ?");
 *   pstmt.setBigDecimal(1, 153833.00)
 *   pstmt.setInt(2, 110592)
 * </PRE>
 *
 * <!-- end generic documentation -->
 *
 * <!-- start Release-specific documentation -->
 * <div class="ReleaseSpecificDocumentation">
 * <h1>HSQLDB-Specific Information:</h1> <p>
 *
 * From version 2.0, the implementation meets the JDBC specification
 * requirement that any existing ResultSet is closed when execute() or
 * executeQuery() methods are called. The connection property close_result=true
 * is required for this behaviour.
 * <p>
 * JDBCPreparedStatement objects are backed by
 * a true compiled parametric representation. Hence, there are now significant
 * performance gains to be had by using a JDBCPreparedStatement object in
 * preference to a JDBCStatement object when a short-running SQL statement is
 * to be executed more than once. <p>
 *
 * When it can be otherwise avoided, it should be considered poor practice to
 * fully prepare (construct), parameterize, execute, fetch and close a
 * JDBCParameterMetaData object for each execution cycle. Indeed,
 * because the prepare and execute phases
 * both represent a round-trip to the engine, this practice is likely to be
 * noticeably <em>less</em> performant for short-running statements (and
 * possibly even orders of magnitude less performant over network connections
 * for short-running statements) than the equivalent process using JDBCStatement
 * objects, albeit far more convenient, less error prone and certainly much
 * less resource-intensive, especially when large binary and character values
 * are involved, due to the optimized parameterization facility. <p>
 *
 * Instead, when developing an application that is not totally oriented toward
 * the execution of ad hoc SQL, it is recommended to expend some effort toward
 * identifying the SQL statements that are good candidates for regular reuse and
 * adapting the structure of the application accordingly. Often, this is done
 * by recording the text of candidate SQL statements in an application resource
 * object (which has the nice side-benefit of isolating and hiding differences
 * in SQL dialects across different drivers) and caching for possible reuse the
 * PreparedStatement objects derived from the recorded text. <p>
 *
 * Starting with 2.0, when built under a JDBC 4 environment, statement caching
 * can be transparently enabled or disabled on a statement-by-statement basis by
 * invoking setPoolable(true | false), respectively, upon Statement objects of
 * interest. <p>
 *
 * <b>Multi thread use:</b> <p>
 *
 * A PreparedStatement object is stateful and should not normally be shared
 * by multiple threads. If it has to be shared, the calls to set the
 * parameters, calls to add batch statements, the execute call and any
 * post-execute calls should be made within a block synchronized on the
 * PreparedStatement Object.<p>
 *
 * (fredt@users)<br>
 * (campbell-burnet@users)<p>
 *
 * </div>
 * <!-- end release-specific documentation -->
 *
 * @author Campbell Burnet (campbell-burnet@users dot sourceforge.net)
 * @author Fred Toussi (fredt@users dot sourceforge.net)
 * @version 2.7.2
 * @since 1.9.0
 * @see JDBCConnection#prepareStatement
 * @see JDBCResultSet
 */
public class JDBCPreparedStatement extends JDBCStatementBase
implements PreparedStatement {

    /**
     * <!-- start generic documentation -->
     * Executes the SQL query in this <code>PreparedStatement</code> object
     * and returns the <code>ResultSet</code> object generated by the query.
     * <!-- end generic documentation -->
     *
     * @return a <code>ResultSet</code> object that contains the data produced by the
     *         query; never <code>null</code>
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed  <code>PreparedStatement</code> or the SQL
     *            statement does not return a <code>ResultSet</code> object
     */
    public synchronized ResultSet executeQuery() throws SQLException {

        if (statementRetType != StatementTypes.RETURN_RESULT) {
            checkStatementType(StatementTypes.RETURN_RESULT);
        }

        fetchResult();

        return getResultSet();
    }

    /**
     * <!-- start generic documentation -->
     * Executes the SQL statement in this <code>PreparedStatement</code> object,
     * (JDBC4 clarification:)
     * which must be an SQL Data Manipulation Language (DML) statement, such as <code>INSERT</code>, <code>UPDATE</code> or
     * <code>DELETE</code>; or an SQL statement that returns nothing,
     * such as a DDL statement.
     * <!-- end generic documentation -->
     *
     * @return (JDBC4 clarification:) either (1) the row count for SQL Data Manipulation Language (DML) statements
     *         or (2) 0 for SQL statements that return nothing
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed  <code>PreparedStatement</code>
     * or the SQL
     *            statement returns a <code>ResultSet</code> object
     */
    public synchronized int executeUpdate() throws SQLException {

        if (statementRetType != StatementTypes.RETURN_COUNT) {
            checkStatementType(StatementTypes.RETURN_COUNT);
        }

        fetchResult();

        return resultIn.getUpdateCount();
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to SQL <code>NULL</code>.
     *
     * <P><B>Note:</B> You must specify the parameter's SQL type.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB currently ignores the sqlType argument.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param sqlType the SQL type code defined in <code>java.sql.Types</code>
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception SQLFeatureNotSupportedException if <code>sqlType</code> is
     * a <code>ARRAY</code>, <code>BLOB</code>, <code>CLOB</code>,
     * <code>DATALINK</code>, <code>JAVA_OBJECT</code>, <code>NCHAR</code>,
     * <code>NCLOB</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>,
     *  <code>REF</code>, <code>ROWID</code>, <code>SQLXML</code>
     * or  <code>STRUCT</code> data type and the JDBC driver does not support
     * this data type
     */
    public synchronized void setNull(int parameterIndex,
                                     int sqlType) throws SQLException {
        setParameter(parameterIndex, null);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>boolean</code> value.
     * The driver converts this
     * (JDBC4 Modified:)
     * to an SQL <code>BIT</code> or <code>BOOLEAN</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB supports BOOLEAN type for boolean values. This method can also
     * be used to set the value of a parameter of the SQL type BIT(1), which is
     * a bit string consisting of a 0 or 1.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setBoolean(int parameterIndex,
                                        boolean x) throws SQLException {

        Boolean b = x ? Boolean.TRUE
                      : Boolean.FALSE;

        setParameter(parameterIndex, b);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>byte</code> value.
     * The driver converts this
     * to an SQL <code>TINYINT</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setByte(int parameterIndex,
                                     byte x) throws SQLException {
        setIntParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>short</code> value.
     * The driver converts this
     * to an SQL <code>SMALLINT</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setShort(int parameterIndex,
                                      short x) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        setIntParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>int</code> value.
     * The driver converts this
     * to an SQL <code>INTEGER</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setInt(int parameterIndex,
                                    int x) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        setIntParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>long</code> value.
     * The driver converts this
     * to an SQL <code>BIGINT</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setLong(int parameterIndex,
                                     long x) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        setLongParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>float</code> value.
     * The driver converts this
     * (JDBC4 correction:)
     * to an SQL <code>REAL</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.1, HSQLDB handles Java positive/negative Infinity
     * and NaN <code>float</code> values consistent with the Java Language
     * Specification; these <em>special</em> values are now correctly stored
     * to and retrieved from the database.
     * </div>
     * <!-- start release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setFloat(int parameterIndex,
                                      float x) throws SQLException {
        setDouble(parameterIndex, (double) x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>double</code> value.
     * The driver converts this
     * to an SQL <code>DOUBLE</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.1, HSQLDB handles Java positive/negative Infinity
     * and NaN <code>double</code> values consistent with the Java Language
     * Specification; these <em>special</em> values are now correctly stored
     * to and retrieved from the database.
     * </div>
     * <!-- start release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setDouble(int parameterIndex,
                                       double x) throws SQLException {

        Double d = Double.valueOf(x);

        setParameter(parameterIndex, d);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.math.BigDecimal</code> value.
     * The driver converts this to an SQL <code>NUMERIC</code> value when
     * it sends it to the database.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setBigDecimal(int parameterIndex,
                                           BigDecimal x) throws SQLException {
        setParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java <code>String</code> value.
     * The driver converts this
     * to an SQL <code>VARCHAR</code> or <code>LONGVARCHAR</code> value
     * (depending on the argument's
     * size relative to the driver's limits on <code>VARCHAR</code> values)
     * when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Including 2.0, HSQLDB represents all XXXCHAR values internally as
     * java.lang.String objects; there is no appreciable difference between
     * CHAR, VARCHAR and LONGVARCHAR.
     * </div>
     * <!-- start release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setString(int parameterIndex,
                                       String x) throws SQLException {
        setParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given Java array of bytes.  The driver converts
     * this to an SQL <code>VARBINARY</code> or <code>LONGVARBINARY</code>
     * (depending on the argument's size relative to the driver's limits on
     * <code>VARBINARY</code> values) when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Including 2.0, HSQLDB represents all XXXBINARY values the same way
     * internally; there is no appreciable difference between BINARY,
     * VARBINARY and LONGVARBINARY as far as JDBC is concerned.
     * </div>
     * <!-- start release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setBytes(int parameterIndex,
                                      byte[] x) throws SQLException {
        setParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * (JDBC4 clarification:)
     * Sets the designated parameter to the given <code>java.sql.Date</code> value
     * using the default time zone of the virtual machine that is running
     * the application.
     * The driver converts this
     * to an SQL <code>DATE</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * When a setXXX method is used to set a parameter of type
     * TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE the time zone of the
     * client application is used as time zone
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setDate(int parameterIndex,
                                     Date x) throws SQLException {
        setDate(parameterIndex, x, null);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Time</code> value.
     * The driver converts this
     * to an SQL <code>TIME</code> value when it sends it to the database.
     * <!-- end generic documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * When a setXXX method is used to set a parameter of type
     * TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE the time zone of the
     * client application is used as time zone
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setTime(int parameterIndex,
                                     Time x) throws SQLException {
        setTime(parameterIndex, x, null);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Timestamp</code> value.
     * The driver
     * converts this to an SQL <code>TIMESTAMP</code> value when it sends it to the
     * database.
     * <!-- end generic documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * When a setXXX method is used to set a parameter of type
     * TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE the time zone of the
     * client application is used as time zone.<p>
     *
     * When this method is used to set a parameter of type TIME or
     * TIME WITH TIME ZONE, then the nanosecond value of the Timestamp object
     * will be used if the TIME parameter accepts fractional seconds.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setTimestamp(int parameterIndex,
                                          Timestamp x) throws SQLException {
        setTimestamp(parameterIndex, x, null);
    }

    /* @todo 1.9.0 - implement streaming */

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given input stream, which will have
     * the specified number of bytes.
     * When a very large ASCII value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code>. Data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from ASCII to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <!-- end generic documentation -->
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * From HSQLDB 2.0 this method uses the US-ASCII character encoding to convert bytes
     * from the stream into the characters of a String.<p>
     * This method does not use streaming to send the data,
     * whether the target is a CLOB or other binary object.<p>
     *
     * For long streams (larger than a few megabytes) with CLOB targets,
     * it is more efficient to use a version of setCharacterStream which takes
     * the a length parameter.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setAsciiStream(int parameterIndex,
            java.io.InputStream x, int length) throws SQLException {
        setAsciiStream(parameterIndex, x, (long) length);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given input stream, which
     * will have the specified number of bytes.
     * (JDBC4 deleted:)
     * [A Unicode character has two bytes, with the first byte being the high
     * byte, and the second being the low byte.] <p>
     *
     * When a very large Unicode value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from Unicode to the database char format.
     *
     * (JDBC4 added:)
     * The byte format of the Unicode stream must be a Java UTF-8, as defined in the
     * Java Virtual Machine Specification.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Starting with 2.0, this method behaves according to the JDBC4
     * specification (the stream is treated as though it has UTF-8 encoding.
     * This method is deprecated: please use setCharacterStream(...) instead.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x a <code>java.io.InputStream</code> object that contains the
     *        Unicode parameter value
     * (JDBC4 deleted:)
     *       [as two-byte Unicode characters]
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception SQLFeatureNotSupportedException if the JDBC driver does not support
     * this method
     * @deprecated
     *      Sun does not include a reason, but presumably
     *      this is because setCharacterStream is now preferred
     */

//#ifdef DEPRECATEDJDBC
    @Deprecated
    public synchronized void setUnicodeStream(int parameterIndex,
            java.io.InputStream x, int length) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        final int ver = JDBCDatabaseMetaData.JDBC_MAJOR;

        if (x == null) {
            throw JDBCUtil.nullArgument("x");
        }

        String       encoding = "UTF8";
        StringWriter writer   = new StringWriter();

        try {
            CountdownInputStream cis    = new CountdownInputStream(x);
            InputStreamReader    reader = new InputStreamReader(cis, encoding);
            char[]               buff   = new char[1024];
            int                  charsRead;

            cis.setCount(length);

            while (-1 != (charsRead = reader.read(buff))) {
                writer.write(buff, 0, charsRead);
            }
        } catch (IOException ex) {
            throw JDBCUtil.sqlException(ErrorCode.SERVER_TRANSFER_CORRUPTED,
                                        ex.toString(), ex);
        }

        setParameter(parameterIndex, writer.toString());
    }

//#endif DEPRECATEDJDBC

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given input stream, which will have
     * the specified number of bytes.
     * When a very large binary value is input to a <code>LONGVARBINARY</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.2, this method works according to the standard.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void setBinaryStream(int parameterIndex,
            java.io.InputStream x, int length) throws SQLException {
        setBinaryStream(parameterIndex, x, (long) length);
    }

    /**
     * <!-- start generic documentation -->
     * Clears the current parameter values immediately.
     * <P>In general, parameter values remain in force for repeated use of a
     * statement. Setting a parameter value automatically clears its
     * previous value.  However, in some cases it is useful to immediately
     * release the resources used by the current parameter values; this can
     * be done by calling the method <code>clearParameters</code>.
     * <!-- end generic documentation -->
     *
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     */
    public synchronized void clearParameters() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        Arrays.fill(parameterValues, null);
        Arrays.fill(parameterSet, false);
        Arrays.fill(streamLengths, 0, streamLengths.length, 0);
    }

    //----------------------------------------------------------------------
    // Advanced features:

    /**
     * <p>Sets the value of the designated parameter with the given object. The second
     * argument must be an object type; for integral values, the
     * <code>java.lang</code> equivalent objects should be used.
     *
     * If the second argument is an <code>InputStream</code> then the stream must contain
     * the number of bytes specified by scaleOrLength.  If the second argument is a
     * <code>Reader</code> then the reader must contain the number of characters specified
     * by scaleOrLength. If these conditions are not true the driver will generate a
     * <code>SQLException</code> when the prepared statement is executed.
     *
     * <p>The given Java object will be converted to the given targetSqlType
     * before being sent to the database.
     *
     * If the object has a custom mapping (is of a class implementing the
     * interface <code>SQLData</code>),
     * the JDBC driver should call the method <code>SQLData.writeSQL</code> to
     * write it to the SQL data stream.
     * If, on the other hand, the object is of a class implementing
     * <code>Ref</code>, <code>Blob</code>, <code>Clob</code>,  <code>NClob</code>,
     *  <code>Struct</code>, <code>java.net.URL</code>,
     * or <code>Array</code>, the driver should pass it to the database as a
     * value of the corresponding SQL type.
     *
     * <p>Note that this method may be used to pass database-specific
     * abstract data types.
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type (as defined in java.sql.Types) to be
     * sent to the database. The scale argument may further qualify this type.
     * @param scaleOrLength for <code>java.sql.Types.DECIMAL</code>
     *          or <code>java.sql.Types.NUMERIC types</code>,
     *          this is the number of digits after the decimal point. For
     *          Java Object types <code>InputStream</code> and <code>Reader</code>,
     *          this is the length
     *          of the data in the stream or reader.  For all other types,
     *          this value will be ignored.
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>PreparedStatement</code> or
     *            if the Java Object specified by x is an InputStream
     *            or Reader object and the value of the scale parameter is less
     *            than zero
     * @exception SQLFeatureNotSupportedException if <code>targetSqlType</code> is
     * a <code>ARRAY</code>, <code>BLOB</code>, <code>CLOB</code>,
     * <code>DATALINK</code>, <code>JAVA_OBJECT</code>, <code>NCHAR</code>,
     * <code>NCLOB</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>,
     *  <code>REF</code>, <code>ROWID</code>, <code>SQLXML</code>
     * or  <code>STRUCT</code> data type and the JDBC driver does not support
     * this data type
     * @see java.sql.Types
     */
    public synchronized void setObject(int parameterIndex, Object x,
                                       int targetSqlType,
                                       int scaleOrLength) throws SQLException {

        if (x instanceof InputStream) {
            setBinaryStream(parameterIndex, (InputStream) x, scaleOrLength);
        } else if (x instanceof Reader) {
            setCharacterStream(parameterIndex, (Reader) x, scaleOrLength);
        } else {
            setObject(parameterIndex, x);
        }
    }

    /**
     * <!-- start generic documentation -->
     * Sets the value of the designated parameter with the given object.
     * This method is like the method <code>setObject</code>
     * above, except that it assumes a scale of zero.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.2, this method supports conversions listed in the
     * conversion table B-5 of the JDBC 3 specification.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type (as defined in java.sql.Types) to be
     *                      sent to the database
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception SQLFeatureNotSupportedException if <code>targetSqlType</code> is
     * a <code>ARRAY</code>, <code>BLOB</code>, <code>CLOB</code>,
     * <code>DATALINK</code>, <code>JAVA_OBJECT</code>, <code>NCHAR</code>,
     * <code>NCLOB</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>,
     *  <code>REF</code>, <code>ROWID</code>, <code>SQLXML</code>
     * or  <code>STRUCT</code> data type and the JDBC driver does not support
     * this data type
     * @see #setObject(int,Object)
     */
    public synchronized void setObject(int parameterIndex, Object x,
                                       int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * <p>Sets the value of the designated parameter using the given object.
     * The second parameter must be of type <code>Object</code>; therefore, the
     * <code>java.lang</code> equivalent objects should be used for built-in types.
     *
     * <p>The JDBC specification specifies a standard mapping from
     * Java <code>Object</code> types to SQL types.  The given argument
     * will be converted to the corresponding SQL type before being
     * sent to the database.
     *
     * <p>Note that this method may be used to pass database-
     * specific abstract data types, by using a driver-specific Java
     * type.
     *
     * If the object is of a class implementing the interface <code>SQLData</code>,
     * the JDBC driver should call the method <code>SQLData.writeSQL</code>
     * to write it to the SQL data stream.
     * If, on the other hand, the object is of a class implementing
     * <code>Ref</code>, <code>Blob</code>, <code>Clob</code>, (JDBC4 new:) [ <code>NClob</code> ],
     *  <code>Struct</code>, <code>java.net.URL</code>, (JDBC4 new:) [ <code>RowId</code>, <code>SQLXML</code> ]
     * or <code>Array</code>, the driver should pass it to the database as a
     * value of the corresponding SQL type.
     * <P>
     * <b>Note:</b> Not all databases allow for a non-typed Null to be sent to
     * the backend. For maximum portability, the <code>setNull</code> or the
     * <code>setObject(int parameterIndex, Object x, int sqlType)</code>
     * method should be used
     * instead of <code>setObject(int parameterIndex, Object x)</code>.
     * <p>
     * <b>Note:</b> This method throws an exception if there is an ambiguity, for example, if the
     * object is of a class implementing more than one of the interfaces named above.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1><p>
     *
     * Since 1.7.2, this method supports conversions listed in the conversion
     * table B-5 of the JDBC 3 specification.
     * </div>
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @exception SQLException if a database access error occurs,
     *  this method is called on a closed <code>PreparedStatement</code>
     * or the type of the given object is ambiguous
     */
    public synchronized void setObject(int parameterIndex,
                                       Object x) throws SQLException {
        setParameter(parameterIndex, x);
    }

    /**
     * <!-- start generic documentation -->
     * Executes the SQL statement in this <code>PreparedStatement</code> object,
     * which may be any kind of SQL statement.
     * Some prepared statements return multiple results; the <code>execute</code>
     * method handles these complex statements as well as the simpler
     * form of statements handled by the methods <code>executeQuery</code>
     * and <code>executeUpdate</code>.
     * <P>
     * The <code>execute</code> method returns a <code>boolean</code> to
     * indicate the form of the first result.  You must call either the method
     * <code>getResultSet</code> or <code>getUpdateCount</code>
     * to retrieve the result; you must call <code>getMoreResults</code> to
     * move to any subsequent result(s).
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * If the statement is a call to a PROCEDURE, it may return multiple
     * multiple fetchable results. <p>
     *
     * </div>
     *
     * @return <code>true</code> if the first result is a <code>ResultSet</code>
     *         object; <code>false</code> if the first result is an update
     *         count or there is no result
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>PreparedStatement</code>
     * or an argument is supplied to this method
     * @see JDBCStatement#execute
     * @see JDBCStatement#getResultSet
     * @see JDBCStatement#getUpdateCount
     * @see JDBCStatement#getMoreResults
     *
     */
    public synchronized boolean execute() throws SQLException {

        fetchResult();

        return statementRetType == StatementTypes.RETURN_RESULT;
    }

    //--------------------------JDBC 2.0-----------------------------

    /**
     * <!-- start generic documentation -->
     * Adds a set of parameters to this <code>PreparedStatement</code>
     * object's batch of commands.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.2, this feature is supported.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @see JDBCStatement#addBatch
     * @since JDK 1.2
     */
    public synchronized void addBatch() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        checkParametersSet();

        if (!isBatch) {
            resultOut.setBatchedPreparedExecuteRequest();

            isBatch = true;
        }

        try {
            performPreExecute();
        } catch (HsqlException e) {
            throw JDBCUtil.sqlException(e);
        }

        int      len              = parameterValues.length;
        Object[] batchParamValues = new Object[len];

        System.arraycopy(parameterValues, 0, batchParamValues, 0, len);
        resultOut.addBatchedPreparedExecuteRequest(batchParamValues);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>Reader</code>
     * object, which is the given number of characters long.
     * When a very large UNICODE value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.Reader</code> object. The data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * From HSQLDB 2.0 this method uses streaming to send data
     * when the target is a CLOB.<p>
     * HSQLDB represents CHARACTER and related SQL types as UTF16 Unicode
     * internally, so this method does not perform any conversion.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the <code>java.io.Reader</code> object that contains the
     *        Unicode data
     * @param length the number of characters in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.2
     */
    public synchronized void setCharacterStream(int parameterIndex,
            java.io.Reader reader, int length) throws SQLException {
        setCharacterStream(parameterIndex, reader, (long) length);
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given
     *  <code>REF(&lt;structured-type&gt;)</code> value.
     * The driver converts this to an SQL <code>REF</code> value when it
     * sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Including 2.0 HSQLDB does not support the SQL REF type. Calling this method
     * throws an exception.
     *
     * </div>
     * <!-- end release-specific documentation -->
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x an SQL <code>REF</code> value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.2
     */
    public void setRef(int parameterIndex, Ref x) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Blob</code> object.
     * The driver converts this to an SQL <code>BLOB</code> value when it
     * sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * For parameters of type Blob, setBlob works normally.<p>
     *
     * In addition since 1.7.2, setBlob is supported for BINARY and VARBINARY
     * parameters. In this context, the Blob object is
     * hard-limited to those of length less than or equal to Integer.MAX_VALUE.
     * In practice, soft limits such as available heap and maximum disk usage
     * per file (such as the transaction log) dictate a much smaller maximum
     * length. <p>
     *
     * For BINARY and VARBINARY parameter types setBlob(i,x) is roughly
     * equivalent (null and length handling not shown) to:
     *
     * <pre class="JavaCodeExample">
     * <b>setBinaryStream</b>(i, x.<b>getBinaryStream</b>(), (<span class="JavaKeyWord">int</span>) x.<b>length</b>());
     * </pre></div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x a <code>Blob</code> object that maps an SQL <code>BLOB</code> value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.2
     */
    public synchronized void setBlob(int parameterIndex,
                                     Blob x) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        Type outType = parameterTypes[parameterIndex - 1];

        switch (outType.typeCode) {

            case Types.SQL_BINARY :
            case Types.SQL_VARBINARY :
                setBlobForBinaryParameter(parameterIndex, x);

                return;

            case Types.SQL_BLOB :
                setBlobParameter(parameterIndex, x);
                break;

            default :
                throw JDBCUtil.invalidArgument();
        }
    }

    /**
     * Converts a blob to binary data for non-blob binary parameters.
     */
    private void setBlobForBinaryParameter(int parameterIndex,
                                           Blob x) throws SQLException {

        if (x instanceof JDBCBlob) {
            setParameter(parameterIndex, ((JDBCBlob) x).data());

            return;
        } else if (x == null) {
            setParameter(parameterIndex, null);

            return;
        }

        final long length = x.length();

        if (length > Integer.MAX_VALUE) {
            String msg = "Maximum Blob input octet length exceeded: " + length;    // NOI18N

            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR, msg);
        }

        try {
            java.io.InputStream in = x.getBinaryStream();
            HsqlByteArrayOutputStream out = new HsqlByteArrayOutputStream(in,
                (int) length);

            setParameter(parameterIndex, out.toByteArray());
            out.close();
        } catch (Throwable e) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR,
                                        e.toString(), e);
        }
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Clob</code> object.
     * The driver converts this to an SQL <code>CLOB</code> value when it
     * sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * For parameters of type Clob, setClob works normally.<p>
     *
     * In addition since 1.7.2, setClob is supported for CHARACTER and VARCHAR
     * parameters. In this context, the Clob object is
     * hard-limited to those of length less than or equal to Integer.MAX_VALUE.
     * In practice, soft limits such as available heap and maximum disk usage
     * per file (such as the transaction log) dictate a much smaller maximum
     * length. <p>
     *
     * For CHARACTER and VARCHAR parameter types setClob(i,x) is roughly
     * equivalent (null and length handling not shown) to:
     *
     * <pre class="JavaCodeExample">
     * <b>setCharacterStream</b>(i, x.<b>getCharacterStream</b>(), (<span class="JavaKeyWord">int</span>) x.<b>length</b>());
     * </pre></div>
     * <!-- end release-specific documentation -->
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x a <code>Clob</code> object that maps an SQL <code>CLOB</code> value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.2
     */
    public synchronized void setClob(int parameterIndex,
                                     Clob x) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        Type outType = parameterTypes[parameterIndex - 1];

        switch (outType.typeCode) {

            case Types.SQL_CHAR :
            case Types.SQL_VARCHAR :
                setClobForStringParameter(parameterIndex, x);

                return;

            case Types.SQL_CLOB :
                setClobParameter(parameterIndex, x);

                return;

            default :
                throw JDBCUtil.invalidArgument();
        }
    }

    private void setClobForStringParameter(int parameterIndex,
                                           Clob x) throws SQLException {

        if (x instanceof JDBCClob) {
            setParameter(parameterIndex, ((JDBCClob) x).getData());

            return;
        } else if (x == null) {
            setParameter(parameterIndex, null);

            return;
        }

        final long length = x.length();

        if (length > Integer.MAX_VALUE) {
            String msg = "Max Clob input character length exceeded: " + length;    // NOI18N

            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR, msg);
        }

        try {
            java.io.Reader  reader = x.getCharacterStream();
            CharArrayWriter writer = new CharArrayWriter(reader, (int) length);

            setParameter(parameterIndex, writer.toString());
        } catch (Throwable e) {
            throw JDBCUtil.sqlException(ErrorCode.SERVER_TRANSFER_CORRUPTED,
                                        e.toString(), e);
        }
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Array</code> object.
     * The driver converts this to an SQL <code>ARRAY</code> value when it
     * sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * From version 2.0, HSQLDB supports the SQL ARRAY type.
     *
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x an <code>Array</code> object that maps an SQL <code>ARRAY</code> value
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.2
     */
    public synchronized void setArray(int parameterIndex,
                                      Array x) throws SQLException {

        checkParameterIndex(parameterIndex);

        int  index = parameterIndex - 1;
        Type type  = parameterMetaData.columnTypes[index];

        if (!type.isArrayType()) {
            throw JDBCUtil.sqlException(ErrorCode.X_42561);
        }

        if (x == null) {
            parameterValues[index] = null;
            parameterSet[index]    = true;

            return;
        }

        Object[] data = null;

        if (x instanceof JDBCArray) {
            Type     baseType  = type.collectionBaseType();
            Object[] array     = ((JDBCArray) x).getArrayInternal();
            Type     otherType = ((JDBCArray) x).arrayType;

            data = (Object[]) type.convertToType(session, array, otherType);
        } else {
            Object object = x.getArray();

            if (object instanceof Object[]) {
                Type     baseType = type.collectionBaseType();
                Object[] array    = (Object[]) object;

                data = new Object[array.length];

                for (int i = 0; i < data.length; i++) {
                    data[i] = baseType.convertJavaToSQL(session, array[i]);
                }
            } else {

                // if foreign data is not Object[]
                throw JDBCUtil.notSupported();
            }
        }

        parameterValues[index] = data;
        parameterSet[index]    = true;
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves a <code>ResultSetMetaData</code> object that contains
     * information about the columns of the <code>ResultSet</code> object
     * that will be returned when this <code>PreparedStatement</code> object
     * is executed.
     * <P>
     * Because a <code>PreparedStatement</code> object is pre-compiled, it is
     * possible to know about the <code>ResultSet</code> object that it will
     * return without having to execute it.  Consequently, it is possible
     * to invoke the method <code>getMetaData</code> on a
     * <code>PreparedStatement</code> object rather than waiting to execute
     * it and then invoking the <code>ResultSet.getMetaData</code> method
     * on the <code>ResultSet</code> object that is returned.
     * <P>
     * <B>NOTE:</B> Using this method may be expensive for some drivers due
     * to the lack of underlying DBMS support.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.2, this feature is supported and is <em>inexpensive</em> as
     * it is backed by underlying DBMS support.  If the statement
     * generates an update count, then null is returned.
     * </div>
     * <!-- end release-specific documentation -->
     * @return the description of a <code>ResultSet</code> object's columns or
     *         <code>null</code> if the driver cannot return a
     *         <code>ResultSetMetaData</code> object
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception SQLFeatureNotSupportedException if the JDBC driver does not support
     * this method
     * @since JDK 1.2
     */
    public synchronized ResultSetMetaData getMetaData() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (statementRetType != StatementTypes.RETURN_RESULT) {
            return null;
        }

        if (resultSetMetaData == null) {
            boolean isUpdatable  = ResultProperties.isUpdatable(rsProperties);
            boolean isInsertable = isUpdatable;

            if (isInsertable) {
                for (int i = 0; i < resultMetaData.colIndexes.length; i++) {
                    if (resultMetaData.colIndexes[i] < 0) {
                        isInsertable = false;

                        break;
                    }
                }
            }

            resultSetMetaData = new JDBCResultSetMetaData(resultMetaData,
                    isUpdatable, isInsertable, connection);
        }

        return resultSetMetaData;
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Date</code> value,
     * using the given <code>Calendar</code> object.  The driver uses
     * the <code>Calendar</code> object to construct an SQL <code>DATE</code> value,
     * which the driver then sends to the database.  With
     * a <code>Calendar</code> object, the driver can calculate the date
     * taking into account a custom timezone.  If no
     * <code>Calendar</code> object is specified, the driver uses the default
     * timezone, which is that of the virtual machine running the application.
     * <!-- end generic documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the <code>Calendar</code> object the driver will use
     *            to construct the date
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.2
     */
    public synchronized void setDate(int parameterIndex, Date x,
                                     Calendar cal) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index = parameterIndex - 1;

        if (x == null) {
            parameterValues[index] = null;
            parameterSet[index]    = true;

            return;
        }

        Type outType = parameterTypes[index];
        Object value;

        switch (outType.typeCode) {

            case Types.SQL_DATE :
            case Types.SQL_TIMESTAMP :
            case Types.SQL_TIMESTAMP_WITH_TIME_ZONE :
                value = ((DateTimeType)outType).convertJavaToSQL(session, x, cal);
                break;

            case Types.SQL_CHAR:
            case Types.SQL_VARCHAR:
                value = Type.SQL_DATE.convertJavaToSQL(session, x);
                value = outType.castToType(session, value, Type.SQL_DATE);
                break;

            default :
                throw JDBCUtil.sqlException(ErrorCode.X_42561);
        }

        parameterValues[index] = value;
        parameterSet[index] = true;
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Time</code> value,
     * using the given <code>Calendar</code> object.  The driver uses
     * the <code>Calendar</code> object to construct an SQL <code>TIME</code> value,
     * which the driver then sends to the database.  With
     * a <code>Calendar</code> object, the driver can calculate the time
     * taking into account a custom timezone.  If no
     * <code>Calendar</code> object is specified, the driver uses the default
     * timezone, which is that of the virtual machine running the application.
     * <!-- end generic documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * When a setXXX method is used to set a parameter of type
     * TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE the time zone (including
     * Daylight Saving Time) of the Calendar is used as time zone for the
     * value.<p>
     *
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the <code>Calendar</code> object the driver will use
     *            to construct the time
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.2
     */
    public synchronized void setTime(int parameterIndex, Time x,
                                     Calendar cal) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index = parameterIndex - 1;

        if (x == null) {
            parameterValues[index] = null;
            parameterSet[index]    = true;

            return;
        }

        Type outType = parameterTypes[index];
        Object value;

        switch (outType.typeCode) {

            case Types.SQL_TIME :
            case Types.SQL_TIME_WITH_TIME_ZONE :
            case Types.SQL_TIMESTAMP:
            case Types.SQL_TIMESTAMP_WITH_TIME_ZONE :
                value = ((DateTimeType)outType).convertJavaToSQL(session, x, cal);
                break;

            case Types.SQL_CHAR:
            case Types.SQL_VARCHAR:
                value = Type.SQL_TIME_WITH_TIME_ZONE_MAX.convertJavaToSQL(session, x);
                value = outType.castToType(session, value, Type.SQL_TIME_WITH_TIME_ZONE_MAX);
                break;

            default :
                throw JDBCUtil.sqlException(ErrorCode.X_42561);
        }


        parameterValues[index] = value;
        parameterSet[index] = true;
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.sql.Timestamp</code> value,
     * using the given <code>Calendar</code> object.  The driver uses
     * the <code>Calendar</code> object to construct an SQL <code>TIMESTAMP</code> value,
     * which the driver then sends to the database.  With a
     *  <code>Calendar</code> object, the driver can calculate the timestamp
     * taking into account a custom timezone.  If no
     * <code>Calendar</code> object is specified, the driver uses the default
     * timezone, which is that of the virtual machine running the application.
     * <!-- end generic documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * When a setXXX method is used to set a parameter of type
     * TIMESTAMP WITH TIME ZONE or TIME WITH TIME ZONE the time zone (including
     * Daylight Saving Time) of the Calendar is used as time zone.<p>
     * In this case, if the Calendar argument is null, then the default Calendar
     * for the clients JVM is used as the Calendar<p>
     *
     * When this method is used to set a parameter of type TIME or
     * TIME WITH TIME ZONE, then the nanosecond value of the Timestamp object
     * is used if the TIME parameter accepts fractional seconds.<p>
     *
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @param cal the <code>Calendar</code> object the driver will use
     *            to construct the timestamp
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.2
     */
    public synchronized void setTimestamp(int parameterIndex, Timestamp x,
                                          Calendar cal) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index = parameterIndex - 1;

        if (x == null) {
            parameterValues[index] = null;
            parameterSet[index]    = true;

            return;
        }

        Type outType = parameterTypes[index];
        Object value;

        switch (outType.typeCode) {
            case Types.SQL_TIMESTAMP :
            case Types.SQL_TIMESTAMP_WITH_TIME_ZONE :
            case Types.SQL_TIME :
            case Types.SQL_TIME_WITH_TIME_ZONE :
            case Types.SQL_DATE :
                value = ((DateTimeType)outType).convertJavaToSQL(session, x, cal);
                break;

            case Types.SQL_CHAR:
            case Types.SQL_VARCHAR:
                value = Type.SQL_TIMESTAMP_WITH_TIME_ZONE_MAX.convertJavaToSQL(session, x);
                value = outType.castToType(session, value, Type.SQL_TIMESTAMP_WITH_TIME_ZONE_MAX);
                break;

            default :
                throw JDBCUtil.sqlException(ErrorCode.X_42561);
        }

        parameterValues[index] = value;
        parameterSet[index] = true;
    }

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to SQL <code>NULL</code>.
     * This version of the method <code>setNull</code> should
     * be used for user-defined types and REF type parameters.  Examples
     * of user-defined types include: STRUCT, DISTINCT, JAVA_OBJECT, and
     * named array types.
     *
     * <P><B>Note:</B> To be portable, applications must give the
     * SQL type code and the fully-qualified SQL type name when specifying
     * a NULL user-defined or REF parameter.  In the case of a user-defined type
     * the name is the type name of the parameter itself.  For a REF
     * parameter, the name is the type name of the referenced type.  If
     * a JDBC driver does not need the type code or type name information,
     * it may ignore it.
     *
     * Although it is intended for user-defined and Ref parameters,
     * this method may be used to set a null parameter of any JDBC type.
     * If the parameter does not have a user-defined or REF type, the given
     * typeName is ignored.
     *
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB simply ignores the sqlType and typeName arguments.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param sqlType a value from <code>java.sql.Types</code>
     * @param typeName the fully-qualified name of an SQL user-defined type;
     *  ignored if the parameter is not a user-defined type or REF
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @exception SQLFeatureNotSupportedException if <code>sqlType</code> is
     * a <code>ARRAY</code>, <code>BLOB</code>, <code>CLOB</code>,
     * <code>DATALINK</code>, <code>JAVA_OBJECT</code>, <code>NCHAR</code>,
     * <code>NCLOB</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>,
     *  <code>REF</code>, <code>ROWID</code>, <code>SQLXML</code>
     * or  <code>STRUCT</code> data type and the JDBC driver does not support
     * this data type or if the JDBC driver does not support this method
     * @since JDK 1.2
     */
    public synchronized void setNull(int parameterIndex, int sqlType,
                                     String typeName) throws SQLException {
        setParameter(parameterIndex, null);
    }

    //------------------------- JDBC 2.0 - overridden methods -------------------

    /**
     * <!-- start generic documentation -->
     * Submits a batch of commands to the database for execution and
     * if all commands execute successfully, returns an array of update counts.
     * The <code>int</code> elements of the array that is returned are ordered
     * to correspond to the commands in the batch, which are ordered
     * according to the order in which they were added to the batch.
     * The elements in the array returned by the method <code>executeBatch</code>
     * may be one of the following:
     * <OL>
     * <LI>A number greater than or equal to zero -- indicates that the
     * command was processed successfully and is an update count giving the
     * number of rows in the database that were affected by the command's
     * execution
     * <LI>A value of <code>SUCCESS_NO_INFO</code> -- indicates that the command was
     * processed successfully but that the number of rows affected is
     * unknown
     * <P>
     * If one of the commands in a batch update fails to execute properly,
     * this method throws a <code>BatchUpdateException</code>, and a JDBC
     * driver may or may not continue to process the remaining commands in
     * the batch.  However, the driver's behavior must be consistent with a
     * particular DBMS, either always continuing to process commands or never
     * continuing to process commands.  If the driver continues processing
     * after a failure, the array returned by the method
     * <code>BatchUpdateException.getUpdateCounts</code>
     * will contain as many elements as there are commands in the batch, and
     * at least one of the elements will be the following:
     * <LI>A value of <code>EXECUTE_FAILED</code> -- indicates that the command failed
     * to execute successfully and occurs only if a driver continues to
     * process commands after a command fails
     * </OL>
     * <P>
     * A driver is not required to implement this method.
     * The possible implementations and return values have been modified in
     * the Java 2 SDK, Standard Edition, version 1.3 to
     * accommodate the option of continuing to process commands in a batch
     * update after a <code>BatchUpdateException</code> object has been thrown.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Starting with HSQLDB 1.7.2, this feature is supported. <p>
     *
     * HSQLDB stops execution of commands in a batch when one of the commands
     * results in an exception. The size of the returned array equals the
     * number of commands that were executed successfully.<p>
     *
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return an array of update counts containing one element for each
     * command in the batch.  The elements of the array are ordered according
     * to the order in which commands were added to the batch.
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code> or the
     * driver does not support batch statements. Throws {@link BatchUpdateException}
     * (a subclass of <code>SQLException</code>) if one of the commands sent to the
     * database fails to execute properly or attempts to return a result set.
     *
     *
     * @see #addBatch
     * @see java.sql.DatabaseMetaData#supportsBatchUpdates()
     * @since JDK 1.3
     */
    public synchronized int[] executeBatch() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        checkStatementType(StatementTypes.RETURN_COUNT);

        if (!isBatch) {
            if (connection.isAllowEmptyBatch) {
                return new int[]{};
            }

            throw JDBCUtil.sqlExceptionSQL(ErrorCode.X_07506);
        }

        generatedResult = null;

        int batchCount = resultOut.getNavigator().getSize();

        resultIn = null;

        try {
            resultIn = session.execute(resultOut);
        } catch (HsqlException e) {
            throw JDBCUtil.sqlException(e);
        } finally {
            performPostExecute();
            resultOut.getNavigator().clear();

            isBatch = false;
        }

        if (resultIn.mode == ResultConstants.ERROR) {
            throw JDBCUtil.sqlException(resultIn);
        }

        RowSetNavigator navigator    = resultIn.getNavigator();
        int[]           updateCounts = new int[navigator.getSize()];

        for (int i = 0; navigator.next(); i++) {
            Object[] data = navigator.getCurrent();

            updateCounts[i] = ((Integer) data[0]).intValue();
        }

        if (updateCounts.length != batchCount) {
            if (errorResult == null) {
                throw new BatchUpdateException(updateCounts);
            } else {
                throw new BatchUpdateException(errorResult.getMainString(),
                                               errorResult.getSubString(),
                                               errorResult.getErrorCode(),
                                               updateCounts);
            }
        }

        return updateCounts;
    }

    /**
     * <!-- start generic documentation -->
     * Sets escape processing on or off.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * As per JDBC spec, calling this method has no effect.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param enable <code>true</code> to enable escape processing;
     *     <code>false</code> to disable it
     * @exception SQLException if a database access error occurs
     */
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkClosed();
    }

    /**
     * This method should always throw if called for a PreparedStatement or
     * CallableStatement.
     *
     * @param sql ignored
     * @throws SQLException always
     */
    public void addBatch(String sql) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * This method should always throw if called for a PreparedStatement or
     * CallableStatement.
     *
     * @param sql ignored
     * @throws SQLException always
     * @return nothing
     */
    public synchronized ResultSet executeQuery(String sql)
    throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * This method should always throw if called for a PreparedStatement or
     * CallableStatement.
     *
     * @param sql ignored
     * @throws SQLException always
     * @return nothing
     */
    public boolean execute(String sql) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * This method should always throw if called for a PreparedStatement or
     * CallableStatement.
     *
     * @param sql ignored
     * @throws SQLException always
     * @return nothing
     */
    public int executeUpdate(String sql) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * Does the specialized work required to free this object's resources and
     * that of its parent class. <p>
     *
     * @throws SQLException if a database access error occurs
     */
    public synchronized void close() throws SQLException {

        if (isClosed()) {
            return;
        }

        closeResultData();

        HsqlException he = null;

        try {

            // fredt - if this is called by Connection.close() then there's no
            // need to free the prepared statements on the server - it is done
            // by Connection.close()
            if (!connection.isClosed) {
                session.execute(Result.newFreeStmtRequest(statementID));
            }
        } catch (HsqlException e) {
            he = e;
        }

        parameterValues   = null;
        parameterSet      = null;
        parameterTypes    = null;
        parameterModes    = null;
        resultMetaData    = null;
        parameterMetaData = null;
        resultSetMetaData = null;
        pmd               = null;
        connection        = null;
        session           = null;
        resultIn          = null;
        resultOut         = null;
        isClosed          = true;

        if (he != null) {
            throw JDBCUtil.sqlException(he);
        }
    }

    /**
     * Retrieves a String representation of this object.  <p>
     *
     * The representation is of the form: <p>
     *
     * class-name@hash[sql=[char-sequence], parameters=[p1, ...pi, ...pn]] <p>
     *
     * p1, ...pi, ...pn are the String representations of the currently set
     * parameter values that will be used with the non-batch execution
     * methods. <p>
     *
     * @return a String representation of this object
     */
    public synchronized String toString() {

        StringBuilder sb = new StringBuilder();
        String        sql;
        Object[]      pv;

        sb.append(super.toString());

        sql = this.sql;
        pv  = parameterValues;

        if (sql == null || pv == null) {
            sb.append("[closed]");

            return sb.toString();
        }

        sb.append("[sql=[").append(sql).append("]");

        if (pv.length > 0) {
            sb.append(", parameters=[");

            for (int i = 0; i < pv.length; i++) {
                sb.append('[');
                sb.append(pv[i]);
                sb.append("], ");
            }

            sb.setLength(sb.length() - 2);
            sb.append(']');
        }

        sb.append(']');

        return sb.toString();
    }

    //------------------------- JDBC 3.0 -----------------------------------

    /**
     * <!-- start generic documentation -->
     * Sets the designated parameter to the given <code>java.net.URL</code> value.
     * The driver converts this to an SQL <code>DATALINK</code> value
     * when it sends it to the database.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Including 2.0, HSQLDB does not support the DATALINK SQL type for which this
     * method is intended. Calling this method throws an exception.
     *
     * </div>
     * <!-- end release-specific documentation -->
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the <code>java.net.URL</code> object to be set
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.4, HSQL 1.7.0
     */
    public void setURL(int parameterIndex,
                       java.net.URL x) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the number, types and properties of this
     * <code>PreparedStatement</code> object's parameters.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Since 1.7.2, this feature is supported.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return a <code>ParameterMetaData</code> object that contains information
     *         about the number, types and properties for each
     *  parameter marker of this <code>PreparedStatement</code> object
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @see java.sql.ParameterMetaData
     * @since JDK 1.4, HSQL 1.7.0
     */
    public synchronized ParameterMetaData getParameterMetaData()
    throws SQLException {

        checkClosed();

        if (pmd == null) {
            pmd = new JDBCParameterMetaData(connection, parameterMetaData);
        }

        return pmd;
    }

    /**
     * Statement methods that must be overridden in this class and throw
     * an exception.
     */
    public int executeUpdate(String sql,
                             int autoGeneratedKeys) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    public boolean execute(String sql,
                           int autoGeneratedKeys) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    public int executeUpdate(String sql,
                             int[] columnIndexes) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    public boolean execute(String sql,
                           int[] columnIndexes) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    public int executeUpdate(String sql,
                             String[] columnNames) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    public boolean execute(String sql,
                           String[] columnNames) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * <!-- start generic documentation -->
     * Moves to this <code>Statement</code> object's next result, deals with
     * any current <code>ResultSet</code> object(s) according  to the instructions
     * specified by the given flag, and returns
     * <code>true</code> if the next result is a <code>ResultSet</code> object.
     *
     * <P>There are no more results when the following is true:
     * <PRE>{@code
     *     // stmt is a Statement object
     *     ((stmt.getMoreResults(current) == false) && (stmt.getUpdateCount() == -1))
     * }</PRE>
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB supports this feature. <p>
     *
     * This is used with CallableStatement objects that return multiple
     * ResultSet objects.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param current one of the following <code>Statement</code>
     *        constants indicating what should happen to current
     *        <code>ResultSet</code> objects obtained using the method
     *        <code>getResultSet</code>:
     *        <code>Statement.CLOSE_CURRENT_RESULT</code>,
     *        <code>Statement.KEEP_CURRENT_RESULT</code>, or
     *        <code>Statement.CLOSE_ALL_RESULTS</code>
     * @return <code>true</code> if the next result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are no
     *         more results
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code> or the argument
     *             supplied is not one of the following:
     *        <code>Statement.CLOSE_CURRENT_RESULT</code>,
     *        <code>Statement.KEEP_CURRENT_RESULT</code>, or
     *        <code>Statement.CLOSE_ALL_RESULTS</code>
     * @since JDK 1.4, HSQLDB 1.7
     * @see #execute
     */
    public synchronized boolean getMoreResults(int current)
    throws SQLException {
        return super.getMoreResults(current);
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves any auto-generated keys created as a result of executing this
     * <code>Statement</code> object. If this <code>Statement</code> object did
     * not generate any keys, an empty <code>ResultSet</code>
     * object is returned.
     * <p>(JDBC4 clarification:)
     * <p><B>Note:</B>If the columns which represent the auto-generated keys were not specified,
     * the JDBC driver implementation will determine the columns which best represent the auto-generated keys.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Starting with version 2.0, HSQLDB supports this feature with single-row and
     * multi-row insert, update and merge statements. <p>
     *
     * This method returns a result set only if
     * the executeUpdate methods that was used is one of the three methods that
     * have the extra parameter indicating return of generated keys<p>
     *
     * If the executeUpdate method did not specify the columns which represent
     * the auto-generated keys the IDENTITY column or GENERATED column(s) of the
     * table are returned.<p>
     *
     * The executeUpdate methods with column indexes or column names return the
     * post-insert or post-update values of the specified columns, whether the
     * columns are generated or not. This allows values that have been modified
     * by execution of triggers to be returned.<p>
     *
     * If column names or indexes provided by the user in the executeUpdate()
     * method calls do not correspond to table columns (incorrect names or
     * indexes larger than the column count), an empty result is returned.
     *
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return a <code>ResultSet</code> object containing the auto-generated key(s)
     *         generated by the execution of this <code>Statement</code> object
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.4, HSQLDB 1.7
     */
    public synchronized ResultSet getGeneratedKeys() throws SQLException {
        return getGeneratedResultSet();
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the result set holdability for <code>ResultSet</code> objects
     * generated by this <code>Statement</code> object.
     * <!-- end generic documentation -->
     *
     * @return either <code>ResultSet.HOLD_CURSORS_OVER_COMMIT</code> or
     *         <code>ResultSet.CLOSE_CURSORS_AT_COMMIT</code>
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.4, HSQLDB 1.7
     */
    public synchronized int getResultSetHoldability() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return ResultProperties.getJDBCHoldability(rsProperties);
    }

    //------------------------- JDBC 4.0 -----------------------------------

    /**
     * Retrieves whether this <code>Statement</code> object has been closed. A <code>Statement</code> is closed if the
     * method close has been called on it, or if it is automatically closed.
     * @return true if this <code>Statement</code> object is closed; false if it is still open
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized boolean isClosed() {
        return isClosed;
    }

    /**
     * Sets the designated parameter to the given <code>java.sql.RowId</code> object. The
     * driver converts this to a SQL <code>ROWID</code> value when it sends it
     * to the database
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the parameter value
     * @throws SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since JDK 1.6, HSQLDB 2.0
     */
    public void setRowId(int parameterIndex, RowId x) throws SQLException {
        throw JDBCUtil.notSupported();
    }

    /**
     * Sets the designated parameter to the given <code>String</code> object.
     * The driver converts this to a SQL <code>NCHAR</code> or
     * <code>NVARCHAR</code> or <code>LONGNVARCHAR</code> value
     * (depending on the argument's
     * size relative to the driver's limits on <code>NVARCHAR</code> values)
     * when it sends it to the database.
     *
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @throws SQLException if the driver does not support national
     *         character sets;  if the driver can detect that a data conversion
     *  error could occur ; if a database access error occurs; or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setNString(int parameterIndex,
                                        String value) throws SQLException {
        setString(parameterIndex, value);
    }

    /**
     * Sets the designated parameter to a <code>Reader</code> object. The
     * <code>Reader</code> reads the data till end-of-file is reached. The
     * driver does the necessary conversion from Java character format to
     * the national character set in the database.
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @param length the number of characters in the parameter data.
     * @throws SQLException if the driver does not support national
     *         character sets;  if the driver can detect that a data conversion
     *  error could occur ; if a database access error occurs; or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setNCharacterStream(int parameterIndex,
            Reader value, long length) throws SQLException {
        setCharacterStream(parameterIndex, value, length);
    }

    /**
     * Sets the designated parameter to a <code>java.sql.NClob</code> object. The driver converts this to a
     * SQL <code>NCLOB</code> value when it sends it to the database.
     * @param parameterIndex of the first parameter is 1, the second is 2, ...
     * @param value the parameter value
     * @throws SQLException if the driver does not support national
     *         character sets;  if the driver can detect that a data conversion
     *  error could occur ; if a database access error occurs; or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setNClob(int parameterIndex,
                                      NClob value) throws SQLException {
        setClob(parameterIndex, value);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to a <code>Reader</code> object.  The reader must contain  the number
     * of characters specified by length otherwise a <code>SQLException</code> will be
     * generated when the <code>PreparedStatement</code> is executed.
     * This method differs from the <code>setCharacterStream (int, Reader, int)</code> method
     * because it informs the driver that the parameter value should be sent to
     * the server as a <code>CLOB</code>.  When the <code>setCharacterStream</code> method is used, the
     * driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGVARCHAR</code> or a <code>CLOB</code>
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @param length the number of characters in the parameter data.
     * @throws SQLException if a database access error occurs, this method is called on
     * a closed <code>PreparedStatement</code>, if parameterIndex does not correspond to a parameter
     * marker in the SQL statement, or if the length specified is less than zero.
     *
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setClob(int parameterIndex, Reader reader,
                                     long length) throws SQLException {
        setCharacterStream(parameterIndex, reader, length);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to a <code>InputStream</code> object.  The input stream must contain  the number
     * of characters specified by length otherwise a <code>SQLException</code> will be
     * generated when the <code>PreparedStatement</code> is executed.
     * This method differs from the <code>setBinaryStream (int, InputStream, int)</code>
     * method because it informs the driver that the parameter value should be
     * sent to the server as a <code>BLOB</code>.  When the <code>setBinaryStream</code> method is used,
     * the driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGVARBINARY</code> or a <code>BLOB</code>
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * In HSQLDB 2.0, this method uses streaming to send the data when the
     * stream is assigned to a BLOB target. For other binary targets the
     * stream is read on the client side and a byte array is sent.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex index of the first parameter is 1,
     * the second is 2, ...
     * @param inputStream An object that contains the data to set the parameter
     * value to.
     * @param length the number of bytes in the parameter data.
     * @throws SQLException if a database access error occurs,
     * this method is called on a closed <code>PreparedStatement</code>,
     * if parameterIndex does not correspond
     * to a parameter marker in the SQL statement,  if the length specified
     * is less than zero or if the number of bytes in the input stream does not match
     * the specified length.
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setBlob(int parameterIndex,
                                     InputStream inputStream,
                                     long length) throws SQLException {
        setBinaryStream(parameterIndex, inputStream, length);
    }

    /**
     * Sets the designated parameter to a <code>Reader</code> object.  The reader must contain  the number
     * of characters specified by length otherwise a <code>SQLException</code> will be
     * generated when the <code>PreparedStatement</code> is executed.
     * This method differs from the <code>setCharacterStream (int, Reader, int)</code> method
     * because it informs the driver that the parameter value should be sent to
     * the server as a <code>NCLOB</code>.  When the <code>setCharacterStream</code> method is used, the
     * driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGNVARCHAR</code> or a <code>NCLOB</code>
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @param length the number of characters in the parameter data.
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if the length specified is less than zero;
     * if the driver does not support national character sets;
     * if the driver can detect that a data conversion
     *  error could occur;  if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setNClob(int parameterIndex, Reader reader,
                                      long length) throws SQLException {
        setClob(parameterIndex, reader, length);
    }

    /**
     * Sets the designated parameter to the given <code>java.sql.SQLXML</code> object.
     * The driver converts this to an
     * SQL <code>XML</code> value when it sends it to the database.
     * <p>
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param xmlObject a <code>SQLXML</code> object that maps an SQL <code>XML</code> value
     * @throws SQLException if a database access error occurs,
     *  this method is called on a closed <code>PreparedStatement</code>
     * or the <code>java.xml.transform.Result</code>,
     *  <code>Writer</code> or <code>OutputStream</code> has not been closed for
     * the <code>SQLXML</code> object
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since JDK 1.6, HSQLDB 2.0
     */
    public void setSQLXML(int parameterIndex,
                          SQLXML xmlObject) throws SQLException {
        throw JDBCUtil.notSupported();
    }

// --------------------------- Added: Mustang Build 86 -------------------------

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to the given input stream, which will have
     * the specified number of bytes.
     * When a very large ASCII value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code>. Data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from ASCII to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * From HSQLDB 2.0 this method uses the US-ASCII character encoding to convert bytes
     * from the stream into the characters of a String.<p>
     * This method does not use streaming to send the data,
     * whether the target is a CLOB or other binary object.<p>
     *
     * For long streams (larger than a few megabytes) with CLOB targets,
     * it is more efficient to use a version of setCharacterStream which takes
     * the a length parameter.
     * </div>
     * <!-- end release-specific documentation -->
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setAsciiStream(int parameterIndex,
            java.io.InputStream x, long length) throws SQLException {

        if (length < 0) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INVALID_ARGUMENT,
                                        "length: " + length);
        }

        setAscStream(parameterIndex, x, length);
    }

    void setAscStream(int parameterIndex, java.io.InputStream x,
                      long length) throws SQLException {

        if (length > Integer.MAX_VALUE) {
            throw JDBCUtil.sqlException(ErrorCode.X_22001);
        }

        if (x == null) {
            throw JDBCUtil.nullArgument("x");
        }

        try {
            String s = StringConverter.inputStreamToString(x, "US-ASCII");

            if (length >= 0 && s.length() > length) {
                s = s.substring(0, (int) length);
            }

            setParameter(parameterIndex, s);
        } catch (IOException e) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR,
                                        null, e);
        }
    }

    /**
     * Sets the designated parameter to the given input stream, which will have
     * the specified number of bytes.
     * When a very large binary value is input to a <code>LONGVARBINARY</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * This method uses streaming to send the data when the
     * stream is assigned to a BLOB target. For other binary targets the
     * stream is read on the client side and a byte array is sent.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @param length the number of bytes in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setBinaryStream(int parameterIndex,
            java.io.InputStream x, long length) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (length < 0) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INVALID_ARGUMENT,
                                        "length: " + length);
        }

        if (length > parameterTypes[parameterIndex - 1].precision) {
            throw JDBCUtil.sqlException(ErrorCode.X_22001,
                                        "length: " + length);
        }

        // ignore length as client/server cannot handle incorrect data length entered by user
        setBinStream(parameterIndex, x, -1);
    }

    private void setBinStream(int parameterIndex, java.io.InputStream x,
                              long length) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (parameterTypes[parameterIndex - 1].typeCode == Types.SQL_BLOB) {
            setBlobParameter(parameterIndex, x, length);

            return;
        }

        if (length > Integer.MAX_VALUE) {
            String msg = "Maximum Blob input length exceeded: " + length;

            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR, msg);
        }

        try {
            HsqlByteArrayOutputStream output;

            if (length < 0) {
                output = new HsqlByteArrayOutputStream(x);
            } else {
                output = new HsqlByteArrayOutputStream(x, (int) length);
            }

            setParameter(parameterIndex, output.toByteArray());
        } catch (Throwable e) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR,
                                        e.toString(), e);
        }
    }

    /**
     * Sets the designated parameter to the given <code>Reader</code>
     * object, which is the given number of characters long.
     * When a very large UNICODE value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.Reader</code> object. The data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * This method uses streaming to send data
     * when the target is a CLOB.<p>
     * </div>
     * <!-- end release-specific documentation -->
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the <code>java.io.Reader</code> object that contains the
     *        Unicode data
     * @param length the number of characters in the stream
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setCharacterStream(int parameterIndex,
            java.io.Reader reader, long length) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (length < 0) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INVALID_ARGUMENT,
                                        "length: " + length);
        }

        if (length > parameterTypes[parameterIndex - 1].precision) {
            throw JDBCUtil.sqlException(ErrorCode.X_22001,
                                        "length: " + length);
        }

        // ignore length as client/server cannot handle incorrect data length entered by user
        setCharStream(parameterIndex, reader, -1);
    }

    private void setCharStream(int parameterIndex, java.io.Reader reader,
                               long length) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        if (parameterTypes[parameterIndex - 1].typeCode == Types.SQL_CLOB) {
            setClobParameter(parameterIndex, reader, length);

            return;
        }

        if (length > Integer.MAX_VALUE) {
            String msg = "Maximum Clob input length exceeded: " + length;

            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR, msg);
        }

        try {
            CharArrayWriter writer;

            if (length < 0) {
                writer = new CharArrayWriter(reader);
            } else {
                writer = new CharArrayWriter(reader, (int) length);
            }

            setParameter(parameterIndex, writer.toString());
        } catch (Throwable e) {
            throw JDBCUtil.sqlException(ErrorCode.JDBC_INPUTSTREAM_ERROR,
                                        e.toString(), e);
        }
    }

    /**
     * Sets the designated parameter to the given input stream.
     * When a very large ASCII value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code>. Data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from ASCII to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setAsciiStream</code> which takes a length parameter.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * In HSQLDB 2.0, this method does not use streaming to send the data,
     * whether the target is a CLOB or other binary object.
     *
     * For long streams (larger than a few megabytes), it is more efficient to
     * use a version of setCharacterStream which takes the a length parameter.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the Java input stream that contains the ASCII parameter value
     * @exception SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *   @since 1.6
     */
    public synchronized void setAsciiStream(int parameterIndex,
            java.io.InputStream x) throws SQLException {
        setAscStream(parameterIndex, x, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to the given input stream.
     * When a very large binary value is input to a <code>LONGVARBINARY</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.InputStream</code> object. The data will be read from the
     * stream as needed until end-of-file is reached.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setBinaryStream</code> which takes a length parameter.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * This method does not use streaming to send the data,
     * whether the target is a CLOB or other binary object.<p>
     *
     * For long streams (larger than a few megabytes) with CLOB targets,
     * it is more efficient to use a version of setCharacterStream which takes
     * the a length parameter.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the java input stream which contains the binary parameter value
     * @exception SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since 1.6
     */
    public synchronized void setBinaryStream(int parameterIndex,
            java.io.InputStream x) throws SQLException {
        setBinStream(parameterIndex, x, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to the given <code>Reader</code>
     * object.
     * When a very large UNICODE value is input to a <code>LONGVARCHAR</code>
     * parameter, it may be more practical to send it via a
     * <code>java.io.Reader</code> object. The data will be read from the stream
     * as needed until end-of-file is reached.  The JDBC driver will
     * do any necessary conversion from UNICODE to the database char format.
     *
     * <P><B>Note:</B> This stream object can either be a standard
     * Java stream object or your own subclass that implements the
     * standard interface.
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setCharacterStream</code> which takes a length parameter.
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * In HSQLDB 2.0, this method does not use streaming to send the data,
     * whether the target is a CLOB or other binary object.
     *
     * For long streams (larger than a few megabytes), it is more efficient to
     * use a version of setCharacterStream which takes the a length parameter.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param reader the <code>java.io.Reader</code> object that contains the
     *        Unicode data
     * @exception SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since 1.6
     */
    public synchronized void setCharacterStream(int parameterIndex,
            java.io.Reader reader) throws SQLException {
        setCharStream(parameterIndex, reader, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     *   Sets the designated parameter to a <code>Reader</code> object. The
     *   <code>Reader</code> reads the data till end-of-file is reached. The
     *   driver does the necessary conversion from Java character format to
     *   the national character set in the database.
     *
     *   <P><B>Note:</B> This stream object can either be a standard
     *   Java stream object or your own subclass that implements the
     *   standard interface.
     *   <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     *   it might be more efficient to use a version of
     *   <code>setNCharacterStream</code> which takes a length parameter.
     *
     *   @param parameterIndex of the first parameter is 1, the second is 2, ...
     *   @param value the parameter value
     *   @throws SQLException if parameterIndex does not correspond to a parameter
     *   marker in the SQL statement; if the driver does not support national
     *           character sets;  if the driver can detect that a data conversion
     *    error could occur; if a database access error occurs; or
     *   this method is called on a closed <code>PreparedStatement</code>
     *   @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *   @since 1.6
     */
    public synchronized void setNCharacterStream(int parameterIndex,
            Reader value) throws SQLException {
        setCharStream(parameterIndex, value, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to a <code>Reader</code> object.
     * This method differs from the <code>setCharacterStream (int, Reader)</code> method
     * because it informs the driver that the parameter value should be sent to
     * the server as a <code>CLOB</code>.  When the <code>setCharacterStream</code> method is used, the
     * driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGVARCHAR</code> or a <code>CLOB</code>
     *
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setClob</code> which takes a length parameter.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs; this method is called on
     * a closed <code>PreparedStatement</code>or if parameterIndex does not correspond to a parameter
     * marker in the SQL statement
     *
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     * @since 1.6
     */
    public synchronized void setClob(int parameterIndex,
                                     Reader reader) throws SQLException {
        setCharStream(parameterIndex, reader, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to a <code>InputStream</code> object.
     * This method differs from the <code>setBinaryStream (int, InputStream)</code>
     * method because it informs the driver that the parameter value should be
     * sent to the server as a <code>BLOB</code>.  When the <code>setBinaryStream</code> method is used,
     * the driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGVARBINARY</code> or a <code>BLOB</code>
     *
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setBlob</code> which takes a length parameter.
     *
     * @param parameterIndex index of the first parameter is 1,
     * the second is 2, ...
     * @param inputStream An object that contains the data to set the parameter
     * value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement; if a database access error occurs;
     * this method is called on a closed <code>PreparedStatement</code> or
     * if parameterIndex does not correspond
     * to a parameter marker in the SQL statement,
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since 1.6
     */
    public synchronized void setBlob(int parameterIndex,
                                     InputStream inputStream)
                                     throws SQLException {
        setBinStream(parameterIndex, inputStream, -1);
    }

    /* @todo 1.9.0 - implement streaming and remove length limits */

    /**
     * Sets the designated parameter to a <code>Reader</code> object.
     * This method differs from the <code>setCharacterStream (int, Reader)</code> method
     * because it informs the driver that the parameter value should be sent to
     * the server as a <code>NCLOB</code>.  When the <code>setCharacterStream</code> method is used, the
     * driver may have to do extra work to determine whether the parameter
     * data should be sent to the server as a <code>LONGNVARCHAR</code> or a <code>NCLOB</code>
     * <P><B>Note:</B> Consult your JDBC driver documentation to determine if
     * it might be more efficient to use a version of
     * <code>setNClob</code> which takes a length parameter.
     *
     * @param parameterIndex index of the first parameter is 1, the second is 2, ...
     * @param reader An object that contains the data to set the parameter value to.
     * @throws SQLException if parameterIndex does not correspond to a parameter
     * marker in the SQL statement;
     * if the driver does not support national character sets;
     * if the driver can detect that a data conversion
     *  error could occur;  if a database access error occurs or
     * this method is called on a closed <code>PreparedStatement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     *
     * @since 1.6
     */
    public synchronized void setNClob(int parameterIndex,
                                      Reader reader) throws SQLException {
        setCharStream(parameterIndex, reader, -1);
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the maximum number of bytes that can be
     * returned for character and binary column values in a <code>ResultSet</code>
     * object produced by this <code>Statement</code> object.
     * This limit applies only to  <code>BINARY</code>, <code>VARBINARY</code>,
     * <code>LONGVARBINARY</code>, <code>CHAR</code>, <code>VARCHAR</code>,
     * (JDBC4 new:) <code>NCHAR</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code>
     * and <code>LONGVARCHAR</code> columns.  If the limit is exceeded, the
     * excess data is silently discarded.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB always returns zero, meaning there is no limit.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the current column size limit for columns storing character and
     *         binary values; zero means there is no limit
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #setMaxFieldSize
     */
    public synchronized int getMaxFieldSize() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return 0;
    }

    /**
     * <!-- start generic documentation -->
     * (JDBC4 clarification:) Sets the limit for the maximum number of bytes in a <code>ResultSet</code>
     * Sets the limit for the maximum number of bytes that can be returned for
     * character and binary column values in a <code>ResultSet</code>
     * object produced by this <code>Statement</code> object.
     *
     * This limit applies
     * only to <code>BINARY</code>, <code>VARBINARY</code>,
     * <code>LONGVARBINARY</code>, <code>CHAR</code>, <code>VARCHAR</code>,
     * (JDBC4 new:) <code>NCHAR</code>, <code>NVARCHAR</code>, <code>LONGNVARCHAR</code> and
     * <code>LONGVARCHAR</code> fields.  If the limit is exceeded, the excess data
     * is silently discarded. For maximum portability, use values
     * greater than 256.
     * <!-- emd generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * To present, calls to this method are simply ignored; HSQLDB always
     * stores the full number of bytes when dealing with any of the field types
     * mentioned above. These types all have an absolute maximum element upper
     * bound determined by the Java array index limit
     * java.lang.Integer.MAX_VALUE.  For XXXBINARY types, this translates to
     * Integer.MAX_VALUE bytes.  For XXXCHAR types, this translates to
     * 2 * Integer.MAX_VALUE bytes (2 bytes / character). <p>
     *
     * In practice, field sizes are limited to values much smaller than the
     * absolute maximum element upper bound, in particular due to limits imposed
     * on the maximum available Java heap memory.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param max the new column size limit in bytes; zero means there is no limit
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code>
     *            or the condition {@code max >= 0} is not satisfied
     * @see #getMaxFieldSize
     */
    public synchronized void setMaxFieldSize(int max) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (max < 0) {
            throw JDBCUtil.outOfRangeArgument();
        }
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the maximum number of rows that a
     * <code>ResultSet</code> object produced by this
     * <code>Statement</code> object can contain.  If this limit is exceeded,
     * the excess rows are silently dropped.
     * <!-- start generic documentation -->
     *
     * @return the current maximum number of rows for a <code>ResultSet</code>
     *         object produced by this <code>Statement</code> object;
     *         zero means there is no limit
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #setMaxRows
     */
    public synchronized int getMaxRows() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return maxRows;
    }

    /**
     * <!-- start generic documentation -->
     * (JDBC4 clarification:)
     * Sets the limit for the maximum number of rows that any
     * <code>ResultSet</code> object  generated by this <code>Statement</code>
     * object can contain to the given number.
     * If the limit is exceeded, the excess
     * rows are silently dropped.
     * <!-- end generic documentation -->
     *
     * @param max the new max rows limit; zero means there is no limit
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code>
     *            or the condition {@code max >= 0} is not satisfied
     * @see #getMaxRows
     */
    public synchronized void setMaxRows(int max) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (max < 0) {
            throw JDBCUtil.outOfRangeArgument();
        }

        maxRows = max;
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the number of seconds the driver will
     * wait for a <code>Statement</code> object to execute.
     * If the limit is exceeded, a
     * <code>SQLException</code> is thrown.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * To present, HSQLDB always returns zero, meaning there
     * is no limit.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the current query timeout limit in seconds; zero means there is
     *         no limit
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #setQueryTimeout
     */
    public synchronized int getQueryTimeout() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return queryTimeout;
    }

    /**
     * <!-- start generic documentation -->
     * Sets the number of seconds the driver will wait for a
     * <code>Statement</code> object to execute to the given number of seconds.
     * If the limit is exceeded, an <code>SQLException</code> is thrown. A JDBC
     * (JDBC4 clarification:)
     * driver must apply this limit to the <code>execute</code>,
     * <code>executeQuery</code> and <code>executeUpdate</code> methods. JDBC driver
     * implementations may also apply this limit to <code>ResultSet</code> methods
     * (consult your driver vendor documentation for details).
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * The maximum number of seconds to wait is 32767.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param seconds the new query timeout limit in seconds; zero means
     *        there is no limit
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code>
     *            or the condition {@code seconds >= 0} is not satisfied
     * @see #getQueryTimeout
     */
    public synchronized void setQueryTimeout(int seconds) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (seconds < 0) {
            throw JDBCUtil.outOfRangeArgument();
        }

        if (seconds > Short.MAX_VALUE) {
            seconds = Short.MAX_VALUE;
        }

        queryTimeout = seconds;
    }

    /**
     * <!-- start generic documentation -->
     * Cancels this <code>Statement</code> object if both the DBMS and
     * driver support aborting an SQL statement.
     * This method can be used by one thread to cancel a statement that
     * is being executed by another thread.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB version 2.3.4 and later supports aborting an SQL query
     * or data update statement.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @exception SQLFeatureNotSupportedException if the JDBC driver does not support
     * this method
     */
    public synchronized void cancel() throws SQLException {

        checkClosed();

        String sql      = resultOut.getMainString();
        int    randomId = connection.sessionProxy.getRandomId();
        Result request  = Result.newCancelRequest(randomId, -1, sql);

        try {
            Result response = connection.sessionProxy.cancel(request);
        } catch (HsqlException e) {
            throw JDBCUtil.sqlException(e);
        }
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the first warning reported by calls on this <code>Statement</code> object.
     * Subsequent <code>Statement</code> object warnings will be chained to this
     * <code>SQLWarning</code> object.
     *
     * <p>The warning chain is automatically cleared each time
     * a statement is (re)executed. This method may not be called on a closed
     * <code>Statement</code> object; doing so will cause an <code>SQLException</code>
     * to be thrown.
     *
     * <P><B>Note:</B> If you are processing a <code>ResultSet</code> object, any
     * warnings associated with reads on that <code>ResultSet</code> object
     * will be chained on it rather than on the <code>Statement</code>
     * object that produced it.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * From 1.9 HSQLDB, produces Statement warnings.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the first <code>SQLWarning</code> object or <code>null</code>
     *         if there are no warnings
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     */
    public synchronized SQLWarning getWarnings() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return rootWarning;
    }

    /**
     * <!-- start generic documentation -->
     * Clears all the warnings reported on this <code>Statement</code>
     * object. After a call to this method,
     * the method <code>getWarnings</code> will return
     * <code>null</code> until a new warning is reported for this
     * <code>Statement</code> object.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Supported in HSQLDB 1.9.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     */
    public synchronized void clearWarnings() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        rootWarning = null;
    }

    /* @todo 1.9.0 - implement */

    /**
     * <!-- start generic documentation -->
     * Sets the SQL cursor name to the given <code>String</code>, which
     * will be used by subsequent <code>Statement</code> object
     * <code>execute</code> methods. This name can then be
     * used in SQL positioned update or delete statements to identify the
     * current row in the <code>ResultSet</code> object generated by this
     * statement.  If the database does not support positioned update/delete,
     * this method is a noop.  To ensure that a cursor has the proper isolation
     * level to support updates, the cursor's <code>SELECT</code> statement
     * should have the form <code>SELECT FOR UPDATE</code>.  If
     * <code>FOR UPDATE</code> is not present, positioned updates may fail.
     *
     * <P><B>Note:</B> By definition, the execution of positioned updates and
     * deletes must be done by a different <code>Statement</code> object than
     * the one that generated the <code>ResultSet</code> object being used for
     * positioning. Also, cursor names must be unique within a connection.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Including 2.0, HSQLDB does not support named cursors;
     * calls to this method are ignored.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param name the new cursor name, which must be unique within
     *             a connection
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @throws SQLFeatureNotSupportedException  if the JDBC driver does not support this method
     */
    public void setCursorName(String name) throws SQLException {
        checkClosed();
    }

    //----------------------- Multiple Results --------------------------

    /**
     * <!-- start generic documentation -->
     *  Retrieves the current result as a <code>ResultSet</code> object.
     *  This method should be called only once per result.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Without an interceding call to executeXXX, each invocation of this
     * method will produce a new, initialized ResultSet instance referring to
     * the current result, if any.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the current result as a <code>ResultSet</code> object or
     * <code>null</code> if the result is an update count or there are no more results
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #execute
     */
    public synchronized ResultSet getResultSet() throws SQLException {
        return super.getResultSet();
    }

    /**
     * <!-- start generic documentation -->
     *  Retrieves the current result as an update count;
     *  if the result is a <code>ResultSet</code> object or there are no more results, -1
     *  is returned. This method should be called only once per result.
     * <!-- end generic documentation -->
     *
     * @return the current result as an update count; -1 if the current result is a
     * <code>ResultSet</code> object or there are no more results
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #execute
     */
    public synchronized int getUpdateCount() throws SQLException {
        return super.getUpdateCount();
    }

    /**
     * <!-- start generic documentation -->
     * Moves to this <code>Statement</code> object's next result, returns
     * <code>true</code> if it is a <code>ResultSet</code> object, and
     * implicitly closes any current <code>ResultSet</code>
     * object(s) obtained with the method <code>getResultSet</code>.
     *
     * <P>There are no more results when the following is true:
     * <PRE>{@code
     *     // stmt is a Statement object
     *     ((stmt.getMoreResults() == false) && (stmt.getUpdateCount() == -1))
     * }</PRE>
     * <!-- end generic documentation -->
     *
     * @return <code>true</code> if the next result is a <code>ResultSet</code>
     *         object; <code>false</code> if it is an update count or there are
     *         no more results
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #execute
     */
    public synchronized boolean getMoreResults() throws SQLException {
        return getMoreResults(JDBCStatementBase.CLOSE_CURRENT_RESULT);
    }

    //--------------------------JDBC 2.0-----------------------------

    /**
     * <!-- start generic documentation -->
     * Gives the driver a hint as to the direction in which
     * rows will be processed in <code>ResultSet</code>
     * objects created using this <code>Statement</code> object.  The
     * default value is <code>ResultSet.FETCH_FORWARD</code>.
     * <P>
     * Note that this method sets the default fetch direction for
     * result sets generated by this <code>Statement</code> object.
     * Each result set has its own methods for getting and setting
     * its own fetch direction.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Up to 1.8.0.x, HSQLDB supports only <code>FETCH_FORWARD</code>;
     * Setting any other value would throw an <code>SQLException</code>
     * stating that the operation is not supported. <p>
     *
     * Starting with 2.0, HSQLDB accepts any valid value.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param direction the initial direction for processing rows
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code>
     * or the given direction
     * is not one of <code>ResultSet.FETCH_FORWARD</code>,
     * <code>ResultSet.FETCH_REVERSE</code>, or <code>ResultSet.FETCH_UNKNOWN</code>
     * @since JDK 1.2
     * @see #getFetchDirection
     */
    public synchronized void setFetchDirection(int direction)
    throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (direction != ResultSet.FETCH_FORWARD
                && direction != ResultSet.FETCH_REVERSE
                && direction != ResultSet.FETCH_UNKNOWN) {
            throw JDBCUtil.notSupported();
        }

        fetchDirection = direction;
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the direction for fetching rows from
     * database tables that is the default for result sets
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch direction by calling the method <code>setFetchDirection</code>,
     * the return value is implementation-specific.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Up to 1.8.0.x, HSQLDB always returned FETCH_FORWARD.
     *
     * Starting with 2.0, HSQLDB returns FETCH_FORWARD by default, or
     * whatever value has been explicitly assigned by invoking
     * <code>setFetchDirection</code>.
     * .
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the default fetch direction for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.2
     * @see #setFetchDirection
     */
    public synchronized int getFetchDirection() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return fetchDirection;
    }

    /**
     * <!-- start generic documentation -->
     * (JDBC4 clarification:)
     * Gives the JDBC driver a hint as to the number of rows that should
     * be fetched from the database when more rows are needed for
     * <code>ResultSet</code> objects generated by this <code>Statement</code>.
     * If the value specified is zero, then the hint is ignored.
     * The default value is zero.
     * <!-- start generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB uses the specified value as a hint, but may process more or fewer
     * rows than specified.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @param rows the number of rows to fetch
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code> or the
     *        (JDBC4 modified:)
     *        condition  {@code <code>rows >= 0</code>} is not satisfied.
     * @since JDK 1.2
     * @see #getFetchSize
     */
    public synchronized void setFetchSize(int rows) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (rows < 0) {
            throw JDBCUtil.outOfRangeArgument();
        }

        fetchSize = rows;
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the number of result set rows that is the default
     * fetch size for <code>ResultSet</code> objects
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch size by calling the method <code>setFetchSize</code>,
     * the return value is implementation-specific.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <b>HSQLDB-Specific Information</b> <p>
     *
     * HSQLDB returns 0 by default, or the fetch size specified by setFetchSize
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return the default fetch size for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.2
     * @see #setFetchSize
     */
    public synchronized int getFetchSize() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return fetchSize;
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the result set concurrency for <code>ResultSet</code> objects
     * generated by this <code>Statement</code> object.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB supports <code>CONCUR_READ_ONLY</code> and
     * <code>CONCUR_READ_UPDATEBLE</code> concurrency.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return either <code>ResultSet.CONCUR_READ_ONLY</code> or
     * <code>ResultSet.CONCUR_UPDATABLE</code>
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.2
     */
    public synchronized int getResultSetConcurrency() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return ResultProperties.getJDBCConcurrency(rsProperties);
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the result set type for <code>ResultSet</code> objects
     * generated by this <code>Statement</code> object.
     * <!-- end generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * HSQLDB 1.7.0 and later versions support <code>TYPE_FORWARD_ONLY</code>
     * and <code>TYPE_SCROLL_INSENSITIVE</code>.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @return one of <code>ResultSet.TYPE_FORWARD_ONLY</code>,
     * <code>ResultSet.TYPE_SCROLL_INSENSITIVE</code>, or
     * <code>ResultSet.TYPE_SCROLL_SENSITIVE</code>
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.2
     */
    public synchronized int getResultSetType() throws SQLException {

        // fredt - omit checkClosed() in order to be able to handle the result of a
        // SHUTDOWN query
        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return ResultProperties.getJDBCScrollability(rsProperties);
    }

    /**
     * <!-- start generic documentation -->
     * Empties this <code>Statement</code> object's current list of
     * SQL commands.
     * <P>
     * (JDBC4 clarification:) <p>
     * <B>NOTE:</B>  Support of an ability to batch updates is optional.
     * <!-- start generic documentation -->
     *
     * <!-- start release-specific documentation -->
     * <div class="ReleaseSpecificDocumentation">
     * <h1>HSQLDB-Specific Information:</h1> <p>
     *
     * Starting with HSQLDB 1.7.2, this feature is supported.
     * </div>
     * <!-- end release-specific documentation -->
     *
     * @exception SQLException if a database access error occurs,
     *  this method is called on a closed <code>Statement</code> or the
     * driver does not support batch updates
     * @see #addBatch
     * @since JDK 1.2
     */
    public synchronized void clearBatch() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (isBatch) {
            resultOut.getNavigator().clear();
        }
    }

    /**
     * <!-- start generic documentation -->
     * Retrieves the <code>Connection</code> object
     * that produced this <code>Statement</code> object.
     * <!-- end generic documentation -->
     *
     * @return the connection that produced this statement
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @since JDK 1.2
     */
    public synchronized Connection getConnection() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return connection;
    }

    //----------------------------- JDBC 4.0 -----------------------------------
    boolean poolable = true;

    /**
     * Requests that a <code>Statement</code> be pooled or not pooled.  The value
     * specified is a hint to the statement pool implementation indicating
     * whether the application wants the statement to be pooled.  It is up to
     * the statement pool manager as to whether the hint is used.
     * <p>
     * The poolable value of a statement is applicable to both internal
     * statement caches implemented by the driver and external statement caches
     * implemented by application servers and other applications.
     * <p>
     * By default, a <code>Statement</code> is not poolable when created, and
     * a <code>PreparedStatement</code> and <code>CallableStatement</code>
     * are poolable when created.
     * <p>
     * @param poolable          requests that the statement be pooled if true and
     *                                          that the statement not be pooled if false
     * <p>
     * @throws SQLException if this method is called on a closed
     * <code>Statement</code>
     * <p>
     * @since JDK 1.6, HSQLDB 2.0
     */
    public synchronized void setPoolable(boolean poolable)
    throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        this.poolable = poolable;
    }

    /**
     * Returns a  value indicating whether the <code>Statement</code>
     * is poolable or not.
     * <p>
     * @return          <code>true</code> if the <code>Statement</code>
     * is poolable; <code>false</code> otherwise
     * @throws SQLException if this method is called on a closed
     * <code>Statement</code>
     * <p>
     * @since JDK 1.6, HSQLDB 2.0
     * <p>
     * @see #setPoolable(boolean) setPoolable(boolean)
     */
    public synchronized boolean isPoolable() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        return this.poolable;
    }

    // ------------------- java.sql.Wrapper implementation ---------------------

    /**
     * Returns an object that implements the given interface to allow access to
     * non-standard methods, or standard methods not exposed by the proxy.
     *
     * If the receiver implements the interface then the result is the receiver
     * or a proxy for the receiver. If the receiver is a wrapper
     * and the wrapped object implements the interface then the result is the
     * wrapped object or a proxy for the wrapped object. Otherwise return the
     * result of calling <code>unwrap</code> recursively on the wrapped object
     * or a proxy for that result. If the receiver is not a
     * wrapper and does not implement the interface, then an <code>SQLException</code> is thrown.
     *
     * @param iface A Class defining an interface that the result must implement.
     * @return an object that implements the interface. May be a proxy for the actual implementing object.
     * @throws java.sql.SQLException If no object found that implements the interface
     * @since JDK 1.6, HSQLDB 2.0
     */
    @SuppressWarnings("unchecked")
    public <T>T unwrap(Class<T> iface) throws java.sql.SQLException {

        if (isWrapperFor(iface)) {
            return (T) this;
        }

        throw JDBCUtil.invalidArgument("iface: " + iface);
    }

    /**
     * Returns true if this either implements the interface argument or is directly or indirectly a wrapper
     * for an object that does. Returns false otherwise. If this implements the interface then return true,
     * else if this is a wrapper then return the result of recursively calling <code>isWrapperFor</code> on the wrapped
     * object. If this does not implement the interface and is not a wrapper, return false.
     * This method should be implemented as a low-cost operation compared to <code>unwrap</code> so that
     * callers can use this method to avoid expensive <code>unwrap</code> calls that may fail. If this method
     * returns true then calling <code>unwrap</code> with the same argument should succeed.
     *
     * @param iface a Class defining an interface.
     * @return true if this implements the interface or directly or indirectly wraps an object that does.
     * @throws java.sql.SQLException  if an error occurs while determining whether this is a wrapper
     * for an object with the given interface.
     * @since JDK 1.6, HSQLDB 2.0
     */
    public boolean isWrapperFor(
            java.lang.Class<?> iface) throws java.sql.SQLException {
        return (iface != null && iface.isAssignableFrom(this.getClass()));
    }

    //------------------------- JDBC 4.2 -----------------------------------

    /**
     *  Retrieves the current result as an update count; if the result
     * is a <code>ResultSet</code> object or there are no more results, -1
     *  is returned. This method should be called only once per result.
     * <p>
     * This method should be used when the returned row count may exceed
     * {@link Integer#MAX_VALUE}.
     * <p>
     * The public implementation will throw {@code UnsupportedOperationException}
     *
     * @return the current result as an update count; -1 if the current result
     * is a <code>ResultSet</code> object or there are no more results
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #execute
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized long getLargeUpdateCount() throws SQLException {
        return super.getUpdateCount();
    }

//#endif JAVA8

    /**
     * Sets the limit for the maximum number of rows that any
     * <code>ResultSet</code> object  generated by this <code>Statement</code>
     * object can contain to the given number.
     * If the limit is exceeded, the excess
     * rows are silently dropped.
     * <p>
     * This method should be used when the row limit may exceed
     * {@link Integer#MAX_VALUE}.
     * <p>
     * The default implementation will throw {@code UnsupportedOperationException}
     *
     * @param max the new max rows limit; zero means there is no limit
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code>
     *            or the condition {@code max >= 0} is not satisfied
     * @see #getMaxRows
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized void setLargeMaxRows(long max) throws SQLException {

        int maxRows = max > Integer.MAX_VALUE ? Integer.MAX_VALUE :
                                                (int) max;
        setMaxRows(maxRows);
    }

//#endif JAVA8

    /**
     * Retrieves the maximum number of rows that a
     * <code>ResultSet</code> object produced by this
     * <code>Statement</code> object can contain.  If this limit is exceeded,
     * the excess rows are silently dropped.
     * <p>
     * This method should be used when the returned row limit may exceed
     * {@link Integer#MAX_VALUE}.
     * <p>
     * The default implementation will return {@code 0}
     *
     * @return the current maximum number of rows for a <code>ResultSet</code>
     *         object produced by this <code>Statement</code> object;
     *         zero means there is no limit
     * @exception SQLException if a database access error occurs or
     * this method is called on a closed <code>Statement</code>
     * @see #setMaxRows
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized long getLargeMaxRows() throws SQLException {
        return maxRows;
    }

//#endif JAVA8

    /**
     * Submits a batch of commands to the database for execution and
     * if all commands execute successfully, returns an array of update counts.
     * The <code>long</code> elements of the array that is returned are ordered
     * to correspond to the commands in the batch, which are ordered
     * according to the order in which they were added to the batch.
     * The elements in the array returned by the method {@code executeLargeBatch}
     * may be one of the following:
     * <OL>
     * <LI>A number greater than or equal to zero -- indicates that the
     * command was processed successfully and is an update count giving the
     * number of rows in the database that were affected by the command's
     * execution
     * <LI>A value of <code>SUCCESS_NO_INFO</code> -- indicates that the command was
     * processed successfully but that the number of rows affected is
     * unknown
     * <P>
     * If one of the commands in a batch update fails to execute properly,
     * this method throws a <code>BatchUpdateException</code>, and a JDBC
     * driver may or may not continue to process the remaining commands in
     * the batch.  However, the driver's behavior must be consistent with a
     * particular DBMS, either always continuing to process commands or never
     * continuing to process commands.  If the driver continues processing
     * after a failure, the array returned by the method
     * <code>BatchUpdateException.getLargeUpdateCounts</code>
     * will contain as many elements as there are commands in the batch, and
     * at least one of the elements will be the following:
     *
     * <LI>A value of <code>EXECUTE_FAILED</code> -- indicates that the command failed
     * to execute successfully and occurs only if a driver continues to
     * process commands after a command fails
     * </OL>
     * <p>
     * This method should be used when the returned row count may exceed
     * {@link Integer#MAX_VALUE}.
     * <p>
     * The default implementation will throw {@code UnsupportedOperationException}
     *
     * @return an array of update counts containing one element for each
     * command in the batch.  The elements of the array are ordered according
     * to the order in which commands were added to the batch.
     * @exception SQLException if a database access error occurs,
     * this method is called on a closed <code>Statement</code> or the
     * driver does not support batch statements. Throws {@link BatchUpdateException}
     * (a subclass of <code>SQLException</code>) if one of the commands sent to the
     * database fails to execute properly or attempts to return a result set.
     * @throws SQLTimeoutException when the driver has determined that the
     * timeout value that was specified by the {@code setQueryTimeout}
     * method has been exceeded and has at least attempted to cancel
     * the currently running {@code Statement}
     *
     * @see #addBatch
     * @see DatabaseMetaData#supportsBatchUpdates
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized long[] executeLargeBatch() throws SQLException {
        int[] updateCounts = executeBatch();

        long[] longCounts = new long[updateCounts.length];

        for(int i = 0; i < updateCounts.length; i++) {
            longCounts[i] = updateCounts[i];
        }

        return longCounts;
    }

//#endif JAVA8

    /**
     * <p>Sets the value of the designated parameter with the given object.
     *
     * If the second argument is an {@code InputStream} then the stream
     * must contain the number of bytes specified by scaleOrLength.
     * If the second argument is a {@code Reader} then the reader must
     * contain the number of characters specified by scaleOrLength. If these
     * conditions are not true the driver will generate a
     * {@code SQLException} when the prepared statement is executed.
     *
     * <p>The given Java object will be converted to the given targetSqlType
     * before being sent to the database.
     *
     * If the object has a custom mapping (is of a class implementing the
     * interface {@code SQLData}),
     * the JDBC driver should call the method {@code SQLData.writeSQL} to
     * write it to the SQL data stream.
     * If, on the other hand, the object is of a class implementing
     * {@code Ref}, {@code Blob}, {@code Clob},  {@code NClob},
     *  {@code Struct}, {@code java.net.URL},
     * or {@code Array}, the driver should pass it to the database as a
     * value of the corresponding SQL type.
     *
     * <p>Note that this method may be used to pass database-specific
     * abstract data types.
     * <P>
     * The default implementation will throw {@code SQLFeatureNotSupportedException}
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type to be sent to the database. The
     * scale argument may further qualify this type.
     * @param scaleOrLength for {@code java.sql.JDBCType.DECIMAL}
     *          or {@code java.sql.JDBCType.NUMERIC types},
     *          this is the number of digits after the decimal point. For
     *          Java Object types {@code InputStream} and {@code Reader},
     *          this is the length
     *          of the data in the stream or reader.  For all other types,
     *          this value will be ignored.
     * @exception SQLException if parameterIndex does not correspond to a
     * parameter marker in the SQL statement; if a database access error occurs
     * or this method is called on a closed {@code PreparedStatement}  or
     *            if the Java Object specified by x is an InputStream
     *            or Reader object and the value of the scale parameter is less
     *            than zero
     * @exception SQLFeatureNotSupportedException if
     * the JDBC driver does not support the specified targetSqlType
     * @see JDBCType
     * @see SQLType
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized void setObject(int parameterIndex, Object x, SQLType targetSqlType,
                           int scaleOrLength) throws SQLException {
        int typeNo = targetSqlType.getVendorTypeNumber().intValue();
        setObject(parameterIndex, x, typeNo, scaleOrLength);
    }

//#endif JAVA8

    /**
     * Sets the value of the designated parameter with the given object.
     *
     * This method is similar to {@link #setObject(int parameterIndex,
     * Object x, SQLType targetSqlType, int scaleOrLength)},
     * except that it assumes a scale of zero.
     * <P>
     * The default implementation will throw {@code SQLFeatureNotSupportedException}
     *
     * @param parameterIndex the first parameter is 1, the second is 2, ...
     * @param x the object containing the input parameter value
     * @param targetSqlType the SQL type to be sent to the database
     * @exception SQLException if parameterIndex does not correspond to a
     * parameter marker in the SQL statement; if a database access error occurs
     * or this method is called on a closed {@code PreparedStatement}
     * @exception SQLFeatureNotSupportedException if the JDBC driver does not support
     * the specified targetSqlType
     * @see JDBCType
     * @see SQLType
     * @since 1.8
     */
//#ifdef JAVA8
    public synchronized void setObject(int parameterIndex, Object x, SQLType targetSqlType)
            throws SQLException {
        int typeNo = targetSqlType.getVendorTypeNumber().intValue();
        setObject(parameterIndex, x, typeNo);
    }

//#endif JAVA8

    /**
     * Executes the SQL statement in this <code>PreparedStatement</code> object,
     * which must be an SQL Data Manipulation Language (DML) statement,
     * such as <code>INSERT</code>, <code>UPDATE</code> or
     * <code>DELETE</code>; or an SQL statement that returns nothing,
     * such as a DDL statement.
     * <p>
     * This method should be used when the returned row count may exceed
     * {@link Integer#MAX_VALUE}.
     * <p>
     * The default implementation will throw {@code UnsupportedOperationException}
     *
     * @return either (1) the row count for SQL Data Manipulation Language
     * (DML) statements or (2) 0 for SQL statements that return nothing
     * @exception SQLException if a database access error occurs;
     * this method is called on a closed  <code>PreparedStatement</code>
     * or the SQL statement returns a <code>ResultSet</code> object
     * @throws SQLTimeoutException when the driver has determined that the
     * timeout value that was specified by the {@code setQueryTimeout}
     * method has been exceeded and has at least attempted to cancel
     * the currently running {@code Statement}
     * @since 1.8
     */
    public synchronized long executeLargeUpdate() throws SQLException {
        return executeUpdate();
    }

    //-------------------- Internal Implementation -----------------------------

    /**
     * Constructs a statement that produces results of the requested
     * <code>type</code>. <p>
     *
     * A prepared statement must be a single SQL statement. <p>
     *
     * @param c the Connection used execute this statement
     * @param sql the SQL statement this object represents
     * @param resultSetType the type of result this statement will produce (scrollability)
     * @param resultSetConcurrency (updatability)
     * @param resultSetHoldability (validity beyond commit)
     * @param generatedKeys internal mode of handling generated key reporting
     * @param generatedIndexes column indexes for generated keys
     * @param generatedNames column names for generated keys are given
     * @throws HsqlException if the statement is not accepted by the database
     * @throws SQLException if preprocessing by driver fails
     */
    JDBCPreparedStatement(JDBCConnection c, String sql, int resultSetType,
                          int resultSetConcurrency, int resultSetHoldability,
                          int generatedKeys, int[] generatedIndexes,
                          String[] generatedNames)
                          throws HsqlException, SQLException {

        isResult              = false;
        connection            = c;
        connectionIncarnation = connection.incarnation;
        session               = c.sessionProxy;
        sql                   = c.nativeSQL(sql);
        resultOut             = Result.newPrepareStatementRequest();

        int props = ResultProperties.getValueForJDBC(resultSetType,
            resultSetConcurrency, resultSetHoldability);

        resultOut.setPrepareOrExecuteProperties(sql, 0, 0, 0, queryTimeout,
                props, generatedKeys, generatedIndexes, generatedNames);

        Result in = session.execute(resultOut);

        if (in.mode == ResultConstants.ERROR) {
            throw JDBCUtil.sqlException(in);
        }

        rootWarning = null;

        Result current = in;

        while (current.getChainedResult() != null) {
            current = current.getUnlinkChainedResult();

            if (current.isWarning()) {
                SQLWarning w = JDBCUtil.sqlWarning(current);

                if (rootWarning == null) {
                    rootWarning = w;
                } else {
                    rootWarning.setNextWarning(w);
                }
            }
        }

        connection.setWarnings(rootWarning);

        statementID       = in.getStatementID();
        statementRetType  = in.getStatementType();
        resultMetaData    = in.metaData;
        parameterMetaData = in.parameterMetaData;
        parameterTypes    = parameterMetaData.getParameterTypes();
        parameterModes    = parameterMetaData.paramModes;
        rsProperties      = in.rsProperties;

        //
        int paramCount = parameterMetaData.getColumnCount();

        parameterValues = new Object[paramCount];
        parameterSet    = new boolean[paramCount];
        streamLengths   = new long[paramCount];

        for (int i = 0; i < paramCount; i++) {
            if (parameterTypes[i].isLobType()) {
                hasLOBs = true;

                break;
            }
        }

        //
        resultOut = Result.newPreparedExecuteRequest(parameterTypes,
                statementID);

        resultOut.setStatement(in.getStatement());

        // for toString()
        this.sql = sql;
    }

    /**
     * Constructor for updatable ResultSet
     */
    JDBCPreparedStatement(JDBCConnection c, Result result) {

        isResult              = true;
        connection            = c;
        connectionIncarnation = connection.incarnation;
        session               = c.sessionProxy;

        int paramCount = result.metaData.getExtendedColumnCount();

        parameterMetaData = result.metaData;
        parameterTypes    = result.metaData.columnTypes;
        parameterModes    = new byte[paramCount];
        parameterValues   = new Object[paramCount];
        parameterSet      = new boolean[paramCount];
        streamLengths     = new long[paramCount];

        //
        for (int i = 0; i < paramCount; i++) {
            parameterModes[i] = SchemaObject.ParameterModes.PARAM_IN;

            if (parameterTypes[i].isLobType()) {
                hasLOBs = true;
            }
        }

        //
        resultOut = Result.newUpdateResultRequest(parameterTypes,
                result.getResultId());
    }

    /**
     * Checks if execution does or does not generate a single row
     * update count, throwing if the argument, yes, does not match. <p>
     *
     * @param type type of statement regarding what it returns
     *      something other than a single row update count.
     * @throws SQLException if the argument, yes, does not match
     */
    protected void checkStatementType(int type) throws SQLException {

        if (type != statementRetType) {
            if (statementRetType == StatementTypes.RETURN_COUNT) {
                throw JDBCUtil.sqlException(ErrorCode.X_07504);
            } else {
                throw JDBCUtil.sqlException(ErrorCode.X_07503);
            }
        }
    }

    protected void checkParameterIndex(int i) throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (i < 1 || i > parameterValues.length) {
            String msg = "parameter index out of range: " + i;

            throw JDBCUtil.outOfRangeArgument(msg);
        }
    }

    /**
     * Checks if the specified parameter index value is valid in terms of
     * setting an IN or IN OUT parameter value. <p>
     *
     * @param parameterIndex The parameter index to check
     * @throws SQLException if the specified parameter index is invalid
     */
    protected void checkSetParameterIndex(int parameterIndex)
    throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (parameterIndex < 1 || parameterIndex > parameterValues.length) {
            String msg = "parameter index out of range: " + parameterIndex;

            throw JDBCUtil.outOfRangeArgument(msg);
        }

        if (parameterModes[parameterIndex - 1]
                == SchemaObject.ParameterModes.PARAM_OUT) {
            String msg = "Not IN or INOUT mode for parameter: "
                         + parameterIndex;

            throw JDBCUtil.invalidArgument(msg);
        }
    }

    /**
     * Checks if the specified parameter index value is valid in terms of
     * getting an OUT or INOUT parameter value. <p>
     *
     * @param parameterIndex The parameter index to check
     * @throws SQLException if the specified parameter index is invalid
     */
    protected void checkGetParameterIndex(int parameterIndex)
    throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        if (parameterIndex < 1 || parameterIndex > parameterValues.length) {
            String msg = "parameter index out of range: " + parameterIndex;

            throw JDBCUtil.outOfRangeArgument(msg);
        }

        int mode = parameterModes[parameterIndex - 1];

        switch (mode) {

            case SchemaObject.ParameterModes.PARAM_UNKNOWN :
            case SchemaObject.ParameterModes.PARAM_OUT :
            case SchemaObject.ParameterModes.PARAM_INOUT :
                break;

            case SchemaObject.ParameterModes.PARAM_IN :
            default :
                String msg = "Not OUT or INOUT mode for parameter: "
                             + parameterIndex;

                throw JDBCUtil.invalidArgument(msg);
        }
    }

    /**
     * Called just before execution or adding to batch, this ensures all the
     * parameters have been set.<p>
     *
     * If a parameter has been set using a stream method, it should be set
     * again for the next reuse. When set using other methods, the parameter
     * setting is retained for the next use.
     * @throws SQLException if a parameter has not been set
     */
    private void checkParametersSet() throws SQLException {

        if (isResult) {
            return;
        }

        for (int i = 0; i < parameterSet.length; i++) {
            if (parameterModes[i] != SchemaObject.ParameterModes.PARAM_OUT) {
                if (!parameterSet[i]) {
                    throw JDBCUtil.sqlException(
                        ErrorCode.JDBC_PARAMETER_NOT_SET);
                }
            }
        }
    }

    /**
     * The internal parameter value setter always converts the parameter to
     * the type required for data transmission.
     *
     * @param parameterIndex parameter index
     * @param o object
     * @throws SQLException if either argument is not acceptable.
     */
    void setParameter(int parameterIndex, Object o) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index = parameterIndex - 1;

        if (o == null) {
            parameterValues[index] = null;
            parameterSet[index]    = true;

            return;
        }

        Type outType = parameterTypes[index];

        switch (outType.typeCode) {

            case Types.OTHER :
                try {
                    if (connection.isStoreLiveObject) {
                        o = new JavaObjectDataInternal(o);

                        break;
                    }

                    if (o instanceof Serializable) {
                        o = new JavaObjectData((Serializable) o);

                        break;
                    }
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }

                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            case Types.SQL_BIT :
            case Types.SQL_BIT_VARYING :
                try {
                    if (o instanceof Boolean) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }

                    if (o instanceof Integer) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }

                    if (o instanceof byte[]) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }

                    if (o instanceof String) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }

                    if (o instanceof BitSet) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }

                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            case Types.SQL_BINARY :
            case Types.SQL_VARBINARY :
            case Types.SQL_GUID :
                if (o instanceof byte[]) {
                    o = new BinaryData((byte[]) o, !connection.isNetConn);

                    break;
                }

                if (o instanceof UUID) {
                    o = BinaryUUIDType.getBinary((UUID) o);

                    break;
                }

                try {
                    if (o instanceof String) {
                        o = outType.convertToDefaultType(session, o);

                        break;
                    }
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }

                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            case Types.SQL_ARRAY :
                if (o instanceof Array) {
                    setArray(parameterIndex, (Array) o);

                    return;
                }

                if (o instanceof ArrayList) {
                    o = ((ArrayList) o).toArray();
                }

                if (o instanceof Object[]) {
                    Type     baseType = outType.collectionBaseType();
                    Object[] array    = (Object[]) o;
                    Object[] data     = new Object[array.length];

                    for (int j = 0; j < data.length; j++) {
                        data[j] = baseType.convertJavaToSQL(session, array[j]);
                    }

                    o = data;

                    break;
                }

                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            case Types.SQL_BLOB :
                setBlobParameter(parameterIndex, o);

                return;

            case Types.SQL_CLOB :
                setClobParameter(parameterIndex, o);

                return;

            case Types.SQL_DATE :
            case Types.SQL_TIME_WITH_TIME_ZONE :
            case Types.SQL_TIMESTAMP_WITH_TIME_ZONE :
            case Types.SQL_TIME :
            case Types.SQL_TIMESTAMP : {
                try {
                    if (o instanceof String) {
                        o = outType.convertToType(session, o,
                                                  Type.SQL_VARCHAR);

                        break;
                    }

                    o = outType.convertJavaToSQL(session, o);

                    break;
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }
            }
            case Types.TINYINT :
            case Types.SQL_SMALLINT :
            case Types.SQL_INTEGER :
            case Types.SQL_BIGINT :
            case Types.SQL_REAL :
            case Types.SQL_FLOAT :
            case Types.SQL_DOUBLE :
            case Types.SQL_NUMERIC :
            case Types.SQL_DECIMAL :
                try {
                    if (o instanceof String) {
                        o = outType.convertToType(session, o,
                                                  Type.SQL_VARCHAR);

                        break;
                    } else if (o instanceof Boolean) {
                        boolean value = ((Boolean) o).booleanValue();

                        o = value ? Integer.valueOf(1)
                                  : Integer.valueOf(0);
                    }

                    o = outType.convertToDefaultType(session, o);

                    break;
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }
            case Types.SQL_VARCHAR : {
                if (o instanceof String) {
                    break;
                }

                try {
                    o = outType.convertToDefaultType(session, o);

                    break;
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }
            }
            case Types.SQL_CHAR : {
                if (o instanceof String) {
                    break;
                }

                if (outType.precision == 1) {
                    if (o instanceof Character) {
                        o = String.valueOf(((Character) o).charValue());

                        break;
                    } else if (o instanceof Boolean) {
                        o = ((Boolean) o).booleanValue() ? "1"
                                                         : "0";

                        break;
                    }
                }

                try {
                    o = outType.convertToDefaultType(session, o);
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }

                break;
            }
            default :
                try {
                    if (outType.isIntervalType()) {
                        o = outType.convertJavaToSQL(session, o);
                        break;
                    }

                    o = outType.convertToDefaultType(session, o);

                    break;
                } catch (HsqlException e) {
                    throw JDBCUtil.sqlException(e);
                }
        }

        parameterValues[index] = o;
        parameterSet[index]    = true;
    }

    void setClobParameter(int i, Object o) throws SQLException {
        setClobParameter(i, o, 0);
    }

    void setClobParameter(int i, Object o,
                          long streamLength) throws SQLException {

        if (o instanceof JDBCClobClient) {
            JDBCClobClient clob = (JDBCClobClient) o;

            if (!clob.session.getDatabaseUniqueName().equals(
                    session.getDatabaseUniqueName())) {
                streamLength = clob.length();

                Reader is = clob.getCharacterStream();

                parameterValues[i - 1] = is;
                streamLengths[i - 1]   = streamLength;
                parameterSet[i - 1]    = true;

                return;
            }

            parameterValues[i - 1] = o;
            parameterSet[i - 1]    = Boolean.TRUE;

            return;
        } else if (o instanceof Clob) {
            parameterValues[i - 1] = o;
            parameterSet[i - 1]    = Boolean.TRUE;

            return;
        } else if (o instanceof ClobInputStream) {
            ClobInputStream is = (ClobInputStream) o;

            if (is.session.getDatabaseUniqueName().equals(
                    session.getDatabaseUniqueName())) {
                throw JDBCUtil.sqlException(ErrorCode.JDBC_INVALID_ARGUMENT,
                                            "invalid Reader");
            }

            parameterValues[i - 1] = o;
            streamLengths[i - 1]   = streamLength;
            parameterSet[i - 1]    = true;

            return;
        } else if (o instanceof Reader) {
            parameterValues[i - 1] = o;
            streamLengths[i - 1]   = streamLength;
            parameterSet[i - 1]    = true;

            return;
        } else if (o instanceof String) {
            JDBCClob clob = new JDBCClob((String) o);

            parameterValues[i - 1] = clob;
            parameterSet[i - 1]    = true;

            return;
        }

        throw JDBCUtil.invalidArgument();
    }

    /**
     * setParameterForBlob
     *
     * @param i int
     * @param o Object
     */
    void setBlobParameter(int i, Object o) throws SQLException {
        setBlobParameter(i, o, 0);
    }

    void setBlobParameter(int i, Object o,
                          long streamLength) throws SQLException {

        if (o instanceof JDBCBlobClient) {
            JDBCBlobClient blob = (JDBCBlobClient) o;

            if (!blob.session.getDatabaseUniqueName().equals(
                    session.getDatabaseUniqueName())) {
                streamLength = blob.length();

                InputStream is = blob.getBinaryStream();

                parameterValues[i - 1] = is;
                streamLengths[i - 1]   = streamLength;
                parameterSet[i - 1]    = true;

                return;
            }

            // in the same database
            parameterValues[i - 1] = o;
            parameterSet[i - 1]    = Boolean.TRUE;

            return;
        } else if (o instanceof Blob) {
            parameterValues[i - 1] = o;
            parameterSet[i - 1]    = true;

            return;
        } else if (o instanceof BlobInputStream) {
            BlobInputStream is = (BlobInputStream) o;

            if (is.session.getDatabaseUniqueName().equals(
                    session.getDatabaseUniqueName())) {
                throw JDBCUtil.sqlException(ErrorCode.JDBC_INVALID_ARGUMENT,
                                            "invalid Reader");
            }

            // in the same database ? see if it blocks in
            parameterValues[i - 1] = o;
            streamLengths[i - 1]   = streamLength;
            parameterSet[i - 1]    = true;

            return;
        } else if (o instanceof InputStream) {
            parameterValues[i - 1] = o;
            streamLengths[i - 1]   = streamLength;
            parameterSet[i - 1]    = true;

            return;
        } else if (o instanceof byte[]) {
            JDBCBlob blob = new JDBCBlob((byte[]) o);

            parameterValues[i - 1] = blob;
            parameterSet[i - 1]    = true;

            return;
        }

        throw JDBCUtil.invalidArgument();
    }

    /**
     * Used with int and narrower integral primitives
     * @param parameterIndex parameter index
     * @param value object to set
     * @throws SQLException if either argument is not acceptable
     */
    void setIntParameter(int parameterIndex, int value) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index   = parameterIndex - 1;
        int outType = parameterTypes[index].typeCode;

        switch (outType) {

            case Types.TINYINT :
            case Types.SQL_SMALLINT :
            case Types.SQL_INTEGER : {
                Object o = Integer.valueOf(value);

                parameterValues[index] = o;
                parameterSet[index]    = true;

                break;
            }
            case Types.SQL_BIGINT : {
                Object o = Long.valueOf(value);

                parameterValues[index] = o;
                parameterSet[index]    = true;

                break;
            }
            case Types.SQL_BINARY :
            case Types.SQL_VARBINARY :
            case Types.OTHER :
                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            default :
                setParameter(parameterIndex, Integer.valueOf(value));
        }
    }

    /**
     * Used with long and narrower integral primitives. Conversion to BINARY
     * or OTHER types will throw here and not passed to setParameter().
     *
     * @param parameterIndex parameter index
     * @param value object to set
     * @throws SQLException if either argument is not acceptable
     */
    void setLongParameter(int parameterIndex, long value) throws SQLException {

        checkSetParameterIndex(parameterIndex);

        int index   = parameterIndex - 1;
        int outType = parameterTypes[index].typeCode;

        switch (outType) {

            case Types.SQL_BIGINT :
                Object o = Long.valueOf(value);

                parameterValues[index] = o;
                parameterSet[index]    = true;
                break;

            case Types.SQL_BINARY :
            case Types.SQL_VARBINARY :
            case Types.OTHER :
                throw JDBCUtil.sqlException(ErrorCode.X_42563);
            default :
                setParameter(parameterIndex, Long.valueOf(value));
        }
    }

    private void performPreExecute() throws SQLException, HsqlException {

        if (!hasLOBs) {
            return;
        }

        for (int i = 0; i < parameterValues.length; i++) {
            Object value = parameterValues[i];

            if (value == null) {
                continue;
            }

            if (parameterTypes[i].typeCode == Types.SQL_BLOB) {
                long       id;
                BlobDataID blob = null;

                if (value instanceof JDBCBlobClient) {

                    // check or fix id mismatch
                    blob = ((JDBCBlobClient) value).blob;
                    id   = blob.getId();
                } else if (value instanceof Blob) {
                    long length = ((Blob) value).length();

                    blob = session.createBlob(length);
                    id   = blob.getId();

                    InputStream stream = ((Blob) value).getBinaryStream();
                    ResultLob resultLob =
                        ResultLob.newLobCreateBlobRequest(session.getId(), id,
                                                          stream, length);

                    session.allocateResultLob(resultLob);
                    resultOut.addLobResult(resultLob);
                } else if (value instanceof InputStream) {
                    long length       = streamLengths[i];
                    long createLength = length > 0 ? length
                                                   : 0;

                    blob = session.createBlob(createLength);
                    id   = blob.getId();

                    InputStream stream = (InputStream) value;
                    ResultLob resultLob =
                        ResultLob.newLobCreateBlobRequest(session.getId(), id,
                                                          stream, length);

                    session.allocateResultLob(resultLob);
                    resultOut.addLobResult(resultLob);
                } else if (value instanceof BlobDataID) {
                    blob = (BlobDataID) value;
                }

                parameterValues[i] = blob;
            } else if (parameterTypes[i].typeCode == Types.SQL_CLOB) {
                long       id;
                ClobDataID clob = null;

                if (value instanceof JDBCClobClient) {

                    // check or fix id mismatch
                    clob = ((JDBCClobClient) value).clob;
                    id   = clob.getId();
                } else if (value instanceof Clob) {
                    long   length = ((Clob) value).length();
                    Reader reader = ((Clob) value).getCharacterStream();

                    clob = session.createClob(length);
                    id   = clob.getId();

                    ResultLob resultLob =
                        ResultLob.newLobCreateClobRequest(session.getId(), id,
                                                          reader, length);

                    session.allocateResultLob(resultLob);
                    resultOut.addLobResult(resultLob);
                } else if (value instanceof Reader) {
                    long length       = streamLengths[i];
                    long createLength = length > 0 ? length
                                                   : 0;

                    clob = session.createClob(createLength);
                    id   = clob.getId();

                    Reader reader = (Reader) value;
                    ResultLob resultLob =
                        ResultLob.newLobCreateClobRequest(session.getId(), id,
                                                          reader, length);

                    session.allocateResultLob(resultLob);
                    resultOut.addLobResult(resultLob);
                } else if (value instanceof ClobDataID) {
                    clob = (ClobDataID) value;
                }

                parameterValues[i] = clob;
            }
        }
    }

    /**
     * Internal result producer for JDBCStatement (sqlExecDirect mode).
     * <p>
     *
     * @throws SQLException when a database access error occurs
     */
    void fetchResult() throws SQLException {

        if (isClosed || connection.isClosed) {
            checkClosed();
        }

        closeResultData();
        checkParametersSet();

        if (isBatch) {
            throw JDBCUtil.sqlExceptionSQL(ErrorCode.X_07505);
        }

        //
        if (isResult) {
            resultOut.setPreparedResultUpdateProperties(parameterValues);
        } else {
            resultOut.setPreparedExecuteProperties(parameterValues, maxRows,
                                                   fetchSize, rsProperties,
                                                   queryTimeout);
        }

        try {
            performPreExecute();

            resultIn = session.execute(resultOut);
        } catch (HsqlException e) {
            throw JDBCUtil.sqlException(e);
        } finally {
            performPostExecute();
        }

        if (resultIn.mode == ResultConstants.ERROR) {
            throw JDBCUtil.sqlException(resultIn);
        }

        if (resultIn.isData()) {
            currentResultSet = new JDBCResultSet(connection, this, resultIn,
                                                 resultIn.metaData);
        } else if (statementRetType == StatementTypes.RETURN_RESULT) {
            getMoreResults();
        }
    }

    /**
     * processes chained warnings and any generated columns result set
     */
    void performPostExecute() throws SQLException {
        super.performPostExecute();
    }

    /** The parameter values for the next non-batch execution. */
    protected Object[] parameterValues;

    /** Flags for bound variables. */
    protected boolean[] parameterSet;

    /** The SQL types of the parameters. */
    protected Type[] parameterTypes;

    /** The (IN, IN OUT, or OUT) modes of parameters */
    protected byte[] parameterModes;

    /** Lengths for streams. */
    protected long[] streamLengths;

    /** Has one or more CLOB / BLOB type parameters. */
    protected boolean hasLOBs;

    /** Is in batch mode. */
    protected boolean isBatch;

    /** Description of result set metadata. */
    protected ResultMetaData resultMetaData;

    /** Description of parameter metadata. */
    protected ResultMetaData parameterMetaData;

    /** This object's one and one ResultSetMetaData object. */
    protected JDBCResultSetMetaData resultSetMetaData;

    /** This object's one and only ParameterMetaData object. */
    protected ParameterMetaData pmd;

    /** The SQL character sequence that this object represents. */
    protected String sql;

    /** ID of the statement. */
    protected long statementID;

    /** Statement type - whether it generates a row update count or a result set. */
    protected int statementRetType;

    /** Is part of a Result. */
    protected final boolean isResult;

    /** The session attribute of the connection */
    protected SessionInterface session;

    public String getSQL() {
        return sql;
    }

    public long getStatementID() {
        return statementID;
    }

    public boolean isRowCount() {
        return statementRetType == StatementTypes.RETURN_COUNT;
    }

    public JDBCResultSetMetaData getResultSetMetaData() {
        return resultSetMetaData;
    }

    public ResultMetaData getParameterMetaDataDirect() {
        return parameterMetaData;
    }
}
