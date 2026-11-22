package columnar.tpch;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;

/** TPC-H table layouts, with DECIMAL read as DOUBLE. */
public final class TpchSchemas {

    private TpchSchemas() {
    }

    public static final Schema CUSTOMER = Schema.of(
            Field.of("c_custkey", DataType.INT64), Field.of("c_name", DataType.VARCHAR),
            Field.of("c_address", DataType.VARCHAR), Field.of("c_nationkey", DataType.INT32),
            Field.of("c_phone", DataType.VARCHAR), Field.of("c_acctbal", DataType.DOUBLE),
            Field.of("c_mktsegment", DataType.VARCHAR), Field.of("c_comment", DataType.VARCHAR));

    public static final Schema ORDERS = Schema.of(
            Field.of("o_orderkey", DataType.INT64), Field.of("o_custkey", DataType.INT64),
            Field.of("o_orderstatus", DataType.VARCHAR), Field.of("o_totalprice", DataType.DOUBLE),
            Field.of("o_orderdate", DataType.DATE), Field.of("o_orderpriority", DataType.VARCHAR),
            Field.of("o_clerk", DataType.VARCHAR), Field.of("o_shippriority", DataType.INT32),
            Field.of("o_comment", DataType.VARCHAR));

    public static final Schema LINEITEM = Schema.of(
            Field.of("l_orderkey", DataType.INT64), Field.of("l_partkey", DataType.INT64),
            Field.of("l_suppkey", DataType.INT64), Field.of("l_linenumber", DataType.INT64),
            Field.of("l_quantity", DataType.DOUBLE), Field.of("l_extendedprice", DataType.DOUBLE),
            Field.of("l_discount", DataType.DOUBLE), Field.of("l_tax", DataType.DOUBLE),
            Field.of("l_returnflag", DataType.VARCHAR), Field.of("l_linestatus", DataType.VARCHAR),
            Field.of("l_shipdate", DataType.DATE), Field.of("l_commitdate", DataType.DATE),
            Field.of("l_receiptdate", DataType.DATE), Field.of("l_shipinstruct", DataType.VARCHAR),
            Field.of("l_shipmode", DataType.VARCHAR), Field.of("l_comment", DataType.VARCHAR));
}
