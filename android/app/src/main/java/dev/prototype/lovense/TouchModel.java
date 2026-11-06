package dev.prototype.lovense;

import java.util.Arrays;

/** Platform-independent state for one or two vibration control points. */
public final class TouchModel {
    public static final double REST_LIMIT = .05;
    public static final double REST_TRACK = .0665;

    /** Spatial track coordinates and requested output are deliberately distinct. */
    public static double trackToRequest(double position) {
        double p = Math.max(0, Math.min(1, position));
        return p <= REST_TRACK ? REST_LIMIT * p / REST_TRACK
            : REST_LIMIT + (1 - REST_LIMIT) * (p - REST_TRACK) / (1 - REST_TRACK);
    }
    public static double requestToTrack(double value) {
        double r = Math.max(0, Math.min(1, value));
        return r <= REST_LIMIT ? REST_TRACK * r / REST_LIMIT
            : REST_TRACK + (1 - REST_TRACK) * (r - REST_LIMIT) / (1 - REST_LIMIT);
    }
    public static double normalize(double value, int steps) {
        if (!Double.isFinite(value)) return 0;
        double r = Math.max(0, Math.min(1, value));
        if (r <= REST_LIMIT) return 0;
        double q = steps > 0 ? Math.round(r * steps) / (double) steps : r;
        return q <= REST_LIMIT ? 0 : q;
    }
    public static final class Feature {
        /** Original ScalarCmd array index, not the visible point position. */
        public final int scalarIndex;
        public final String label;
        /** Positive counts quantize output; zero means continuous normalized values. */
        public final int stepCount;

        public Feature(int scalarIndex, String label, int stepCount) {
            if (scalarIndex < 0) throw new IllegalArgumentException("Negative scalar index");
            if (stepCount < 0) throw new IllegalArgumentException("Negative step count");
            this.scalarIndex = scalarIndex;
            this.label = label == null || label.trim().isEmpty() ? "Vibration" : label;
            this.stepCount = stepCount;
        }
    }

    private Feature[] features = new Feature[0];
    private double[] levels = new double[0];
    private double[] visualLevels = new double[0];
    private int[] pointers = new int[0];
    private double[] downPosition = new double[0];
    private double[] downLevel = new double[0];
    private boolean[] restLatched = new boolean[0];
    private boolean hold;

    public void configure(Feature[] replacement) {
        if (replacement == null || replacement.length > 2)
            throw new IllegalArgumentException("Select zero, one, or two vibration controls");
        Feature[] copy = replacement.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == null) throw new IllegalArgumentException("Null feature");
            for (int j = 0; j < i; j++) if (copy[j].scalarIndex == copy[i].scalarIndex)
                throw new IllegalArgumentException("Duplicate scalar index");
        }
        features = copy;
        levels = new double[copy.length];
        visualLevels = new double[copy.length];
        pointers = new int[copy.length];
        Arrays.fill(pointers, -1);
        downPosition = new double[copy.length];
        downLevel = new double[copy.length];
        restLatched = new boolean[copy.length];
        hold = false;
    }

    public int size() { return features.length; }
    public Feature feature(int point) { return features[point]; }
    public double[] targets() { return levels.clone(); }
    public double[] visualTargets() { return visualLevels.clone(); }
    public double visualTarget(int point) { return visualLevels[point]; }
    public double target(int point) { return levels[point]; }
    public boolean isHolding() { return hold; }
    public boolean isPointActive(int point) { return pointers[point] >= 0; }
    public int activeTouchCount() {
        int count = 0;
        for (int pointer : pointers) if (pointer >= 0) count++;
        return count;
    }
    public boolean hasOutput() {
        for (double level : levels) if (level > 0) return true;
        return false;
    }

    /** Position is bottom=0/top=1 and may lie beyond the track. Down never changes output. */
    public boolean begin(int pointerId, int point, double position, double pickupTolerance) {
        if (pointerId < 0 || point < 0 || point >= size() || !Double.isFinite(position)
                || !Double.isFinite(pickupTolerance) || pickupTolerance < 0) return false;
        if (findPointer(pointerId) >= 0 || pointers[point] >= 0) return false;
        boolean restPickup = levels[point] == 0 && position >= -pickupTolerance && position <= REST_TRACK;
        if (!restPickup && Math.abs(position - requestToTrack(visualLevels[point])) > pickupTolerance) return false;
        pointers[point] = pointerId;
        downPosition[point] = position;
        downLevel[point] = requestToTrack(visualLevels[point]);
        restLatched[point] = levels[point] == 0 && position <= REST_TRACK;
        return true;
    }

    public boolean move(int pointerId, double position) {
        int point = findPointer(pointerId);
        if (point < 0 || !Double.isFinite(position)) return false;
        double next;
        if (position <= REST_TRACK) {
            next = 0;
            restLatched[point] = true;
            downPosition[point] = REST_TRACK;
            downLevel[point] = REST_TRACK;
        } else {
            if (restLatched[point]) {
                downPosition[point] = REST_TRACK;
                downLevel[point] = REST_TRACK;
                restLatched[point] = false;
            }
            next = normalize(trackToRequest(downLevel[point] + position - downPosition[point]), 0);
        }
        if (Double.compare(visualLevels[point], next) == 0) return false;
        visualLevels[point] = next;
        levels[point] = quantize(point, next);
        return true;
    }

    /** Releases only this pointer; another point's active/held target stays untouched. */
    public boolean end(int pointerId) {
        int point = findPointer(pointerId);
        if (point < 0) return false;
        pointers[point] = -1;
        restLatched[point] = false;
        downPosition[point] = downLevel[point] = 0;
        if (!hold) levels[point] = visualLevels[point] = 0;
        return true;
    }

    public void setHold(boolean enabled) {
        hold = enabled;
        if (!hold) for (int i = 0; i < size(); i++) if (!isPointActive(i)) levels[i] = visualLevels[i] = 0;
    }

    /** Invalidates all old pointer IDs. Only a new begin() can take ownership again. */
    public void clear() {
        Arrays.fill(levels, 0);
        Arrays.fill(visualLevels, 0);
        Arrays.fill(pointers, -1);
        Arrays.fill(downPosition, 0);
        Arrays.fill(downLevel, 0);
        Arrays.fill(restLatched, false);
        hold = false;
    }
    public void releaseTouches() {
        Arrays.fill(pointers, -1); Arrays.fill(restLatched, false);
        if (!hold) { Arrays.fill(levels, 0); Arrays.fill(visualLevels, 0); }
    }

    /** Synchronizes an alternate input path without inheriting any touch ownership. */
    public void setTargets(double[] replacement) {
        if (replacement == null || replacement.length != size())
            throw new IllegalArgumentException("Target count does not match features");
        if (activeTouchCount() != 0) throw new IllegalStateException("Cannot replace targets during a touch");
        for (double value : replacement) if (!Double.isFinite(value) || value < 0 || value > 1)
            throw new IllegalArgumentException("Target must be between zero and one");
        for (int i = 0; i < size(); i++) levels[i] = visualLevels[i] = quantize(i, replacement[i]);
    }

    /** Playback visuals remain continuous; actual output always uses the device's stages. */
    public void setVisualTargets(double[] replacement) {
        setTargets(replacement);
        for (int i = 0; i < size(); i++) visualLevels[i] = normalize(replacement[i], 0);
    }

    private int findPointer(int pointerId) {
        if (pointerId < 0) return -1;
        for (int i = 0; i < size(); i++) if (pointers[i] == pointerId) return i;
        return -1;
    }

    private double quantize(int point, double value) {
        return normalize(value, features[point].stepCount);
    }
}
