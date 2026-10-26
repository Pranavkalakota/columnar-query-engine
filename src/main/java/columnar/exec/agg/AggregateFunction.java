package columnar.exec.agg;

import columnar.types.DataType;

/** The aggregate functions the engine supports. */
public enum AggregateFunction {
    COUNT_STAR, COUNT, SUM, AVG, MIN, MAX;

    /** COUNT(*) is the only one that takes no input expression. */
    public boolean needsInput() {
        return this != COUNT_STAR;
    }

    /** Counting never yields NULL. */
    public boolean countsRows() {
        return this == COUNT_STAR || this == COUNT;
    }

    public DataType resultType(DataType input) {
        return switch (this) {
            case COUNT_STAR, COUNT -> DataType.INT64;
            case SUM -> input.sumType();
            case AVG -> input.avgType();
            case MIN, MAX -> input;
        };
    }

    @Override
    public String toString() {
        return this == COUNT_STAR ? "COUNT(*)" : name();
    }
}
