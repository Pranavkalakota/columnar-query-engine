package columnar.exec.agg;

import columnar.types.DataType;

/** Folds one aggregate's running state. */
public sealed interface Accumulator {

    void accept(Object value);

    /** The aggregate so far, or null when nothing contributed. */
    Object result();

    static Accumulator create(AggregateFunction function, DataType resultType) {
        return switch (function) {
            case COUNT_STAR -> new CountRows();
            case COUNT -> new CountValues();
            case SUM -> resultType == DataType.DOUBLE ? new DoubleSum() : new LongSum();
            case AVG -> new Average();
            case MIN -> new Extreme(false);
            case MAX -> new Extreme(true);
        };
    }

    /** Counts every row. */
    final class CountRows implements Accumulator {
        private long count;

        @Override
        public void accept(Object ignored) {
            count++;
        }

        @Override
        public Object result() {
            return count;
        }
    }

    final class CountValues implements Accumulator {
        private long count;

        @Override
        public void accept(Object value) {
            if (value != null) {
                count++;
            }
        }

        @Override
        public Object result() {
            return count;
        }
    }

    final class LongSum implements Accumulator {
        private long sum;
        private boolean any;

        @Override
        public void accept(Object value) {
            if (value != null) {
                sum += ((Number) value).longValue();
                any = true;
            }
        }

        @Override
        public Object result() {
            return any ? sum : null;
        }
    }

    final class DoubleSum implements Accumulator {
        private double sum;
        private boolean any;

        @Override
        public void accept(Object value) {
            if (value != null) {
                sum += ((Number) value).doubleValue();
                any = true;
            }
        }

        @Override
        public Object result() {
            return any ? sum : null;
        }
    }

    final class Average implements Accumulator {
        private double sum;
        private long count;

        @Override
        public void accept(Object value) {
            if (value != null) {
                sum += ((Number) value).doubleValue();
                count++;
            }
        }

        @Override
        public Object result() {
            return count == 0 ? null : sum / count;
        }
    }

    /** MIN and MAX over one column. */
    final class Extreme implements Accumulator {
        private final boolean max;
        private Comparable<Object> best;

        Extreme(boolean max) {
            this.max = max;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void accept(Object value) {
            if (value == null) {
                return;
            }
            Comparable<Object> candidate = (Comparable<Object>) value;
            if (best == null || (max ? candidate.compareTo(best) > 0 : candidate.compareTo(best) < 0)) {
                best = candidate;
            }
        }

        @Override
        public Object result() {
            return best;
        }
    }
}
