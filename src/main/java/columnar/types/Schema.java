package columnar.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An ordered list of {@link Field}s with name lookup.
 *
 * <p>Lookup is case-insensitive because unquoted SQL identifiers are, and it
 * reports ambiguity rather than resolving it. A join of two tables that both
 * have a {@code c} column produces a schema with two {@code c} fields, and
 * quietly returning the first one would make {@code SELECT c} silently read a
 * column the user did not mean. The binder turns this into an error with a
 * position; here it is just refused.
 *
 * <p>{@link #project} exists because column pruning is the headline columnar
 * win: a scan should read only the columns a query actually touches, so the
 * plan needs a cheap way to say "this subset, in this order".
 */
public final class Schema {

    private final List<Field> fields;
    private final Map<String, List<Integer>> byLowerName;

    private Schema(List<Field> fields) {
        this.fields = List.copyOf(fields);
        Map<String, List<Integer>> index = new LinkedHashMap<>();
        for (int i = 0; i < this.fields.size(); i++) {
            index.computeIfAbsent(this.fields.get(i).name().toLowerCase(), k -> new ArrayList<>()).add(i);
        }
        this.byLowerName = index;
    }

    public static Schema of(Field... fields) {
        return new Schema(List.of(fields));
    }

    public static Schema of(List<Field> fields) {
        return new Schema(fields);
    }

    public List<Field> fields() {
        return fields;
    }

    public int size() {
        return fields.size();
    }

    public Field field(int index) {
        return fields.get(index);
    }

    public DataType type(int index) {
        return fields.get(index).type();
    }

    public List<String> names() {
        return fields.stream().map(Field::name).toList();
    }

    /** True when exactly one field has this name. An ambiguous name is not "present". */
    public boolean contains(String name) {
        List<Integer> hits = byLowerName.get(name.toLowerCase());
        return hits != null && hits.size() == 1;
    }

    /**
     * The index of a column, or {@code -1} when no field has that name.
     *
     * <p>Throws when more than one does, because any single answer would be a
     * guess about which column the caller meant.
     */
    public int indexOf(String name) {
        List<Integer> hits = byLowerName.get(name.toLowerCase());
        if (hits == null) {
            return -1;
        }
        if (hits.size() > 1) {
            throw new IllegalArgumentException("ambiguous column '" + name + "' in " + names());
        }
        return hits.get(0);
    }

    /** Like {@link #indexOf} but fails on an unknown name instead of returning -1. */
    public int requireIndexOf(String name) {
        int index = indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("no such column '" + name + "' in " + names());
        }
        return index;
    }

    /** A schema holding just these columns, in the order given. Used for column pruning. */
    public Schema project(List<String> names) {
        List<Field> projected = new ArrayList<>(names.size());
        for (String name : names) {
            projected.add(fields.get(requireIndexOf(name)));
        }
        return new Schema(projected);
    }

    @Override
    public String toString() {
        return "(" + String.join(", ", fields.stream().map(Field::toString).toList()) + ")";
    }
}
