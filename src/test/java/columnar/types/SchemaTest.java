package columnar.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaTest {

    private static final Schema LINEITEM = Schema.of(
            Field.of("l_orderkey", DataType.INT64),
            Field.of("l_quantity", DataType.DOUBLE),
            Field.of("l_shipdate", DataType.DATE),
            Field.of("l_returnflag", DataType.VARCHAR));

    @Test
    @DisplayName("columns are addressable by position and by name")
    void basicAccess() {
        assertEquals(4, LINEITEM.size());
        assertEquals(DataType.DOUBLE, LINEITEM.type(1));
        assertEquals("l_shipdate", LINEITEM.field(2).name());
        assertEquals(1, LINEITEM.indexOf("l_quantity"));
        assertEquals(List.of("l_orderkey", "l_quantity", "l_shipdate", "l_returnflag"), LINEITEM.names());
    }

    @Test
    @DisplayName("lookup ignores case, because unquoted SQL identifiers do")
    void lookupIsCaseInsensitive() {
        assertEquals(0, LINEITEM.indexOf("L_ORDERKEY"));
        assertEquals(0, LINEITEM.indexOf("L_OrderKey"));
        assertTrue(LINEITEM.contains("l_ORDERKEY"));
    }

    @Test
    @DisplayName("an unknown column is -1 from indexOf and an error from requireIndexOf")
    void unknownColumn() {
        assertEquals(-1, LINEITEM.indexOf("nope"));
        assertFalse(LINEITEM.contains("nope"));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> LINEITEM.requireIndexOf("nope"));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    @DisplayName("a duplicated name is ambiguous, not resolved to the first match")
    void duplicateNameIsAmbiguous() {
        Schema joined = Schema.of(
                Field.of("c", DataType.INT32),
                Field.of("other", DataType.INT32),
                Field.of("C", DataType.INT64));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> joined.indexOf("c"));
        assertTrue(e.getMessage().contains("ambiguous"));
        assertFalse(joined.contains("c"), "an ambiguous name is not 'present'");
        // Unambiguous names in the same schema still resolve.
        assertEquals(1, joined.indexOf("other"));
    }

    @Test
    @DisplayName("project keeps only the named columns, in the order requested")
    void projectReorders() {
        Schema pruned = LINEITEM.project(List.of("l_shipdate", "l_orderkey"));
        assertEquals(2, pruned.size());
        assertEquals(List.of("l_shipdate", "l_orderkey"), pruned.names());
        assertEquals(DataType.DATE, pruned.type(0));
        assertEquals(0, pruned.indexOf("l_shipdate"));
    }

    @Test
    @DisplayName("projecting a column that is not there fails rather than dropping it")
    void projectRejectsUnknown() {
        assertThrows(IllegalArgumentException.class, () -> LINEITEM.project(List.of("nope")));
    }

    @Test
    @DisplayName("the field list is immutable, so a plan cannot mutate a shared schema")
    void fieldsAreImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> LINEITEM.fields().add(Field.of("x", DataType.INT32)));
    }
}
