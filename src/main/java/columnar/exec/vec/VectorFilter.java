package columnar.exec.vec;

import columnar.expr.Expr;
import columnar.expr.vec.BatchPredicate;
import columnar.expr.vec.PredicateCompiler;
import columnar.types.Schema;
import columnar.vector.VectorBatch;
import java.util.List;

/** Narrows each batch to the rows a compiled predicate keeps. */
public final class VectorFilter implements VectorOperator {

    private final VectorOperator child;
    private final Expr predicate;
    private final List<BatchPredicate> chain;
    private int[] buffer = new int[0];

    public VectorFilter(VectorOperator child, Expr predicate) {
        this.child = child;
        this.predicate = predicate;
        this.chain = PredicateCompiler.compile(predicate, child.schema());
    }

    @Override
    public Schema schema() {
        return child.schema();
    }

    @Override
    public void open() {
        child.open();
    }

    /** Batches with no survivors are dropped, not returned empty. */
    @Override
    public VectorBatch next() {
        VectorBatch batch;
        while ((batch = child.next()) != null) {
            int surviving = apply(batch);
            if (surviving > 0) {
                batch.select(buffer, surviving);
                return batch;
            }
        }
        return null;
    }

    /** Runs the chain, each kernel narrowing what the last left. */
    private int apply(VectorBatch batch) {
        int rows = batch.selectedCount();
        if (buffer.length < rows) {
            buffer = new int[rows];
        }
        int[] inherited = batch.selection();
        int n = inherited == null
                ? chain.get(0).selectAll(batch, rows, buffer)
                : chain.get(0).selectFrom(batch, inherited, rows, buffer);
        for (int i = 1; i < chain.size() && n > 0; i++) {
            n = chain.get(i).selectFrom(batch, buffer, n, buffer);
        }
        return n;
    }

    @Override
    public void close() {
        child.close();
    }

    @Override
    public String toString() {
        return "VectorFilter(" + predicate + ")";
    }
}
