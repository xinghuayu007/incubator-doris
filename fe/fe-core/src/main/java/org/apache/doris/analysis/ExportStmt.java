// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.catalog.Catalog;
import org.apache.doris.catalog.Database;
import org.apache.doris.catalog.FsBroker;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.Table;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.ErrorCode;
import org.apache.doris.common.ErrorReport;
import org.apache.doris.common.UserException;
import org.apache.doris.common.util.PrintableMap;
import org.apache.doris.common.util.PropertyAnalyzer;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.thrift.TFileFormatType;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

// EXPORT statement, export data to dirs by broker.
//
// syntax:
//      EXPORT TABLE tablename [PARTITION (name1[, ...])]
//          TO 'export_target_path'
//          [PROPERTIES("key"="value")]
//          BY BROKER 'broker_name' [( $broker_attrs)]
public class ExportStmt extends StatementBase {
    private final static Logger LOG = LogManager.getLogger(ExportStmt.class);

    public static final String TABLET_NUMBER_PER_TASK_PROP = "tablet_num_per_task";

    private static final String DEFAULT_COLUMN_SEPARATOR = "\t";
    private static final String DEFAULT_LINE_DELIMITER = "\n";
    private static final String DEFAULT_COLUMNS = "";
    private static final String FILE_FORMAT = "format";
    private static final String SCHEMA = "schema";
    private static final String PARQUET_PROP_PREFIX = "parquet.";
    private static final List<String> PARQUET_REPETITION_TYPES = Lists.newArrayList();
    private static final List<String> PARQUET_DATA_TYPES = Lists.newArrayList();

    private TableName tblName;
    private List<String> partitions;
    private Expr whereExpr;
    private String path;
    private BrokerDesc brokerDesc;
    private Map<String, String> properties = Maps.newHashMap();
    private String columnSeparator;
    private String lineDelimiter;
    private String columns ;
    private TFileFormatType format;
    private List<List<String>> schema = new ArrayList<>();
    private Map<String, String> fileProperties = Maps.newHashMap();

    private TableRef tableRef;

    static {
        PARQUET_REPETITION_TYPES.add("required");
        PARQUET_REPETITION_TYPES.add("repeated");
        PARQUET_REPETITION_TYPES.add("optional");

        PARQUET_DATA_TYPES.add("boolean");
        PARQUET_DATA_TYPES.add("int32");
        PARQUET_DATA_TYPES.add("int64");
        PARQUET_DATA_TYPES.add("int96");
        PARQUET_DATA_TYPES.add("byte_array");
        PARQUET_DATA_TYPES.add("float");
        PARQUET_DATA_TYPES.add("double");
        PARQUET_DATA_TYPES.add("fixed_len_byte_array");
    }

    public ExportStmt(TableRef tableRef, Expr whereExpr, String path,
                      Map<String, String> properties, BrokerDesc brokerDesc) {
        this.tableRef = tableRef;
        this.whereExpr = whereExpr;
        this.path = path.trim();
        if (properties != null) {
            this.properties = properties;
        }
        this.brokerDesc = brokerDesc;
        this.columnSeparator = DEFAULT_COLUMN_SEPARATOR;
        this.lineDelimiter = DEFAULT_LINE_DELIMITER;
        this.columns = DEFAULT_COLUMNS;
    }

    public String getColumns() {
        return columns;
    }

    public TableName getTblName() {
        return tblName;
    }

    public List<String> getPartitions() {
        return partitions;
    }

    public Expr getWhereExpr() {
        return whereExpr;
    }

    public String getPath() {
        return path;
    }

    public BrokerDesc getBrokerDesc() {
        return brokerDesc;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public String getColumnSeparator() {
        return this.columnSeparator;
    }

    public TFileFormatType getFileFormat() {
        return this.format;
    }

    public List<List<String>> getSchema() {
        return this.schema;
    }

    public Map<String, String> getFileProperties() {
        return this.fileProperties;
    }

    public String getLineDelimiter() {
        return this.lineDelimiter;
    }

    @Override
    public boolean needAuditEncryption() {
        if (brokerDesc != null) {
            return true;
        }
        return false;
    }

    @Override
    public void analyze(Analyzer analyzer) throws UserException {
        super.analyze(analyzer);

        tableRef = analyzer.resolveTableRef(tableRef);
        Preconditions.checkNotNull(tableRef);
        tableRef.analyze(analyzer);

        this.tblName = tableRef.getName();

        PartitionNames partitionNames = tableRef.getPartitionNames();
        if (partitionNames != null) {
            if (partitionNames.isTemp()) {
                throw new AnalysisException("Do not support exporting temporary partitions");
            }
            partitions = partitionNames.getPartitionNames();
        }

        // check auth
        if (!Catalog.getCurrentCatalog().getAuth().checkTblPriv(ConnectContext.get(),
                                                                tblName.getDb(), tblName.getTbl(),
                                                                PrivPredicate.SELECT)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_TABLEACCESS_DENIED_ERROR, "EXPORT",
                                                ConnectContext.get().getQualifiedUser(),
                                                ConnectContext.get().getRemoteIP(),
                    tblName.getTbl());
        }

        // check table && partitions whether exist
        checkTable(analyzer.getCatalog());

        // check broker whether exist
        if (brokerDesc == null) {
            brokerDesc = new BrokerDesc("local", StorageBackend.StorageType.LOCAL, null);
        }

        // where expr will be checked in export job

        // check path is valid
        path = checkPath(path, brokerDesc.getStorageType());
        if (brokerDesc.getStorageType() == StorageBackend.StorageType.BROKER) {
            if (!analyzer.getCatalog().getBrokerMgr().containsBroker(brokerDesc.getName())) {
                throw new AnalysisException("broker " + brokerDesc.getName() + " does not exist");
            }

            FsBroker broker = analyzer.getCatalog().getBrokerMgr().getAnyBroker(brokerDesc.getName());
            if (broker == null) {
                throw new AnalysisException("failed to get alive broker");
            }
        }

        // check properties
        checkProperties(properties);
    }

