package columnar.exec.vec;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Maps rows to group ordinals by their key columns. */
public final class GroupKeys {

    private static final int INITIAL_BUCKETS = 1024;

    private final List<String> names;
    private final GroupKeyStore[] stores;
    private final int[] columns;
    private final Schema schema;
    private int[] buckets;
    private int[] hashes;
    private int groupCount;
    private int[] ordinals = new int[0];

    public GroupKeys(Schema input, List<String> keyNames) {
        if (keyNames.isEmpty()) {
            throw new IllegalArgumentException("grouping needs at least one key column");
        }
        this.names = List.copyOf(keyNames);
        this.stores = new GroupKeyStore[names.size()];
        this.columns = new int[names.size()];
        List<Field> fields = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            int column = input.requireIndexOf(names.get(i));
            DataType type = input.type(column);
            columns[i] = column;
            stores[i] = GroupKeyStore.create(type);
            fields.add(Field.of(names.get(i), type));
        }
        this.schema = Schema.of(fields);
        this.buckets = new int[INITIAL_BUCKETS];
        this.hashes = new int[64];
    }

    /** The key columns, in order, as they appear in output. */
    public Schema schema() {
        return schema;
    }

    public int groupCount() {
        return groupCount;
    }

    /** Group ordinal per selected row, in a reused array. */
    public int[] ordinalsFor(VectorBatch batch) {
        int n = batch.selectedCount();
        if (ordinals.length < n) {
            ordinals = new int[n];
        }
        int[] sel = batch.selection();
        for (int i = 0; i < n; i++) {
            ordinals[i] = lookup(batch, sel == null ? i : sel[i], columns, true);
        }
        return ordinals;
    }

    /** The group a row belongs to, adding one if new. */
    public int ordinalOf(VectorBatch batch, int row) {
        return lookup(batch, row, columns, true);
    }

    /** A probe row's group, or -1; never adds one. */
    public int find(VectorBatch batch, int row, int[] probeColumns) {
        return lookup(batch, row, probeColumns, false);
    }

    /** Writes a group's key into an output row. */
    public void writeKey(int group, VectorBatch out, int row) {
        for (int i = 0; i < stores.length; i++) {
            stores[i].writeTo(group, out.column(i), row);
        }
    }

    private int lookup(VectorBatch batch, int row, int[] cols, boolean insert) {
        int hash = hash(batch, row, cols);
        int mask = buckets.length - 1;
        int bucket = hash & mask;
        while (true) {
            int slot = buckets[bucket];
            if (slot == 0) {
                return insert ? addGroup(batch, row, cols, hash, bucket) : -1;
            }
            int group = slot - 1;
            if (hashes[group] == hash && matches(batch, row, cols, group)) {
                return group;
            }
            bucket = (bucket + 1) & mask;
        }
    }

    private int addGroup(VectorBatch batch, int row, int[] cols, int hash, int bucket) {
        int group = groupCount++;
        for (int i = 0; i < stores.length; i++) {
            stores[i].append(batch.column(cols[i]), row);
        }
        if (group >= hashes.length) {
            hashes = Arrays.copyOf(hashes, hashes.length * 2);
        }
        hashes[group] = hash;
        buckets[bucket] = group + 1;
        if (groupCount * 2 >= buckets.length) {
            rehash();
        }
        return group;
    }

    private boolean matches(VectorBatch batch, int row, int[] cols, int group) {
        for (int i = 0; i < stores.length; i++) {
            if (!stores[i].matches(batch.column(cols[i]), row, group)) {
                return false;
            }
        }
        return true;
    }

    private int hash(VectorBatch batch, int row, int[] cols) {
        int hash = 1;
        for (int i = 0; i < stores.length; i++) {
            hash = hash * 31 + stores[i].hash(batch.column(cols[i]), row);
        }
        return mix(hash);
    }

    /** Spreads the bits, and keeps the result non-negative. */
    private static int mix(int hash) {
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash & 0x7fffffff;
    }

    private void rehash() {
        int[] grown = new int[buckets.length * 2];
        int mask = grown.length - 1;
        for (int group = 0; group < groupCount; group++) {
            int bucket = hashes[group] & mask;
            while (grown[bucket] != 0) {
                bucket = (bucket + 1) & mask;
            }
            grown[bucket] = group + 1;
        }
        buckets = grown;
    }

    @Override
    public String toString() {
        return "GroupKeys" + names;
    }
}
