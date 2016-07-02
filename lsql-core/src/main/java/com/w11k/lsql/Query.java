package com.w11k.lsql;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.w11k.lsql.converter.Converter;
import rx.Observable;
import rx.Subscriber;
import rx.annotations.Experimental;
import rx.functions.Func1;
import rx.subjects.Subject;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

public class Query {

    private final LSql lSql;

    private final PreparedStatement preparedStatement;

    private Map<String, Converter> converters = Maps.newHashMap();

    private boolean ignoreDuplicateColumns = false;

    public Query(LSql lSql, PreparedStatement preparedStatement) {
        this.lSql = lSql;
        this.preparedStatement = preparedStatement;
    }

    public Query(LSql lSql, String sql) {
        this(lSql, lSql.getDialect().getStatementCreator().createPreparedStatement(lSql, sql, false));
    }

    public LSql getlSql() {
        return lSql;
    }

    public Query ignoreDuplicateColumns() {
        this.ignoreDuplicateColumns = true;
        return this;
    }

    public PreparedStatement getPreparedStatement() {
        return preparedStatement;
    }

    public Map<String, Converter> getConverters() {
        return converters;
    }

    public Query setConverters(Map<String, Converter> converters) {
        this.converters = converters;
        return this;
    }

    public Query addConverter(String columnName, Converter converter) {
        this.converters.put(columnName, converter);
        return this;
    }

    public List<Row> toList() {
        return rx().toList().toBlocking().first();

    }

    /**
     * Executes the query and returns the first row in the result set. Return absent() if the result set is empty.
     */
    public Optional<Row> firstRow() {
        List<Row> list = rx().take(1).toList().toBlocking().first();
        if (list.isEmpty()) {
            return Optional.absent();
        } else {
            return Optional.of(list.get(0));
        }
    }

    /**
     * Turns this query into an Observable of {@code Row}s.  Each subscription will trigger the underlying database operation.
     *
     * @return the Observable
     */
    public Observable<Row> rx() {
        return rxResultSet().map(new Func1<ResultSetWithColumns, Row>() {
            @Override
            public Row call(ResultSetWithColumns resultSetWithColumns) {
                return extractRow(resultSetWithColumns);
            }
        });
    }

    /**
     * Turns this query into an Observable. Each subscription will trigger the underlying database operation.
     * <p/>
     * This is a low-level API to directly work with the JDBC ResultSet.
     *
     * @return the Observable
     */
    public Observable<ResultSetWithColumns> rxResultSet() {
        return Subject.create(new Observable.OnSubscribe<ResultSetWithColumns>() {
            @Override
            public void call(Subscriber<? super ResultSetWithColumns> subscriber) {
                try {
                    ResultSet resultSet = preparedStatement.executeQuery();
                    ResultSetMetaData metaData = resultSet.getMetaData();

                    // used to find duplicates
                    // or unused converter
                    Set<String> processedColumnLabels = Sets.newLinkedHashSet();

                    // queryConverters for columns
                    List<ResultSetColumn> resultSetColumns = Lists.newLinkedList();

                    for (int i = 1; i <= metaData.getColumnCount(); i++) {
                        String columnLabel = lSql.getDialect().identifierSqlToJava(metaData.getColumnLabel(i));

                        // check duplicates
                        if (!ignoreDuplicateColumns && processedColumnLabels.contains(columnLabel)) {
                            throw new IllegalStateException("Duplicate column '" + columnLabel + "' in query.");
                        }
                        processedColumnLabels.add(columnLabel);

                        Converter converter = getConverterForResultSetColumn(metaData, i, columnLabel);

                        resultSetColumns.add(new ResultSetColumn(i, columnLabel, converter));
                    }

                    // Check for unused converters
                    for (String converterFor : converters.keySet()) {
                        if (!processedColumnLabels.contains(converterFor)) {
                            throw new IllegalArgumentException(
                                    "unused converter for column '" + converterFor + "'");
                        }
                    }

                    ResultSetWithColumns resultSetWithColumns =
                            new ResultSetWithColumns(resultSet, resultSetColumns);

                    while (resultSet.next() && !subscriber.isUnsubscribed()) {
                        subscriber.onNext(resultSetWithColumns);
                    }
                    resultSet.close();
                    subscriber.onCompleted();
                } catch (SQLException e) {
                    subscriber.onError(e);
                }
            }
        });
    }

    @Experimental
    public LinkedHashMap<Number, Row> toTree() {
        return new QueryToTreeConverter(this).getTree();
    }

    @Experimental
    public <T> List<T> toPojo(Class<T> classForTopLevelRows) {
        LinkedHashMap<Number, Row> tree = this.toTree();
        Collection<Row> roots = tree.values();
        List<T> rootPojos = Lists.newLinkedList();
        for (Row root : roots) {
            T rootPojo = this.lSql.getObjectMapper().convertValue(root, classForTopLevelRows);
            rootPojos.add(rootPojo);
        }
        return rootPojos;
    }

    public Row extractRow(ResultSetWithColumns resultSetWithColumns) {
        ResultSet resultSet = resultSetWithColumns.getResultSet();
        List<ResultSetColumn> columnList = resultSetWithColumns.getResultSetColumns();
        Row row = new Row();
        for (ResultSetColumn column : columnList) {
            try {
                row.put(column.getName(),
                        column.getConverter().getValueFromResultSet(lSql, resultSet, column.getPosition()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
        return row;
    }

    Converter getConverterForResultSetColumn(ResultSetMetaData metaData, int position, String columnLabel)
            throws SQLException {

        // Check for user provided Converter
        if (converters.containsKey(columnLabel)) {
            Converter converter = converters.get(columnLabel);
            if (converter != null) {
                return converter;
            }
        }

        // Determine source table and column from ResultSet
        String tableName = lSql.getDialect().getTableNameFromResultSetMetaData(metaData, position);
        String columnName = lSql.getDialect().getColumnNameFromResultSetMetaData(metaData, position);
        if (tableName != null
                && tableName.length() > 0
                && columnName != null
                && columnName.length() > 0) {
            return lSql.table(tableName).column(columnName).getConverter();
        }

        // Check if the user registered null as a Converter to use a type-based Converter
        if (converters.containsKey(columnLabel)
                && converters.get(columnLabel) == null) {

            int columnSqlType = metaData.getColumnType(position);
            return lSql.getDialect().getConverterRegistry().getConverterForSqlType(columnSqlType);
        }

        // Error
        String msg = "Unable to determine a Converter instance for column '" + columnLabel + "'. ";
        msg += "Register a converter with Query#addConverter() / Query#setConverters().";
        throw new IllegalStateException(msg);
    }
}
