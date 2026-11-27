package pl.rafik.ssiptv.ui;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import pl.rafik.ssiptv.data.Channel;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class NativeChannelPageTest {
    private static void collect(View view,List<Button> result) {
        if(view instanceof Button && view.getTag() instanceof Channel)result.add((Button)view);
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),result);}
    }
    @Test public void actualNativeButtonsBothLayouts(){
        for(boolean tiles:new boolean[]{false,true}){
            Activity a=Robolectric.buildActivity(Activity.class).setup().get();
            AtomicLong opened=new AtomicLong(-1);AtomicInteger count=new AtomicInteger();
            NativeChannelPage page=new NativeChannelPage(a,tiles,c->"EPG",c->{opened.set(c.id);count.incrementAndGet();},c->{},c->{});
            a.setContentView(page);
            List<Channel> items=new ArrayList<>();
            for(int i=1;i<=55;i++){Channel c=new Channel("Kanał "+i,"TV","","","https://example.invalid","TEST");c.id=i;items.add(c);}
            page.setChannels(items);
            List<Button> b=new ArrayList<>();collect(page,b);
            assertEquals(24,b.size());assertTrue(b.get(3).isEnabled());assertTrue(b.get(3).isFocusable());assertTrue(b.get(3).isClickable());
            assertTrue(b.get(3).performClick());assertEquals(4,opened.get());assertEquals(1,count.get());
            page.setChannels(items.subList(50,55));b.clear();collect(page,b);
            assertEquals(5,b.size());assertTrue(b.get(0).performClick());assertEquals(51,opened.get());assertEquals(2,count.get());
            page.setChannels(new ArrayList<>());b.clear();collect(page,b);assertTrue(b.isEmpty());
            a.finish();
        }
    }
}
