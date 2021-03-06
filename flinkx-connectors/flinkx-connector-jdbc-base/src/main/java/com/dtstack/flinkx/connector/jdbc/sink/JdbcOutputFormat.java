/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dtstack.flinkx.connector.jdbc.sink;

import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.types.Row;

import com.dtstack.flinkx.conf.FieldConf;
import com.dtstack.flinkx.connector.jdbc.JdbcDialect;
import com.dtstack.flinkx.connector.jdbc.conf.JdbcConf;
import com.dtstack.flinkx.connector.jdbc.statement.FieldNamedPreparedStatement;
import com.dtstack.flinkx.connector.jdbc.util.JdbcUtil;
import com.dtstack.flinkx.element.ColumnRowData;
import com.dtstack.flinkx.enums.ColumnType;
import com.dtstack.flinkx.enums.EWriteMode;
import com.dtstack.flinkx.exception.WriteRecordException;
import com.dtstack.flinkx.outputformat.BaseRichOutputFormat;
import com.dtstack.flinkx.util.DateUtil;
import com.dtstack.flinkx.util.ExceptionUtil;
import com.dtstack.flinkx.util.GsonUtil;
import com.dtstack.flinkx.util.JsonUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * OutputFormat for writing data to relational database.
 * <p>
 * Company: www.dtstack.com
 *
 * @author huyifan.zju@163.com
 */
public class JdbcOutputFormat extends BaseRichOutputFormat {

    protected static final Logger LOG = LoggerFactory.getLogger(JdbcOutputFormat.class);

    protected static final long serialVersionUID = 1L;

    protected static List<String> STRING_TYPES = Arrays.asList("CHAR", "VARCHAR", "VARCHAR2", "NVARCHAR2", "NVARCHAR", "TINYBLOB","TINYTEXT","BLOB","TEXT", "MEDIUMBLOB", "MEDIUMTEXT", "LONGBLOB", "LONGTEXT");
    protected JdbcConf jdbcConf;
    protected JdbcDialect jdbcDialect;

    protected transient Connection dbConn;
    protected transient FieldNamedPreparedStatement fieldNamedPreparedStatement;



    @Override
    public void initializeGlobal(int parallelism) {
        executeBatch(jdbcConf.getPreSql());
    }

    @Override
    public void finalizeGlobal(int parallelism) {
        executeBatch(jdbcConf.getPostSql());
    }

    @Override
    protected void openInternal(int taskNumber, int numTasks) {
        try {
            dbConn = getConnection();
            //???????????????????????????????????????????????????
            dbConn.setAutoCommit(false);
            initColumnList();
            if (!EWriteMode.INSERT.name().equalsIgnoreCase(jdbcConf.getMode())) {
                List<String> updateKey = jdbcConf.getUpdateKey();
                if (CollectionUtils.isEmpty(updateKey)) {
                    List<String> tableIndex =
                            JdbcUtil.getTableIndex(
                                    jdbcConf.getSchema(), jdbcConf.getTable(), dbConn);
                    jdbcConf.setUpdateKey(tableIndex);
                    LOG.info("updateKey = {}", JsonUtil.toPrintJson(tableIndex));
                }
            }

            fieldNamedPreparedStatement = FieldNamedPreparedStatement.prepareStatement(
                    dbConn,
                    prepareTemplates(),
                    this.columnNameList.toArray(new String[0]));

            LOG.info("subTask[{}}] wait finished", taskNumber);
        } catch (SQLException sqe) {
            throw new IllegalArgumentException("open() failed.", sqe);
        } finally {
            JdbcUtil.commit(dbConn);
        }
    }

    /**
     * init columnNameList??? columnTypeList and hasConstantField
     */
    protected void initColumnList() {
        Pair<List<String>, List<String>> pair = getTableMetaData();

        List<FieldConf> fieldList = jdbcConf.getColumn();
        List<String> fullColumnList = pair.getLeft();
        List<String> fullColumnTypeList = pair.getRight();
        handleColumnList(fieldList, fullColumnList, fullColumnTypeList);
    }

    /**
     * for override. because some databases have case-sensitive metadata???
     * @return
     */
    protected Pair<List<String>, List<String>> getTableMetaData() {
        return JdbcUtil.getTableMetaData(jdbcConf.getSchema(), jdbcConf.getTable(), dbConn);
    }

