package pl.rafik.ssiptv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.res.ColorStateList;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import pl.rafik.ssiptv.data.Channel;

/** Bounded, ordinary Android buttons. No AdapterView, nested clickable child or custom touch dispatch. */
public final class NativeChannelPage extends LinearLayout {
    static final int PAGE_SIZE=24;
    private final boolean tiles;
    private final Function<Channel,String> description;
    private final Consumer<Channel> activate, select, favorite;
    private final ArrayList<Channel> channels=new ArrayList<>();
    private final ArrayList<Button> buttons=new ArrayList<>();
    private final LinearLayout rows;
    private final ScrollView scroll;
    private final TextView label;
    private final Button prev,next;
    private int page;
    public NativeChannelPage(Context context,boolean tiles,Function<Channel,String> description,
            Consumer<Channel> activate,Consumer<Channel> select,Consumer<Channel> favorite) {
        super(context);
        this.tiles=tiles;this.description=description;this.activate=activate;this.select=select;this.favorite=favorite;
        setOrientation(VERTICAL);
        LinearLayout pager=Ui.row(context);
        prev=button("‹",false);prev.setContentDescription("Poprzednia strona");
        next=button("›",false);next.setContentDescription("Następna strona");
        label=Ui.text(context,"",12,Ui.SECONDARY,true);label.setGravity(Gravity.CENTER);
        prev.setOnClickListener(v->{if(page>0){page--;render(true);}});
        next.setOnClickListener(v->{if((page+1)*PAGE_SIZE<channels.size()){page++;render(true);}});
        pager.addView(prev,new LinearLayout.LayoutParams(Ui.dp(context,52),Ui.dp(context,48)));
        pager.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        pager.addView(next,new LinearLayout.LayoutParams(Ui.dp(context,52),Ui.dp(context,48)));
        addView(pager,new LinearLayout.LayoutParams(-1,-2));
        scroll=new ScrollView(context);scroll.setFillViewport(false);
        rows=Ui.column(context);scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    }
    private Button button(String text,boolean primary){
        Button b=new Button(getContext());
        b.setAllCaps(false);b.setText(text);b.setTextSize(15);
        b.setTextColor(primary?Ui.WHITE:Ui.ACCENT);
        b.setBackgroundTintList(ColorStateList.valueOf(Ui.CARD));
        b.setMinimumHeight(Ui.dp(getContext(),48));b.setFocusable(true);
        b.setFocusableInTouchMode(false);b.setEnabled(true);
        Ui.tvFocus(b);
        return b;
    }
    public void setChannels(List<Channel> items){
        long focusedId=Long.MIN_VALUE;
        View focused=findFocus();
        if(focused!=null&&focused.getTag() instanceof Channel)focusedId=((Channel)focused.getTag()).id;
        channels.clear();channels.addAll(items);
        page=Math.min(page,Math.max(0,(channels.size()-1)/PAGE_SIZE));
        if(focusedId!=Long.MIN_VALUE)for(int i=0;i<channels.size();i++)if(channels.get(i).id==focusedId){page=i/PAGE_SIZE;break;}
        render(false);
        if(focusedId!=Long.MIN_VALUE)for(Button b:buttons)if(((Channel)b.getTag()).id==focusedId){b.requestFocus();break;}
    }
    private String title(Channel ch){return (ch.favorite?"★ ":"")+ch.name+"\n"+description.apply(ch);}
    public void refreshLabels(){for(Button b:buttons)b.setText(title((Channel)b.getTag()));}
    private void render(boolean focus){
        rows.removeAllViews();buttons.clear();
        int start=page*PAGE_SIZE,end=Math.min(channels.size(),start+PAGE_SIZE);
        prev.setEnabled(page>0);next.setEnabled(end<channels.size());
        label.setText(channels.isEmpty()?"Brak kanałów":(start+1)+"–"+end+" / "+channels.size());
        if(channels.isEmpty()){rows.addView(Ui.text(getContext(),"Brak kanałów. Zmień filtr lub dodaj źródło IPTV.",15,Ui.WHITE,false));return;}
        LinearLayout line=null;
        for(int i=start;i<end;i++){
            Channel ch=channels.get(i);
            Button b=button(title(ch),true);b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
            b.setMaxLines(3);b.setTag(ch);b.setContentDescription("Odtwórz: "+ch.name);
            b.setOnClickListener(v->activate.accept(ch));
            b.setOnLongClickListener(v->{favorite.accept(ch);return true;});
            View.OnFocusChangeListener style=b.getOnFocusChangeListener();
            b.setOnFocusChangeListener((v,has)->{if(style!=null)style.onFocusChange(v,has);if(has)select.accept(ch);});
            buttons.add(b);
            if(tiles){
                if((i-start)%2==0){line=Ui.row(getContext());rows.addView(line,new LinearLayout.LayoutParams(-1,-2));}
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,Ui.dp(getContext(),100),1);lp.setMargins(2,2,2,2);line.addView(b,lp);
                if(i==end-1&&(i-start)%2==0)line.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
            }else rows.addView(b,Ui.margins(getContext(),-1,72,0,1,0,1));
        }
        scroll.scrollTo(0,0);
        if(focus&&!buttons.isEmpty()&&!isInTouchMode())buttons.get(0).requestFocus();
    }
}
