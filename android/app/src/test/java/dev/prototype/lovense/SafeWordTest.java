package dev.prototype.lovense;
import android.app.Activity;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28, qualifiers="mdpi")
public final class SafeWordTest {
    private SafeWordView view(Activity activity, int[] count) { SafeWordView view=new SafeWordView(activity); view.layout(0,0,320,64); view.setAction(()->count[0]++); return view; }
    private void touch(SafeWordView view,int action,float x) { MotionEvent e=MotionEvent.obtain(1,2,action,x,32,0); view.onTouchEvent(e); e.recycle(); }
    @Test public void tapsAndIncompleteOrCancelledSlidesNeverActivate() {
        try(var c=Robolectric.buildActivity(Activity.class).setup()) { int[] count={0}; SafeWordView v=view(c.get(),count);
            touch(v,0,290); touch(v,1,290); touch(v,0,32); touch(v,1,32); touch(v,0,32); touch(v,2,140); touch(v,1,140);
            touch(v,0,32); touch(v,2,290); touch(v,3,290); assertEquals(0,count[0]);
        }
    }
    @Test public void deliberateSlideSurvivesRefreshAndActivatesExactlyOnce() {
        try(var c=Robolectric.buildActivity(Activity.class).setup()) { int[] count={0}; SafeWordView v=view(c.get(),count);
            touch(v,0,32); touch(v,2,150); v.setLatched(false); v.setLatched(false); touch(v,2,290); assertEquals(0,count[0]); touch(v,1,290); assertEquals(1,count[0]);
            touch(v,0,32); touch(v,1,290); assertEquals(1,count[0]);
        }
    }
    @Test public void accessibilityRequiresExplicitSafeWordAction() {
        try(var c=Robolectric.buildActivity(Activity.class).setup()) { int[] count={0}; SafeWordView v=view(c.get(),count);
            assertFalse(v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK,null)); assertEquals(0,count[0]);
            assertTrue(v.performAccessibilityAction(SafeWordView.ACTIVATE_SAFE_WORD,null)); assertEquals(1,count[0]);
        }
    }
}
