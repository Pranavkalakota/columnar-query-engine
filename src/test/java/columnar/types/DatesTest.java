package columnar.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DatesTest {

    private static int parse(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return Dates.parse(bytes, 0, bytes.length);
    }

    @Test
    @DisplayName("every day from 0001-01-01 to 9999-12-31 agrees with LocalDate")
    void exhaustivelyMatchesLocalDate() {
        // Hand-rolled calendar maths earns no benefit of the doubt.
        for (int epochDay = Dates.MIN_EPOCH_DAY; epochDay <= Dates.MAX_EPOCH_DAY; epochDay++) {
            LocalDate date = LocalDate.ofEpochDay(epochDay);
            int computed = Dates.toEpochDay(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
            if (computed != epochDay) {
                assertEquals(epochDay, computed, "mismatch at " + date);
            }
        }
    }

    @Test
    @DisplayName("the epoch is zero, earlier dates are negative, and the range ends match the oracle")
    void anchorsAndBoundaries() {
        assertEquals(0, parse("1970-01-01"));
        assertEquals(-1, parse("1969-12-31"));
        assertEquals(19782, parse("2024-02-29"));
        assertEquals(Dates.MIN_EPOCH_DAY, parse("0001-01-01"));
        assertEquals(Dates.MAX_EPOCH_DAY, parse("9999-12-31"));
        assertEquals(LocalDate.of(1969, 12, 31), Dates.toLocalDate(-1));
    }

    @Test
    @DisplayName("leap years follow the century rules, not just divisibility by four")
    void leapYearRules() {
        assertTrue(Dates.isLeapYear(2024));
        assertTrue(Dates.isLeapYear(2000), "divisible by 400");
        assertFalse(Dates.isLeapYear(1900), "divisible by 100 but not 400");
        assertFalse(Dates.isLeapYear(2023));
        assertEquals(29, Dates.lengthOfMonth(2024, 2));
        assertEquals(28, Dates.lengthOfMonth(1900, 2));
        assertEquals(30, Dates.lengthOfMonth(2024, 4));
        assertEquals(31, Dates.lengthOfMonth(2024, 12));
    }

    @Test
    @DisplayName("an impossible date is rejected, not rolled over into the next month")
    void rejectsImpossibleDates() {
        // The oracle rejects 2023-02-29 rather than rolling over.
        assertThrows(IllegalArgumentException.class, () -> parse("2023-02-29"));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(2024, 13, 1));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(2024, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(2024, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(2024, 4, 31));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> Dates.toEpochDay(10000, 1, 1));
    }

    @Test
    @DisplayName("malformed text is refused with the offending input in the message")
    void rejectsMalformedText() {
        assertThrows(IllegalArgumentException.class, () -> parse("2024-1-1"));
        assertThrows(IllegalArgumentException.class, () -> parse("2024/01/01"));
        assertThrows(IllegalArgumentException.class, () -> parse("20240101"));
        assertThrows(IllegalArgumentException.class, () -> parse(""));
        assertThrows(IllegalArgumentException.class, () -> parse("abcd-ef-gh"));
        assertThrows(IllegalArgumentException.class, () -> parse("2024-01-01 "));
    }

    @Test
    @DisplayName("parsing reads a date out of the middle of a larger buffer")
    void parsesFromAnOffset() {
        byte[] line = "x|1996-03-13|y".getBytes(StandardCharsets.UTF_8);
        assertEquals(parse("1996-03-13"), Dates.parse(line, 2, 10));
    }
}
