package columnar.expr.vec;

import columnar.vector.VectorBatch;

/** One comparison, compiled against a column of a batch. */
public interface BatchPredicate {

    /** Writes the rows of 0..n that pass, returning how many. */
    int selectAll(VectorBatch batch, int n, int[] out);

    /** Writes the rows of sel that pass. */
    int selectFrom(VectorBatch batch, int[] sel, int n, int[] out);
}
