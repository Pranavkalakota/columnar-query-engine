package columnar.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FieldTest {

    @Test
    @DisplayName("a field read from a file is nullable; a required one is not")
    void nullabilityDefaults() {
        assertTrue(Field.of("a", DataType.INT32).nullable());
        assertFalse(Field.required("a", DataType.INT32).nullable());
    }

    @Test
    @DisplayName("a field without a usable name is rejected at construction")
    void nameIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> Field.of(null, DataType.INT32));
        assertThrows(IllegalArgumentException.class, () -> Field.of("", DataType.INT32));
        assertThrows(IllegalArgumentException.class, () -> Field.of("   ", DataType.INT32));
    }

    @Test
    @DisplayName("a field without a type is rejected at construction")
    void typeIsRequired() {
        assertThrows(IllegalArgumentException.class, () -> Field.of("a", null));
    }

    @Test
    @DisplayName("records compare by value, so two identical fields are equal")
    void equalityIsByValue() {
        assertEquals(Field.of("a", DataType.INT32), Field.of("a", DataType.INT32));
        assertFalse(Field.of("a", DataType.INT32).equals(Field.required("a", DataType.INT32)));
    }

    @Test
    @DisplayName("toString shows the name, type, and nullability")
    void readableToString() {
        assertEquals("a INT32", Field.of("a", DataType.INT32).toString());
        assertEquals("a INT32 NOT NULL", Field.required("a", DataType.INT32).toString());
    }
}
