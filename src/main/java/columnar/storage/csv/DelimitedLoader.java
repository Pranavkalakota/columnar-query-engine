package columnar.storage.csv;

import columnar.types.Dates;
import columnar.types.Schema;
import columnar.vector.BooleanVector;
import columnar.vector.ColumnVector;
import columnar.vector.DoubleVector;
import columnar.vector.IntVector;
import columnar.vector.LongVector;
import columnar.vector.StringVector;
import columnar.vector.VectorBatch;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fills {@link VectorBatch}es from a delimited file according to a
 * {@link Schema}, with no intermediate String or row object.
 *
 * <p><b>An empty field is NULL</b>, not an empty string, because that is what
 * the oracle does. A failed parse aborts the load naming the record and column
 * rather than substituting a zero. See {@code docs/DESIGN.md} for the reasoning.
 */
public final class DelimitedLoader implements Closeable {

    private final DelimitedRecordReader reader;
    private final Schema schema;
    private long rowsRead;

    public DelimitedLoader(InputStream in, Schema schema, DelimitedOptions options) throws IOException {
        this(in, schema, options, false, 1 << 16);
    }

    public DelimitedLoader(InputStream in, Schema schema, DelimitedOptions options,
            boolean hasHeader, int bufferSize) throws IOException {
        this.schema = schema;
        this.reader = new DelimitedRecordReader(in, options, bufferSize);
        if (hasHeader) {
            reader.nextRecord();
        }
    }

    /** Rows loaded so far, across every batch. */
    public long rowsRead() {
        return rowsRead;
    }

    /** Fills a batch to capacity; a short batch is normal, only an empty one ends the file. */
    public boolean loadInto(VectorBatch batch) throws IOException {
        batch.reset(batch.capacity());
        int row = 0;
        while (row < batch.capacity() && reader.nextRecord()) {
            if (reader.fieldCount() != schema.size()) {
                throw new IOException("record %d has %d fields but the schema has %d"
                        .formatted(reader.recordNumber(), reader.fieldCount(), schema.size()));
            }
            for (int column = 0; column < schema.size(); column++) {
                store(batch.column(column), column, row);
            }
            row++;
            rowsRead++;
        }
        batch.setRowCount(row);
        return row > 0;
    }

    private void store(ColumnVector vector, int column, int row) throws IOException {
        int start = reader.fieldStart(column);
        int length = reader.fieldLength(column);
        try {
            if (length == 0) {
                storeNull(vector, row);
            } else {
                storeValue(vector, row, reader.data(), start, length);
            }
        } catch (RuntimeException e) {
            throw new IOException("record %d, column '%s': %s"
                    .formatted(reader.recordNumber(), schema.field(column).name(), e.getMessage()), e);
        }
    }

    /** A string NULL is appended to keep offsets advancing; fixed-width just marks the row. */
    private static void storeNull(ColumnVector vector, int row) {
        if (vector instanceof StringVector strings) {
            strings.appendNull();
        } else {
            vector.setNull(row);
        }
    }

    private static void storeValue(ColumnVector vector, int row, byte[] data, int start, int length) {
        switch (vector.type()) {
            case INT32 -> ((IntVector) vector).set(row, ByteNumbers.parseInt(data, start, length));
            case DATE -> ((IntVector) vector).set(row, Dates.parse(data, start, length));
            case INT64 -> ((LongVector) vector).set(row, ByteNumbers.parseLong(data, start, length));
            case DOUBLE -> ((DoubleVector) vector).set(row, ByteNumbers.parseDouble(data, start, length));
            case BOOLEAN -> ((BooleanVector) vector).set(row, ByteNumbers.parseBoolean(data, start, length));
            case VARCHAR -> ((StringVector) vector).append(data, start, length);
        }
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
