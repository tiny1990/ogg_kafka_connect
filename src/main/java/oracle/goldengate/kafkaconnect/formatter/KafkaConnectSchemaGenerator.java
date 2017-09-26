package oracle.goldengate.kafkaconnect.formatter;

import oracle.goldengate.datasource.meta.ColumnMetaData;
import oracle.goldengate.datasource.meta.DsType;
import oracle.goldengate.datasource.meta.TableMetaData;
import oracle.goldengate.kafkaconnect.DpConstants;
import oracle.jdbc.OracleType;

import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This class generates the Kafka Connect schema and caches the schemas for
 * reuse.
 *
 * @author tbcampbe
 */
public class KafkaConnectSchemaGenerator {
    private static final Logger logger = LoggerFactory.getLogger(KafkaConnectSchemaGenerator.class);

    private Map<String, KeyAndPayloadSchemas> schemaMap = new HashMap();
    private boolean treatAllColumnsAsStrings = false;

    /**
     * Method to set to treat all columns as strings.
     */
    public void setTreatAllColumnsAsStrings(boolean allColumnsAsStrings) {
        treatAllColumnsAsStrings = allColumnsAsStrings;
    }

    /**
     * Method to get the  schema.  If a schema is not available it will be
     * generated.
     *
     * @param tableName The fully qualified table name.
     * @param tmeta     The table metadata object.
     * @return An object holding the key and value schemas.
     */
    public KeyAndPayloadSchemas getSchema(String tableName, TableMetaData tmeta) {
        KeyAndPayloadSchemas schemas = schemaMap.get(tableName);
        if (schemas == null) {
            logger.info("Building the key and payload schemas for source table [" + tableName + "]");
            schemas = new KeyAndPayloadSchemas();
            //Generate the Kafka key schema
            Schema keySchema = generateKeySchema(tableName, tmeta);
            //Log the key schema if debug logging enabled.
            logSchema(keySchema);
            schemas.setKeySchema(keySchema);
            //Generate the Kafka value schema
            Schema payloadSchema = generatePayloadSchema(tableName, tmeta);
            //Log the payload schema if debug logging is enabled.
            logSchema(payloadSchema);
            schemas.setPayloadSchema(payloadSchema);
            schemaMap.put(tableName, schemas);
        }

        return schemas;
    }

    /**
     * Method to drop an already created schema in the event of a metadata change
     * event.
     *
     * @param tableName The fully qualified table name.
     */
    public void dropSchema(String tableName) {
        schemaMap.remove(tableName);
    }

    private Schema generateKeySchema(String tableName, TableMetaData tmeta) {
        logger.info("Generating key schema for table [" + tableName + "].");
        Schema keySchema = null;
        if (tmeta.getNumKeyColumns() < 1) {
            logger.info("The source table [" + tableName + "] contains no primary keys.  The key schema will be null.");
        } else {
            logger.info("The source table [" + tableName + "] contains one or more primary keys.");
            SchemaBuilder builder = SchemaBuilder.struct().name(tableName + "_key");
            for (int col = 0; col < tmeta.getNumColumns(); col++) {
                ColumnMetaData cmeta = tmeta.getColumnMetaData(col);
                if (cmeta.isKeyCol()) {
                    addFieldSchema(cmeta, builder);
                }
            }
            //Key schema should be done
            keySchema = builder.schema();

        }
        //May return null if the source table has no primary key
        return keySchema;
    }

    private Schema generatePayloadSchema(String tableName, TableMetaData tmeta) {
        logger.info("Generating payload schema for table [" + tableName + "]");
        SchemaBuilder builder = SchemaBuilder.struct().name(tableName);

        //Add a field for the table name
        builder.field("table", Schema.STRING_SCHEMA);
        builder.field("op_type", Schema.STRING_SCHEMA);
        builder.field("op_ts", Schema.STRING_SCHEMA);
        builder.field("current_ts", Schema.STRING_SCHEMA);
        builder.field("pos", Schema.STRING_SCHEMA);

        //An array field for primary key column names could be added here
        //A map field for token values from the source trail file could be added here.

        //after
        SchemaBuilder after = SchemaBuilder.struct().name("after");
        after.optional();
        for (int col = 0; col < tmeta.getNumColumns(); col++) {
            ColumnMetaData cmeta = tmeta.getColumnMetaData(col);
            addFieldSchema(cmeta, after);
        }
        //before
        SchemaBuilder before = SchemaBuilder.struct().name("before");
        before.optional();
        for (int col = 0; col < tmeta.getNumColumns(); col++) {
            ColumnMetaData cmeta = tmeta.getColumnMetaData(col);
            addFieldSchema(cmeta, before);
        }

        //source
        SchemaBuilder source = SchemaBuilder.struct().name("source");
//        source.optional();
        source.field(DpConstants.RECORD_OFFSET_ENTITY_KEY, Schema.STRING_SCHEMA);
        source.field(DpConstants.RECORD_OFFSET_TOTAL_SIZE_KEY, Schema.INT64_SCHEMA);
        source.field(DpConstants.SNAPSHOT_LASTONE_KEY, Schema.BOOLEAN_SCHEMA);
        source.field(DpConstants.RECORD_SOURCE_ISINCREMENT, Schema.BOOLEAN_SCHEMA);
        source.field(DpConstants.RECORD_OFFSET_INDEX_KEY, Schema.OPTIONAL_INT64_SCHEMA);
        source.field(DpConstants.DATA_KEY_BINLOG_TS, Schema.OPTIONAL_INT64_SCHEMA);

        builder.field(DpConstants.DATA_KEY_AFTER, after.build());
        builder.field(DpConstants.DATA_KEY_BEFORE, before.build());
        builder.field(DpConstants.DATA_KEY_SOURCE, source.build());
        return builder.build();
    }

