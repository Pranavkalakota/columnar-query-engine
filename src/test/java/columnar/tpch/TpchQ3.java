package columnar.tpch;

import columnar.exec.agg.Aggregate;
import columnar.exec.row.RowFilter;
import columnar.exec.row.RowGroupedAggregate;
import columnar.exec.row.RowHashJoin;
import columnar.exec.row.RowLimit;
import columnar.exec.row.RowOperator;
import columnar.exec.row.RowSort;
import columnar.exec.sort.SortKey;
import columnar.exec.vec.VectorFilter;
import columnar.exec.vec.VectorGroupedAggregate;
import columnar.exec.vec.VectorHashJoin;
import columnar.exec.vec.VectorLimit;
import columnar.exec.vec.VectorOperator;
import columnar.exec.vec.VectorScan;
import columnar.exec.vec.VectorSort;
import columnar.expr.BinaryOp;
import columnar.expr.Expr;
import columnar.storage.InMemoryTable;
import java.util.List;

/** TPC-H Q3 as a plan, shared by test and benchmark. */
public final class TpchQ3 {

    private TpchQ3() {
    }

    public static final List<String> COLUMNS = List.of("l_orderkey", "o_orderdate", "o_shippriority", "revenue");

    /** Q3 with revenue moved last, where grouping puts it. */
    public static final String SQL = """
            SELECT l_orderkey, o_orderdate, o_shippriority,
                   sum(l_extendedprice * (1 - l_discount)) AS revenue
            FROM customer, orders, lineitem
            WHERE c_mktsegment = 'BUILDING'
              AND c_custkey = o_custkey AND l_orderkey = o_orderkey
              AND o_orderdate < DATE '1995-03-15' AND l_shipdate > DATE '1995-03-15'
            GROUP BY l_orderkey, o_orderdate, o_shippriority
            """;

    public static final String ORDERED_SQL = SQL + "ORDER BY revenue DESC, o_orderdate\nLIMIT 10\n";

    private static final List<String> KEYS = List.of("l_orderkey", "o_orderdate", "o_shippriority");
    private static final List<SortKey> ORDER = List.of(SortKey.desc("revenue"), SortKey.asc("o_orderdate"));

    private static Expr building() {
        return Expr.col("c_mktsegment").binary(BinaryOp.EQUAL, Expr.lit("BUILDING"));
    }

    private static Expr orderedBefore() {
        return Expr.col("o_orderdate").binary(BinaryOp.LESS, Expr.date("1995-03-15"));
    }

    private static Expr shippedAfter() {
        return Expr.col("l_shipdate").binary(BinaryOp.GREATER, Expr.date("1995-03-15"));
    }

    private static List<Aggregate> revenue() {
        Expr discounted = Expr.col("l_extendedprice").binary(BinaryOp.MULTIPLY,
                Expr.lit(1.0).binary(BinaryOp.SUBTRACT, Expr.col("l_discount")));
        return List.of(Aggregate.sum(discounted, "revenue"));
    }

    /** Everything up to the GROUP BY, before sorting and limiting. */
    public static VectorOperator vectorGrouped(InMemoryTable customer, InMemoryTable orders,
            InMemoryTable lineitem, int batchSize) {
        VectorOperator customers = new VectorFilter(new VectorScan(customer), building());
        VectorOperator early = new VectorFilter(new VectorScan(orders), orderedBefore());
        VectorOperator withCustomer = new VectorHashJoin(early, customers,
                List.of("o_custkey"), List.of("c_custkey"), batchSize);
        VectorOperator late = new VectorFilter(new VectorScan(lineitem), shippedAfter());
        VectorOperator joined = new VectorHashJoin(late, withCustomer,
                List.of("l_orderkey"), List.of("o_orderkey"), batchSize);
        return new VectorGroupedAggregate(joined, KEYS, revenue(), batchSize);
    }

    public static VectorOperator vector(InMemoryTable customer, InMemoryTable orders,
            InMemoryTable lineitem, int batchSize) {
        return new VectorLimit(new VectorSort(vectorGrouped(customer, orders, lineitem, batchSize),
                ORDER, batchSize), 10);
    }

    /** The row plan, over whichever scans the caller picks. */
    public static RowOperator rowGrouped(RowOperator customer, RowOperator orders, RowOperator lineitem) {
        RowOperator customers = new RowFilter(customer, building());
        RowOperator early = new RowFilter(orders, orderedBefore());
        RowOperator withCustomer = new RowHashJoin(early, customers, List.of("o_custkey"), List.of("c_custkey"));
        RowOperator late = new RowFilter(lineitem, shippedAfter());
        RowOperator joined = new RowHashJoin(late, withCustomer, List.of("l_orderkey"), List.of("o_orderkey"));
        return new RowGroupedAggregate(joined, KEYS, revenue());
    }

    public static RowOperator row(RowOperator customer, RowOperator orders, RowOperator lineitem) {
        return new RowLimit(new RowSort(rowGrouped(customer, orders, lineitem), ORDER), 10);
    }
}
