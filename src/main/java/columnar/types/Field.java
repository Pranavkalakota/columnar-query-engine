package columnar.types;

/**
 * One column's name, type, and whether it admits NULL.
 *
 * <p>{@code nullable} is not decoration. Kernels check a single
 * {@code mayHaveNulls} flag per batch and take a branch-free fast path when it
 * is clear, so a column declared non-nullable at load time lets the whole
 * validity bitmap be skipped for every batch of it.
 */
public record Field(String name, DataType type, boolean nullable) {

    public Field {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a field needs a name");
        }
        if (type == null) {
            throw new IllegalArgumentException("field " + name + " needs a type");
        }
    }

    /** A nullable field, which is the default for anything read from a file. */
    public static Field of(String name, DataType type) {
        return new Field(name, type, true);
    }

    /** A field that is known to contain no NULLs, which unlocks the no-null fast path. */
    public static Field required(String name, DataType type) {
        return new Field(name, type, false);
    }

    @Override
    public String toString() {
        return name + " " + type + (nullable ? "" : " NOT NULL");
    }
}
