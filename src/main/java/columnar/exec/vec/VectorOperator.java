package columnar.exec.vec;

import columnar.types.Schema;
import columnar.vector.VectorBatch;

/** One operator in the batch-at-a-time pipeline. */
public interface VectorOperator extends AutoCloseable {

    Schema schema();

    void open();

    /** The next batch, or null at end. */
    VectorBatch next();

    @Override
    void close();
}
