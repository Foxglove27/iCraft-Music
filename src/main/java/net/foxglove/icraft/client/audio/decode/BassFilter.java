package net.foxglove.icraft.client.audio.decode;

/**
 * Turns up the bass of 16-bit PCM: a "low shelf" filter that makes everything below about 100 Hz louder by a number of
 * decibels and leaves the rest alone (the standard biquad from Robert Bristow-Johnson's "Audio EQ Cookbook").
 * No Minecraft imports, so it can be tested on its own.
 *
 * <p>More bass means bigger waves, and samples above the 16-bit limit would crackle. So the whole signal is first
 * made a little quieter (half the boost), and the few peaks that are still too loud are rounded off gently
 * (soft clipping) instead of being cut flat.
 */
public final class BassFilter {
    private static final double SHELF_HZ = 100;
    private static final double KNEE = 0.8; // above 80 % of full scale, peaks are rounded off

    private final int channels;
    private final double b0, b1, b2, a1, a2; // filter coefficients, already divided by a0
    private final double preGain;
    // the last two input and output samples of every channel
    private final double[] x1, x2, y1, y2;

    public BassFilter(double boostDb, int sampleRate, int channels) {
        this.channels = channels;
        double a = Math.pow(10, boostDb / 40);
        double w0 = 2 * Math.PI * SHELF_HZ / sampleRate;
        double cos = Math.cos(w0);
        double alpha = Math.sin(w0) / 2 * Math.sqrt(2); // shelf slope 1
        double sqrtA2alpha = 2 * Math.sqrt(a) * alpha;

        double a0 = (a + 1) + (a - 1) * cos + sqrtA2alpha;
        this.b0 = a * ((a + 1) - (a - 1) * cos + sqrtA2alpha) / a0;
        this.b1 = 2 * a * ((a - 1) - (a + 1) * cos) / a0;
        this.b2 = a * ((a + 1) - (a - 1) * cos - sqrtA2alpha) / a0;
        this.a1 = -2 * ((a - 1) + (a + 1) * cos) / a0;
        this.a2 = ((a + 1) + (a - 1) * cos - sqrtA2alpha) / a0;
        this.preGain = Math.pow(10, -boostDb / 2 / 20);

        x1 = new double[channels];
        x2 = new double[channels];
        y1 = new double[channels];
        y2 = new double[channels];
    }

    /** Filters {@code frames} frames of interleaved samples in place. */
    public void process(short[] samples, int frames) {
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                int i = f * channels + c;
                double x = samples[i] / 32768.0 * preGain;
                double y = b0 * x + b1 * x1[c] + b2 * x2[c] - a1 * y1[c] - a2 * y2[c];
                x2[c] = x1[c];
                x1[c] = x;
                y2[c] = y1[c];
                y1[c] = y;
                samples[i] = (short) Math.round(softClip(y) * 32767);
            }
        }
    }

    /** Forgets the previous samples (after a seek, the old ones do not belong to the new position). */
    public void reset() {
        java.util.Arrays.fill(x1, 0);
        java.util.Arrays.fill(x2, 0);
        java.util.Arrays.fill(y1, 0);
        java.util.Arrays.fill(y2, 0);
    }

    // Unchanged below the knee; above it the curve bends smoothly towards full scale and never goes past it
    private static double softClip(double v) {
        double mag = Math.abs(v);
        if (mag <= KNEE) return v;
        double room = 1 - KNEE;
        double bent = KNEE + room * Math.tanh((mag - KNEE) / room);
        return Math.copySign(bent, v);
    }
}
