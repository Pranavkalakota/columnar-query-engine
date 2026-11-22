package columnar.exec.vec;

import columnar.types.DataType;
import columnar.types.Field;
import columnar.types.Schema;
import columnar.vector.ColumnVector;
import columnar.vector.VectorBatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Inner equi-join: hash the build side, probe by batch. */
public final class VectorHashJoin implements VectorOperator {

    private final VectorOperator probe;
    private final VectorOperator build;
    private final List<String> buildKeys;
    private final int[] probeKeyColumns;
    private final int[] buildKeyColumns;
    private final int probeWidth;
    private final Schema schema;
    private final int batchSize;
    private GroupKeys table;
    private ColumnBuffer[] buildColumns;
    private int[] head = new int[0];
    private int[] tail = new int[0];
    private int[] next = new int[0];
    private int buildRows;
    private int[] keep = new int[0];
    private VectorBatch current;
    private int[] matchProbe = new int[0];
    private int[] matchBuild = new int[0];
    private int matches;
    private int emitted;
    private boolean built;

    public VectorHashJoin(VectorOperator probe, VectorOperator build,
            List<String> probeKeys, List<String> buildKeys, int batchSize) {
        if (probeKeys.isEmpty() || probeKeys.size() != buildKeys.size()) {
            throw new IllegalArgumentException("a join needs matching key lists, got " + probeKeys + " and " + buildKeys);
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be positive: " + batchSize);
        }
        this.probe = probe;
        this.build = build;
        this.buildKeys = List.copyOf(buildKeys);
        this.batchSize = batchSize;
        this.probeKeyColumns = resolve(probe.schema(), probeKeys);
        this.buildKeyColumns = resolve(build.schema(), buildKeys);
        for (int k = 0; k < probeKeyColumns.length; k++) {
            DataType left = probe.schema().type(probeKeyColumns[k]);
            DataType right = build.schema().type(buildKeyColumns[k]);
            if (left != right) {
                throw new UnsupportedOperationException("cannot join " + left + " with " + right);
            }
        }
        this.probeWidth = probe.schema().size();
        List<Field> fields = new ArrayList<>(probe.schema().fields());
        fields.addAll(build.schema().fields());
        this.schema = Schema.of(fields);
        this.table = new GroupKeys(build.schema(), this.buildKeys);
    }

    private static int[] resolve(Schema schema, List<String> names) {
        int[] columns = new int[names.size()];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = schema.requireIndexOf(names.get(i));
        }
        return columns;
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public void open() {
        probe.open();
        build.open();
        built = false;
        current = null;
        matches = 0;
        emitted = 0;
    }

    /** Builds first, then emits matches a batch at a time. */
    @Override
    public VectorBatch next() {
        if (!built) {
            buildTable();
            built = true;
        }
        while (emitted >= matches) {
            current = probe.next();
            if (current == null) {
                return null;
            }
            probeBatch(current);
        }
        int n = Math.min(batchSize, matches - emitted);
        VectorBatch out = VectorBatch.allocate(schema, n);
        out.setRowCount(n);
        for (int c = 0; c < probeWidth; c++) {
            VectorGather.gather(current.column(c), matchProbe, emitted, n, out.column(c));
        }
        for (int c = 0; c < buildColumns.length; c++) {
            buildColumns[c].gather(matchBuild, emitted, n, out.column(probeWidth + c));
        }
        emitted += n;
        return out;
    }

    /** Chains each key's build rows in arrival order. */
    private void buildTable() {
        table = new GroupKeys(build.schema(), buildKeys);
        Schema buildSchema = build.schema();
        buildColumns = new ColumnBuffer[buildSchema.size()];
        for (int c = 0; c < buildColumns.length; c++) {
            buildColumns[c] = ColumnBuffer.create(buildSchema.type(c));
        }
        buildRows = 0;
        VectorBatch batch;
        while ((batch = build.next()) != null) {
            int n = keepNonNullKeys(batch, buildKeyColumns);
            for (int c = 0; c < buildColumns.length; c++) {
                buildColumns[c].appendAll(batch.column(c), keep, n);
            }
            for (int i = 0; i < n; i++) {
                link(table.ordinalOf(batch, keep[i]));
            }
        }
    }

    private void link(int key) {
        if (key >= head.length) {
            int size = Math.max(key + 1, head.length * 2);
            int old = head.length;
            head = Arrays.copyOf(head, size);
            tail = Arrays.copyOf(tail, size);
            Arrays.fill(head, old, size, -1);
        }
        if (buildRows >= next.length) {
            next = Arrays.copyOf(next, Math.max(1024, next.length * 2));
        }
        int row = buildRows++;
        next[row] = -1;
        if (head[key] < 0) {
            head[key] = row;
        } else {
            next[tail[key]] = row;
        }
        tail[key] = row;
    }

    /** NULL never equals anything, so those rows cannot match. */
    private int keepNonNullKeys(VectorBatch batch, int[] keyColumns) {
        int n = batch.selectedCount();
        if (keep.length < n) {
            keep = new int[n];
        }
        int[] sel = batch.selection();
        int k = 0;
        outer:
        for (int i = 0; i < n; i++) {
            int row = sel == null ? i : sel[i];
            for (int column : keyColumns) {
                ColumnVector key = batch.column(column);
                if (key.mayHaveNulls() && key.isNull(row)) {
                    continue outer;
                }
            }
            keep[k++] = row;
        }
        return k;
    }

    private void probeBatch(VectorBatch batch) {
        matches = 0;
        emitted = 0;
        int n = keepNonNullKeys(batch, probeKeyColumns);
        for (int i = 0; i < n; i++) {
            int row = keep[i];
            int key = table.find(batch, row, probeKeyColumns);
            if (key < 0) {
                continue;
            }
            for (int b = head[key]; b >= 0; b = next[b]) {
                addMatch(row, b);
            }
        }
    }

    private void addMatch(int probeRow, int buildRow) {
        if (matches >= matchProbe.length) {
            int size = Math.max(1024, matchProbe.length * 2);
            matchProbe = Arrays.copyOf(matchProbe, size);
            matchBuild = Arrays.copyOf(matchBuild, size);
        }
        matchProbe[matches] = probeRow;
        matchBuild[matches] = buildRow;
        matches++;
    }

    @Override
    public void close() {
        probe.close();
        build.close();
    }

    @Override
    public String toString() {
        return "VectorHashJoin(on " + buildKeys + ")";
    }
}