    /**
     * detailed logic for handling column
     * @param fieldList
     * @param fullColumnList
     * @param fullColumnTypeList
     */
    protected void handleColumnList(List<FieldConf> fieldList, List<String> fullColumnList, List<String> fullColumnTypeList) {
        if(fieldList.size() == 1 && Objects.equals(fieldList.get(0).getName(), "*")){
            columnNameList = fullColumnList;
            columnTypeList = fullColumnTypeList;
            return;
        }

        columnNameList = new ArrayList<>(fieldList.size());
        columnTypeList = new ArrayList<>(fieldList.size());
        for (FieldConf fieldConf : fieldList) {
            columnNameList.add(fieldConf.getName());
            for (int i = 0; i < fullColumnList.size(); i++) {
                if (fieldConf.getName().equalsIgnoreCase(fullColumnList.get(i))) {
                    columnTypeList.add(fullColumnTypeList.get(i));
                    break;
                }
            }
        }
    }

    @Override
    protected void writeSingleRecordInternal(RowData row) throws WriteRecordException {
        int index = 0;
        try {
            fieldNamedPreparedStatement = (FieldNamedPreparedStatement) rowConverter.toExternal(row, this.fieldNamedPreparedStatement);
            fieldNamedPreparedStatement.execute();
            JdbcUtil.commit(dbConn);
        } catch (Exception e) {
            JdbcUtil.rollBack(dbConn);
            processWriteException(e, index, row);
        }
    }

    @Override
    protected String recordConvertDetailErrorMessage(int pos, Object row) {
        return "\nJdbcOutputFormat [" + jobName + "] writeRecord error: when converting field["+ pos + "] in Row(" + row + ")";
    }

    @Override
    protected void writeMultipleRecordsInternal() throws Exception {
        try {
            for (RowData row : rows) {
                fieldNamedPreparedStatement = (FieldNamedPreparedStatement) rowConverter.toExternal(row, this.fieldNamedPreparedStatement);
                fieldNamedPreparedStatement.addBatch();
                lastRow = row;
            }
            fieldNamedPreparedStatement.executeBatch();
            // ?????????cp????????????????????????2pc???????????????????????????
            if (checkpointEnabled && CheckpointingMode.EXACTLY_ONCE == checkpointMode) {
                rowsOfCurrentTransaction += rows.size();
            } else {
                //??????????????????
                JdbcUtil.commit(dbConn);
            }
        } catch (Exception e) {
            LOG.warn("write Multiple Records error, start to rollback connection, row size = {}, first row = {}",
                    rows.size(),
                    rows.size() > 0 ? GsonUtil.GSON.toJson(rows.get(0)) : "null",
                    e);
            JdbcUtil.rollBack(dbConn);
            throw e;
        } finally {
            //??????????????????batch
            fieldNamedPreparedStatement.clearBatch();
        }
    }

    @Override
    public void preCommit() throws Exception{
        if (jdbcConf.getRestoreColumnIndex() > -1) {
            Object state;
            if(lastRow instanceof GenericRowData){
                state = ((GenericRowData) lastRow).getField(jdbcConf.getRestoreColumnIndex());
            }else if(lastRow instanceof ColumnRowData){
                state = ((ColumnRowData) lastRow).getField(jdbcConf.getRestoreColumnIndex()).asString();
            }else{
                LOG.warn("can't get [{}] from lastRow:{}", jdbcConf.getRestoreColumn(), lastRow);
                state = null;
            }
            formatState.setState(state);
        }

        if (rows != null && rows.size() > 0) {
            super.writeRecordInternal();
        } else {
            fieldNamedPreparedStatement.executeBatch();
        }
    }

    @Override
    public void commit(long checkpointId) throws Exception {
        try {
            dbConn.commit();
            snapshotWriteCounter.add(rowsOfCurrentTransaction);
            rowsOfCurrentTransaction = 0;
            fieldNamedPreparedStatement.clearBatch();
        } catch (Exception e) {
            dbConn.rollback();
            throw e;
        }
    }

    @Override
    public void rollback(long checkpointId) throws Exception {
        dbConn.rollback();
    }

