package columnar.types;

/** One column's name, type, and whether it admits NULL. */
public record Field(String name, DataType type, boolean nullable) {

    public Field {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a field needs a name");
        }
        if (type == null) {
            throw new IllegalArgumentException("field " + name + " needs a type");
        }
    }

    /** A nullable field, the default for anything read from a file. */
    public static Field of(String name, DataType type) {
        return new Field(name, type, true);
    }

    /** A field known to hold no NULLs, which unlocks the no-null fast path. */
    public static Field required(String name, DataType type) {
        return new Field(name, type, false);
    }

    @Override
    public String toString() {
        return name + " " + type + (nullable ? "" : " NOT NULL");
    }
}
