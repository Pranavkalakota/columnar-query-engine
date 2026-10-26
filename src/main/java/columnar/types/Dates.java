package columnar.types;

import java.time.LocalDate;

/** Calendar dates to and from epoch days. */
public final class Dates {

    /** 0001-01-01, the earliest date the oracle accepts. */
    public static final int MIN_EPOCH_DAY = -719162;

    /** 9999-12-31, the latest date the oracle accepts. */
    public static final int MAX_EPOCH_DAY = 2932896;

    /** Days from 0000-01-01 to the epoch. */
    private static final long DAYS_0000_TO_1970 = 719528;

    private static final int DATE_TEXT_LENGTH = 10;

    private Dates() {
    }

    public static boolean isLeapYear(int year) {
        return (year & 3) == 0 && (year % 100 != 0 || year % 400 == 0);
    }

    public static int lengthOfMonth(int year, int month) {
        return switch (month) {
            case 2 -> isLeapYear(year) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
    }

    /** Day offset from the epoch. */
    public static int toEpochDay(int year, int month, int day) {
        if (year < 1 || year > 9999) {
            throw new IllegalArgumentException("year out of range: " + year);
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month out of range: " + month);
        }
        if (day < 1 || day > lengthOfMonth(year, month)) {
            throw new IllegalArgumentException(
                    "day out of range for %04d-%02d: %d".formatted(year, month, day));
        }
        long total = 365L * year;
        total += (year + 3) / 4 - (year + 99) / 100 + (year + 399) / 400;
        total += (367 * month - 362) / 12;
        total += day - 1;
        if (month > 2) {
            total--;
            if (!isLeapYear(year)) {
                total--;
            }
        }
        return (int) (total - DAYS_0000_TO_1970);
    }

    /** Parses YYYY-MM-DD from bytes, with no intermediate String. */
    public static int parse(byte[] bytes, int offset, int length) {
        if (length != DATE_TEXT_LENGTH
                || bytes[offset + 4] != '-'
                || bytes[offset + 7] != '-') {
            throw new IllegalArgumentException("not a YYYY-MM-DD date: " + text(bytes, offset, length));
        }
        int year = digits(bytes, offset, 4);
        int month = digits(bytes, offset + 5, 2);
        int day = digits(bytes, offset + 8, 2);
        return toEpochDay(year, month, day);
    }

    private static int digits(byte[] bytes, int offset, int count) {
        int value = 0;
        for (int i = 0; i < count; i++) {
            int digit = bytes[offset + i] - '0';
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("not a digit: '" + (char) bytes[offset + i] + "'");
            }
            value = value * 10 + digit;
        }
        return value;
    }

    private static String text(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, Math.max(0, length), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Allocates; for display and boxed access only. */
    public static LocalDate toLocalDate(int epochDay) {
        return LocalDate.ofEpochDay(epochDay);
    }
}