    /**
     * ??????pre???post SQL
     * @param sqlList
     */
    protected void executeBatch(List<String> sqlList) {
        if(CollectionUtils.isNotEmpty(sqlList)){
            try(Connection conn = getConnection();
                Statement stmt = conn.createStatement()
            ){
                for (String sql : sqlList) {
                    //????????????SQL????????????????????????
                    String[] strings = sql.split(";");
                    for (String s : strings) {
                        if(StringUtils.isNotBlank(s)){
                            LOG.info("add sql to batch, sql = {}", sql);
                            stmt.addBatch(sql);
                        }
                    }
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                LOG.error("execute sql failed, sqlList = {}, e = {}", JsonUtil.toPrintJson(sqlList), e);
            }
        }
    }

    protected String prepareTemplates() {
        String singleSql;
        if (EWriteMode.INSERT.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect.getInsertIntoStatement(
                            jdbcConf.getSchema(),
                            jdbcConf.getTable(),
                            columnNameList.toArray(new String[0]));
        } else if (EWriteMode.REPLACE.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect
                            .getReplaceStatement(
                                    jdbcConf.getSchema(),
                                    jdbcConf.getTable(),
                                    columnNameList.toArray(new String[0]))
                            .get();
        } else if (EWriteMode.UPDATE.name().equalsIgnoreCase(jdbcConf.getMode())) {
            singleSql =
                    jdbcDialect
                            .getUpsertStatement(
                                    jdbcConf.getSchema(),
                                    jdbcConf.getTable(),
                                    columnNameList.toArray(new String[0]),
                                    jdbcConf.getUpdateKey().toArray(new String[0]),
                                    jdbcConf.isAllReplace())
                            .get();
        } else {
            throw new IllegalArgumentException("Unknown write mode:" + jdbcConf.getMode());
        }

        LOG.info("write sql:{}", singleSql);
        return singleSql;
    }

    protected void processWriteException(
            Exception e,
            int index,
            RowData row) throws WriteRecordException {
        if (e instanceof SQLException) {
            if (e.getMessage().contains("No operations allowed")) {
                throw new RuntimeException("Connection maybe closed", e);
            }
        }

        if (index < row.getArity()) {
            String message = recordConvertDetailErrorMessage(index, row);
            LOG.error(message, e);
            throw new WriteRecordException(message, e, index, row);
        }
        throw new WriteRecordException(e.getMessage(), e);
    }

    /**
     * ????????????????????????value
     *
     * todo ??????????????????
     *
     * @param row
     * @param index
     *
     * @return
     */
    protected Object getField(Row row, int index) {
        Object field = row.getField(index);
        String type = columnTypeList.get(index);

        //field??????????????????????????????????????????????????????????????????????????????object?????????null
        if (field instanceof String
                && StringUtils.isBlank((String) field)
                && !STRING_TYPES.contains(type.toUpperCase(Locale.ENGLISH))) {
            return null;
        }

        if (type.matches(DateUtil.DATE_REGEX)) {
            field = DateUtil.columnToDate(field, null);
        } else if (type.matches(DateUtil.DATETIME_REGEX)
                || type.matches(DateUtil.TIMESTAMP_REGEX)) {
            field = DateUtil.columnToTimestamp(field, null);
        }

        if (type.equalsIgnoreCase(ColumnType.BIGINT.name()) && field instanceof java.util.Date) {
            field = ((java.util.Date) field).getTime();
        }

        return field;
    }

    @Override
    public void closeInternal() {
        snapshotWriteCounter.add(rowsOfCurrentTransaction);
        try {
            if(fieldNamedPreparedStatement != null){
                fieldNamedPreparedStatement.close();
            }
        } catch (SQLException e) {
            LOG.error(ExceptionUtil.getErrorMessage(e));
        }
        JdbcUtil.closeDbResources(null, null, dbConn, true);
    }

    /**
     * ??????????????????????????????????????????
     * @return connection
     */
    protected Connection getConnection() {
        return JdbcUtil.getConnection(jdbcConf, jdbcDialect);
    }

    public JdbcConf getJdbcConf() {
        return jdbcConf;
    }

    public void setJdbcConf(JdbcConf jdbcConf) {
        this.jdbcConf = jdbcConf;
    }

    public void setJdbcDialect(JdbcDialect jdbcDialect) {
        this.jdbcDialect = jdbcDialect;
    }
}