    private void addFieldSchema(ColumnMetaData cmeta, SchemaBuilder builder) {
        String fieldName = cmeta.getColumnName();

        if (treatAllColumnsAsStrings) {
            //Treat it as a string
            builder.field(fieldName, Schema.OPTIONAL_STRING_SCHEMA);
        } else {
            int type = cmeta.getDataType().getJDBCType();

            long displaySize = cmeta.getBinaryLength();
            int scale = cmeta.getDataType().getScale() == -127 ? 127 : cmeta.getDataType().getScale();
            long precision = cmeta.getDataType().getPrecision();
            System.out.println(cmeta.getColumnName() + " " + type + " " + cmeta.getDataType().toString() + " " + precision + " " + scale + " " + displaySize);


            Schema schema;
            switch (cmeta.getDataType().toString()) {
                case "TIMESTAMP":
                    schema = SchemaBuilder.type(Schema.Type.STRING).optional()
                        .parameter(DpConstants.DATA_LENGTH, "26")
                        .name(DpConstants.DATETIME_SCHEMA_NAME)
                        .build();
                    break;
                case "VARCHAR":
                    schema = SchemaBuilder.type(Schema.Type.STRING).optional()
                        .parameter(DpConstants.DATA_LENGTH, displaySize + "")
                        .name("VARCHAR")
                        .build();
                    break;
                case "DOUBLE":
                    break;



                default:
                    schema = SchemaBuilder.type(Schema.Type.STRING).optional().build();
            }
//                case FLOAT:
//                case BINARY_FLOAT:
//                    schema = SchemaBuilder.type(Schema.Type.FLOAT32).optional()
//                        .build();
//                    break;
//                case BINARY_DOUBLE:
//                    schema = SchemaBuilder.type(Schema.Type.FLOAT64).optional()
//                        .build();
//                    break;
//                case NUMBER:
//                    schema = Decimal.builder(scale)
//                        .parameter(DpConstants.DATA_LENGTH, precision + "")
//                        .optional().build();
//                    break;
//                case INTERVAL_YEAR_TO_MONTH:
//                case INTERVAL_DAY_TO_SECOND:
//                    schema = SchemaBuilder.type(Schema.Type.STRING).optional()
//                        .parameter(DpConstants.DATA_LENGTH, "26")
//                        .build();
//                    break;
//                case CLOB:
//                case NCLOB:
//                case BLOB:
//                case BFILE:
//                    schema = SchemaBuilder.type(Schema.Type.BYTES).optional()
//                        .build();
//                    break;
//                case VARCHAR2:
//                case NVARCHAR:
//                case CHAR:
//                case NCHAR:
//                case LONG:
//                    schema = SchemaBuilder.type(Schema.Type.STRING).optional()
//                        .parameter(DpConstants.DATA_LENGTH, displaySize + "")
//                        .build();
//                    break;
//                case ANYTYPE:
//                case OBJECT:
//                case RAW:
//                case LONG_RAW:
//                case ROWID:
//                case UROWID:
//                case REF:
//                case PLSQL_BOOLEAN:
//                case VARRAY:
//                case NESTED_TABLE:
//                case ANYDATA:
//                case ANYDATASET:
//                case XMLTYPE:
//                case HTTPURITYPE:
//                case XDBURITYPE:
//                case DBURITYPE:
//                case SDO_GEOMETRY:
//                case SDO_TOPO_GEOMETRY:
//                case SDO_GEORASTER:
//                case ORDAUDIO:
//                case ORDDICOM:
//                case ORDDOC:
//                case ORDIMAGE:
//                case ORDVIDEO:
//                case SI_AVERAGE_COLOR:
//                case SI_COLOR:
//                case SI_COLOR_HISTOGRAM:
//                case SI_FEATURE_LIST:
//                case SI_POSITIONAL_COLOR:
//                case SI_STILL_IMAGE:
//                case SI_TEXTURE:

//
            builder.field(fieldName, schema);

        }

    }

    /**
     * A utility method to log the contents of a schema just for debugging.
     *
     * @param s The schema to be logged.
     */

    private void logSchema(Schema s) {
        if ((logger.isDebugEnabled()) && (s != null)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Kafka Connect Schema [");
            sb.append(s.name());
            sb.append("]");
            sb.append(System.lineSeparator());
            List<Field> fields = s.fields();
            for (Field field : fields) {
                sb.append("  Field [");
                sb.append(field.name());
                sb.append("] Type [");
                sb.append(field.schema().toString());
                sb.append("]");
                sb.append(System.lineSeparator());
            }
            logger.debug(sb.toString());
        }
    }

}
