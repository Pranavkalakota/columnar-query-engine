package columnar.expr.vec;

import columnar.vector.VectorBatch;

/** A double expression evaluated over a batch. */
public interface DoubleKernel {

    /** Writes n values; true when all are valid. */
    boolean evaluate(VectorBatch batch, int[] sel, int n, double[] out, boolean[] valid);
}