    private void checkTable(Catalog catalog) throws AnalysisException {
        Database db = catalog.getDb(tblName.getDb());
        if (db == null) {
            throw new AnalysisException("Db does not exist. name: " + tblName.getDb());
        }

        Table table = db.getTable(tblName.getTbl());
        if (table == null) {
            throw new AnalysisException("Table[" + tblName.getTbl() + "] does not exist");
        }

        table.readLock();
        try {
            if (partitions == null) {
                return;
            }
            if (!table.isPartitioned()) {
                throw new AnalysisException("Table[" + tblName.getTbl() + "] is not partitioned.");
            }
            Table.TableType tblType = table.getType();
            switch (tblType) {
                case MYSQL:
                case ODBC:
                case OLAP:
                    break;
                case BROKER:
                case SCHEMA:
                case INLINE_VIEW:
                case VIEW:
                default:
                    throw new AnalysisException("Table[" + tblName.getTbl() + "] is "
                            + tblType.toString() + " type, do not support EXPORT.");
            }

            for (String partitionName : partitions) {
                Partition partition = table.getPartition(partitionName);
                if (partition == null) {
                    throw new AnalysisException("Partition [" + partitionName + "] does not exist");
                }
            }
        } finally {
            table.readUnlock();
        }
    }

    public static String checkPath(String path, StorageBackend.StorageType type) throws AnalysisException {
        if (Strings.isNullOrEmpty(path)) {
            throw new AnalysisException("No dest path specified.");
        }

        try {
            URI uri = new URI(path);
            String schema = uri.getScheme();
            if (type == StorageBackend.StorageType.BROKER) {
                if (schema == null || (!schema.equalsIgnoreCase("bos") && !schema.equalsIgnoreCase("afs")
                    && !schema.equalsIgnoreCase("hdfs"))) {
                    throw new AnalysisException("Invalid export path. please use valid 'HDFS://', 'AFS://' or 'BOS://' path.");
                }
            } else if (type == StorageBackend.StorageType.S3) {
                if (schema == null || !schema.equalsIgnoreCase("s3")) {
                    throw new AnalysisException("Invalid export path. please use valid 'S3://' path.");
                }
            } else if (type == StorageBackend.StorageType.HDFS) {
                if (schema == null || !schema.equalsIgnoreCase("hdfs")) {
                    throw new AnalysisException("Invalid export path. please use valid 'HDFS://' path.");
                }
            } else if (type == StorageBackend.StorageType.LOCAL) {
                if (schema != null && !schema.equalsIgnoreCase("file")) {
                    throw new AnalysisException("Invalid export path. please use valid '"
                            + OutFileClause.LOCAL_FILE_PREFIX + "' path.");
                }
                path = path.substring(OutFileClause.LOCAL_FILE_PREFIX.length() - 1);
            }
        } catch (URISyntaxException e) {
            throw new AnalysisException("Invalid path format. " + e.getMessage());
        }
        return path;
    }

