package pl.rafik.ssiptv.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;
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
public class CatalogInputTest {
 @Test public void actualAdapterActivationBothModesAndLayouts() {
  for(boolean tv:new boolean[]{false,true}) for(boolean grid:new boolean[]{false,true}) {
   Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
   AbsListView list;
   if(grid){GridView g=new GridView(activity);g.setNumColumns(2);list=g;}else list=new ListView(activity);
   Channel first=new Channel("Kanał testowy","TV","","","https://example.invalid","TEST");first.id=73;
   Channel second=new Channel("Drugi","TV","","","https://example.invalid","TEST");second.id=74;
   Channel[] channels={first,second};
   list.setAdapter(new BaseAdapter(){
    public int getCount(){return channels.length;}
    public Object getItem(int i){return channels[i];}
    public long getItemId(int i){return channels[i].id;}
    public View getView(int i,View recycled,ViewGroup parent){TextView v=new TextView(activity);v.setText(channels[i].name);v.setMinHeight(64);return v;}
   });
   AtomicLong opened=new AtomicLong(-1);AtomicInteger times=new AtomicInteger(), favorites=new AtomicInteger();
   CatalogInput.bind(list,tv,ch->{opened.set(ch.id);times.incrementAndGet();},ch->favorites.incrementAndGet());
   activity.setContentView(list);
   list.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
   list.layout(0,0,480,800);
   assertEquals(tv,list.isFocusableInTouchMode());
   assertTrue(list.performItemClick(list.getChildAt(0),0,73));
   assertEquals(73,opened.get());assertEquals(1,times.get());
   assertTrue(list.getOnItemLongClickListener().onItemLongClick(list,list.getChildAt(1),1,74));
   assertEquals(1,favorites.get());assertEquals(1,times.get());
   activity.finish();
  }
 }
}
