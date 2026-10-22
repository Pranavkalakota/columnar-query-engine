package columnar.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import columnar.differential.DuckDbOracle;
import columnar.exec.row.BatchRow;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RowEvaluatorTest {

    private static final Schema BOOLS = Schema.of(
            Field.of("a", DataType.BOOLEAN), Field.of("b", DataType.BOOLEAN));
    private static final Boolean[] VALUES = {Boolean.TRUE, Boolean.FALSE, null};

    private static String sql(Boolean v) {
        return v == null ? "CAST(NULL AS BOOLEAN)" : v.toString();
    }
    private static List<Object> evaluateAll(BinaryOp op) {
        VectorBatch batch = VectorBatch.allocate(BOOLS, 9);
        int row = 0;
        for (Boolean a : VALUES) {
            for (Boolean b : VALUES) {
                set(batch, 0, row, a);
                set(batch, 1, row, b);
                row++;
            }
        }
        batch.setRowCount(9);
        RowEvaluator evaluator = new RowEvaluator(Expr.col("a").binary(op, Expr.col("b")), BOOLS);
        BatchRow cursor = new BatchRow(batch);
        List<Object> results = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            cursor.position(i);
            results.add(evaluator.evaluate(cursor));
        }
        return results;
    }

    private static void set(VectorBatch batch, int column, int row, Boolean value) {
        if (value == null) {
            batch.column(column).setNull(row);
        } else {
            ((BooleanVector) batch.column(column)).set(row, value);
        }
    }

    private static List<Object> oracleAll(DuckDbOracle oracle, BinaryOp op) {
        List<Object> expected = new ArrayList<>();
        for (Boolean a : VALUES) {
            for (Boolean b : VALUES) {
                expected.add(oracle.query("SELECT %s %s %s".formatted(sql(a), op, sql(b)))
                        .rows().get(0).get(0));
            }
        }
        return expected;
    }

    @Test
    @DisplayName("three-valued AND and OR match DuckDB across all nine combinations")
    void threeValuedLogicMatchesTheOracle() {
        Expr and = Expr.col("a").binary(BinaryOp.AND, Expr.col("b"));
        assertEquals(DataType.BOOLEAN, new RowEvaluator(and, BOOLS).type());
        try (DuckDbOracle oracle = DuckDbOracle.inMemory()) {
            for (BinaryOp op : new BinaryOp[] {BinaryOp.AND, BinaryOp.OR}) {
                assertEquals(oracleAll(oracle, op), evaluateAll(op), op.toString());
            }
        }
    }

    @Test
    @DisplayName("the decisive operand wins even when the other side is NULL")
    void decisiveOperandWinsOverNull() {
        List<Object> and = evaluateAll(BinaryOp.AND);
        List<Object> or = evaluateAll(BinaryOp.OR);
        assertNull(and.get(2), "TRUE AND NULL is NULL");
        assertEquals(false, and.get(7), "NULL AND FALSE is FALSE, not NULL");
        assertEquals(true, or.get(6), "NULL OR TRUE is TRUE, not NULL");
        assertNull(or.get(7), "NULL OR FALSE is NULL");
        assertNull(and.get(8), "NULL AND NULL is NULL");
    }
}