    private void checkProperties(Map<String, String> properties) throws UserException {
        this.columnSeparator = Separator.convertSeparator(PropertyAnalyzer.analyzeColumnSeparator(
                properties, ExportStmt.DEFAULT_COLUMN_SEPARATOR));
        this.lineDelimiter = Separator.convertSeparator(PropertyAnalyzer.analyzeLineDelimiter(
                properties, ExportStmt.DEFAULT_LINE_DELIMITER));
       this.columns = properties.get(LoadStmt.KEY_IN_PARAM_COLUMNS);
        // exec_mem_limit
        if (properties.containsKey(LoadStmt.EXEC_MEM_LIMIT)) {
            try {
                Long.parseLong(properties.get(LoadStmt.EXEC_MEM_LIMIT));
            } catch (NumberFormatException e) {
                throw new DdlException("Invalid exec_mem_limit value: " + e.getMessage());
            }
        } else {
            // use session variables
            properties.put(LoadStmt.EXEC_MEM_LIMIT,
                           String.valueOf(ConnectContext.get().getSessionVariable().getMaxExecMemByte()));
        }
        // timeout
        if (properties.containsKey(LoadStmt.TIMEOUT_PROPERTY)) {
            try {
                Long.parseLong(properties.get(LoadStmt.TIMEOUT_PROPERTY));
            } catch (NumberFormatException e) {
                throw new DdlException("Invalid timeout value: " + e.getMessage());
            }
        } else {
            // use session variables
            properties.put(LoadStmt.TIMEOUT_PROPERTY, String.valueOf(Config.export_task_default_timeout_second));
        }

        // tablet num per task
        if (properties.containsKey(TABLET_NUMBER_PER_TASK_PROP)) {
            try {
                Long.parseLong(properties.get(TABLET_NUMBER_PER_TASK_PROP));
            } catch (NumberFormatException e) {
                throw new DdlException("Invalid tablet num per task value: " + e.getMessage());
            }
        } else {
            // use session variables
            properties.put(TABLET_NUMBER_PER_TASK_PROP, String.valueOf(Config.export_tablet_num_per_task));
        }

        // parse format, default format is csv
        String format = "";
        if (properties.containsKey(FILE_FORMAT)) {
            format = properties.get(FILE_FORMAT);
        } else {
            format = "csv";
        }

        switch (format) {
            case "csv":
                this.format = TFileFormatType.FORMAT_CSV_PLAIN;
                break;
            case "parquet":
                this.format = TFileFormatType.FORMAT_PARQUET;
                break;
            default:
                throw new AnalysisException("format:" + format + " is not supported.");
        }

        if (this.format == TFileFormatType.FORMAT_PARQUET) {
            getParquetProperties();
        }
    }

    /**
     * example:
     * EXPORT TABLE table1 to "file:///root/doris/"
     * PROPERTIES ("format"="parquet", "schema"="required,int32,siteid;", "parquet.compression"="snappy");
     *
     * schema: it defined the schema of parquet file, it consists of 3 field: competition type, data type, column name
     * multiple columns is split by `;`
     *
     * prefix with 'parquet.' defines the properties of parquet file,
     * currently only supports: compression, disable_dictionary, version
     */
    private void getParquetProperties() throws AnalysisException {
        String schema = properties.get(SCHEMA);
        if (schema == null || schema.length() <= 0) {
            throw new AnalysisException("schema is required for parquet file");
        }
        schema = schema.replace(" ","");
        schema = schema.toLowerCase();
        String[] schemas = schema.split(";");
        for (String item:schemas) {
            String[] properties = item.split(",");
            if (properties.length != 3) {
                throw new AnalysisException("must only contains repetition type/column type/column name");
            }
            if (!PARQUET_REPETITION_TYPES.contains(properties[0])) {
                throw new AnalysisException("unknown repetition type");
            }
            if (!properties[0].equalsIgnoreCase("required")) {
                throw new AnalysisException("currently only support required type");
            }
            if (!PARQUET_DATA_TYPES.contains(properties[1])) {
                throw new AnalysisException("data type is not supported:"+properties[1]);
            }
            List<String> column = new ArrayList<>();
            column.addAll(Arrays.asList(properties));
            this.schema.add(column);
        }

        Iterator<Map.Entry<String, String>> iter = properties.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, String> entry = iter.next();
            if (entry.getKey().startsWith(PARQUET_PROP_PREFIX)) {
                fileProperties.put(entry.getKey().substring(PARQUET_PROP_PREFIX.length()), entry.getValue());
            }
        }
    }

    @Override
    public String toSql() {
        StringBuilder sb = new StringBuilder();
        sb.append("EXPORT TABLE ");
        if (tblName == null) {
            sb.append("non-exist");
        } else {
            sb.append(tblName.toSql());
        }
        if (partitions != null && !partitions.isEmpty()) {
            sb.append(" PARTITION (");
            Joiner.on(", ").appendTo(sb, partitions);
            sb.append(")");
        }
        sb.append("\n");

        sb.append(" TO ").append("'");
        sb.append(path);
        sb.append("'");

        if (properties != null && !properties.isEmpty()) {
            sb.append("\nPROPERTIES (");
            sb.append(new PrintableMap<String, String>(properties, "=", true, false));
            sb.append(")");
        }

        if (brokerDesc != null) {
            sb.append("\n WITH BROKER '").append(brokerDesc.getName()).append("' (");
            sb.append(new PrintableMap<String, String>(brokerDesc.getProperties(), "=", true, false, true));
            sb.append(")");
        }

        return sb.toString();
    }

    @Override
    public RedirectStatus getRedirectStatus() {
        return RedirectStatus.FORWARD_WITH_SYNC;
    }

    @Override
    public String toString() {
        return toSql();
    }
}
