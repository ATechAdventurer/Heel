package dev.prototype.lovense;

import java.util.Arrays;

public final class TouchModelTest {
    private static int tests;
    private static TouchModel dual() {
        TouchModel model = new TouchModel();
        model.configure(new TouchModel.Feature[] {
            new TouchModel.Feature(2, "Vibration A", 20),
            new TouchModel.Feature(5, "Vibration B", 100)
        });
        return model;
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void equal(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-9) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    private static void expectThrow(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static void test(String name, Runnable action) {
        action.run(); tests++; System.out.println("PASS " + name);
    }
    public static void main(String[] args) {
        test("capability identity and step resolution", () -> {
            TouchModel model = dual();
            check(model.feature(0).scalarIndex == 2 && model.feature(1).scalarIndex == 5, "Visible points must preserve original scalar indices");
            model.setTargets(new double[] {.326, .326});
            equal(model.target(0), .35, "20-step feature");
            equal(model.target(1), .33, "100-step feature");
        });
        test("pickup rejects distant contacts and never jumps on down", () -> {
            TouchModel model = dual();
            check(!model.begin(7, 0, .9, .1), "Far touch must not engage");
            check(!model.move(7, TouchModel.requestToTrack(.1)), "Rejected gesture cannot acquire while moving");
            equal(model.target(0), 0, "Rejected gesture output");
            check(model.begin(7, 0, .08, .1), "Generous pickup band");
            equal(model.target(0), 0, "Down preserves zero");
            model.move(7, TouchModel.requestToTrack(.28));
            equal(model.target(0), .2, "Down offset retained");
        });
        test("two pointers use stable IDs despite event ordering", () -> {
            TouchModel model = dual();
            check(model.begin(12, 0, 0, .1), "First finger");
            check(model.begin(3, 1, 0, .1), "Second finger");
            model.move(3, TouchModel.requestToTrack(.38)); model.move(12, TouchModel.requestToTrack(.65));
            equal(model.target(0), .65, "First target"); equal(model.target(1), .38, "Second target");
            check(model.activeTouchCount() == 2, "Both active");
            model.end(12);
            equal(model.target(0), 0, "First release zeros first"); equal(model.target(1), .38, "First release preserves second");
            check(model.isPointActive(1), "Remaining finger remains active");
            model.move(3, TouchModel.requestToTrack(.48)); equal(model.target(1), .48, "Remaining finger still owns point");
        });
        test("crossing cannot transfer ownership or steal an active point", () -> {
            TouchModel model = dual();
            check(model.begin(4, 0, 0, .1), "First point");
            check(!model.begin(8, 0, 0, .1), "Second finger cannot steal same point");
            check(!model.begin(4, 1, 0, .1), "Existing ID cannot own second point");
            model.move(4, TouchModel.requestToTrack(.5));
            equal(model.target(0), .5, "Original ownership"); equal(model.target(1), 0, "Other point unchanged");
        });
        test("Hold release and disabling Hold preserve active finger", () -> {
            TouchModel model = dual(); model.setHold(true);
            model.begin(1, 0, 0, .1); model.begin(2, 1, 0, .1);
            model.move(1, TouchModel.requestToTrack(.6)); model.move(2, TouchModel.requestToTrack(.3)); model.end(1);
            equal(model.target(0), .6, "Released value held");
            check(!model.isPointActive(0) && model.isPointActive(1), "Held and touching states distinct");
            model.setHold(false);
            equal(model.target(0), 0, "Turning Hold off stops held point");
            equal(model.target(1), .3, "Turning Hold off preserves active point");
            model.end(2); equal(model.target(1), 0, "Active point stops on release");
        });
        test("pickup of a held value keeps exact value and offset", () -> {
            TouchModel model = dual(); model.setHold(true); model.setTargets(new double[] {.5, 0});
            check(model.begin(9, 0, .57, .1), "Pick up held value from band edge");
            equal(model.target(0), .5, "No jump on pickup");
            model.move(9, TouchModel.requestToTrack(.67)); equal(model.target(0), .6, "Relative displacement");
        });
        test("Stop and cancel clear Hold and invalidate old gestures", () -> {
            TouchModel model = dual(); model.setHold(true); model.begin(6, 0, 0, .1); model.move(6, TouchModel.requestToTrack(.8));
            model.begin(2, 1, 0, .1); model.move(2, TouchModel.requestToTrack(.4)); model.end(2);
            model.clear();
            check(!model.isHolding() && model.activeTouchCount() == 0 && !model.hasOutput(), "Full reset");
            check(!model.move(6, TouchModel.requestToTrack(1)) && !model.end(6), "Stale touch cannot restart");
            check(!model.move(2, TouchModel.requestToTrack(1)), "Stale released touch cannot restart");
            check(model.begin(10, 1, 0, .1), "New engagement allowed");
            model.move(10, TouchModel.requestToTrack(.1)); equal(model.target(1), .1, "Fresh touch works");
        });
        test("normalized bounds and invalid numbers", () -> {
            TouchModel model = dual(); model.begin(1, 0, 0, .1);
            model.move(1, TouchModel.requestToTrack(100)); equal(model.target(0), 1, "Upper bound");
            model.move(1, TouchModel.requestToTrack(-100)); equal(model.target(0), 0, "Lower bound");
            check(!model.move(1, Double.NaN), "NaN ignored");
            check(!model.begin(5, 1, Double.POSITIVE_INFINITY, .1), "Infinite position rejected");
            check(!model.begin(5, 1, 0, Double.NaN), "Invalid pickup band rejected");
        });
        test("single control and continuous capability", () -> {
            TouchModel model = new TouchModel();
            model.configure(new TouchModel.Feature[] {new TouchModel.Feature(9, "Custom vibration", 0)});
            model.begin(1, 0, 0, .1); model.move(1, TouchModel.requestToTrack(.33333));
            equal(model.target(0), .33333, "Zero step count remains continuous");
            check(!model.begin(2, 1, 0, .1), "No invented second control");
        });
        test("configuration and alternate targets validated atomically", () -> {
            TouchModel model = dual();
            expectThrow(() -> model.configure(new TouchModel.Feature[] {model.feature(0), model.feature(0)}), "Duplicate scalar index rejected");
            check(model.size() == 2, "Invalid configuration preserves current one");
            expectThrow(() -> model.setTargets(new double[] {.5, Double.NaN}), "Nonfinite target rejected");
            equal(model.target(0), 0, "Invalid replacement applies no partial target");
            expectThrow(() -> model.setTargets(new double[] {1}), "Incorrect target count rejected");
            model.begin(5, 0, 0, .1);
            expectThrow(() -> model.setTargets(new double[] {.2, .4}), "Cannot overwrite active touch");
            model.clear(); model.setTargets(new double[] {.2, .4});
            double[] copy = model.targets(); copy[0] = 1;
            equal(model.target(0), .2, "Returned targets cannot mutate model");
            model.configure(new TouchModel.Feature[0]);
            check(model.size() == 0 && !model.isHolding() && Arrays.equals(model.targets(), new double[0]), "Empty capability state");
        });
        test("inclusive deadzone on all input paths", () -> {
            for (int steps : new int[] {0, 1, 7, 20, 100, 1000}) {
                for (double value : new double[] {0, .001, .049999, .05}) equal(TouchModel.normalize(value, steps), 0, "Inclusive rest");
            }
            equal(TouchModel.normalize(.05001, 20), 0, "Quantized 5 percent also rests");
            equal(TouchModel.normalize(.0749, 20), 0, "Hush below first active step");
            equal(TouchModel.normalize(.075, 20), .1, "Hush first active step");
            TouchModel model = dual(); model.setTargets(new double[] {.05, .05});
            check(!model.hasOutput(), "Sliders share rest normalization");
        });
        test("expanded track round trips and entire shelf accepts pickup", () -> {
            for (int i = 0; i <= 1000; i++) equal(TouchModel.trackToRequest(TouchModel.requestToTrack(i / 1000.0)), i / 1000.0, "Forward inverse");
            equal(TouchModel.requestToTrack(.05), .0665, "Shelf is 33 percent taller");
            TouchModel model = dual(); check(model.begin(1, 0, .06, .01), "Whole shelf works even on tall tracks");
            equal(model.target(0), 0, "Down does not change output");
        });
        test("absolute rest overrides held pickup offset and rebases immediately", () -> {
            TouchModel model = new TouchModel();
            model.configure(new TouchModel.Feature[]{new TouchModel.Feature(0,"Continuous",0)});
            model.setTargets(new double[]{.5}); model.setHold(true);
            double ring = TouchModel.requestToTrack(.5);
            check(model.begin(9, 0, ring - .09, .1), "Pickup below held ring");
            model.move(9, TouchModel.REST_TRACK); equal(model.target(0), 0, "Finger at cutoff stops despite relative offset");
            model.move(9, TouchModel.REST_TRACK + .001);
            equal(model.target(0), TouchModel.trackToRequest(TouchModel.REST_TRACK + .001), "First move above shelf counts immediately");
            model.move(9, -.02); equal(model.target(0), 0, "Padding remains rest");
            model.end(9); equal(model.target(0), 0, "Hold cannot retain rest output");
        });
        test("one finger entering rest preserves the other finger", () -> {
            TouchModel model = dual(); model.setHold(true);
            model.begin(13,0,0,.1); model.begin(8,1,0,.1);
            model.move(13,TouchModel.requestToTrack(.7)); model.move(8,TouchModel.requestToTrack(.4));
            model.move(13,.05); equal(model.target(0),0,"Rest owner zero"); equal(model.target(1),.4,"Other owner unaffected");
            check(model.isPointActive(0) && model.isPointActive(1),"Rest does not lose pointer identity");
        });
        System.out.println("PASS: " + tests + " TouchModel regression tests");
    }
}
