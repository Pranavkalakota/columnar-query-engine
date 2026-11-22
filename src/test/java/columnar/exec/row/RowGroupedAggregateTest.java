package columnar.exec.row;

import static org.junit.jupiter.api.Assertions.assertEquals;

import columnar.exec.agg.Aggregate;
import columnar.exec.sort.SortKey;
import columnar.storage.InMemoryTable;
import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.IntVector;
import columnar.vector.VectorBatch;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RowGroupedAggregateTest {

    private static InMemoryTable days(int... epochDays) {
        Schema schema = Schema.of(Field.of("day", DataType.DATE));
        VectorBatch batch = VectorBatch.allocate(schema, epochDays.length);
        for (int i = 0; i < epochDays.length; i++) {
            ((IntVector) batch.column(0)).set(i, epochDays[i]);
        }
        batch.setRowCount(epochDays.length);
        return InMemoryTable.of(schema, List.of(batch));
    }

    @Test
    @DisplayName("a DATE key still reads as an int, so a sort can sit on top")
    void dateKeysStayInts() {
        InMemoryTable table = days(9003, 9001, 9003, 9002, 9001, 9003);
        RowGroupedAggregate grouped = new RowGroupedAggregate(new RowScan(table),
                List.of("day"), List.of(Aggregate.countStar("n")));
        List<Integer> order = new ArrayList<>();
        List<Object> shown = new ArrayList<>();
        try (RowSort sort = new RowSort(grouped, List.of(SortKey.asc("day")))) {
            sort.open();
            Row row;
            while ((row = sort.next()) != null) {
                order.add(row.getInt(0));
                shown.add(row.get(0));
            }
        }
        assertEquals(List.of(9001, 9002, 9003), order);
        assertEquals(LocalDate.ofEpochDay(9001), shown.get(0), "get() still shows a LocalDate");
    }
}
