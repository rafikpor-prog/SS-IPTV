package pl.rafik.ssiptv.ui;

import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;
import java.util.function.Consumer;
import pl.rafik.ssiptv.data.Channel;

/** A single input owner for both list/grid and TV/phone. Children are passive. */
public final class CatalogInput {
    private CatalogInput() {}
    public static void bind(AbsListView items, boolean tv, Consumer<Channel> play, Consumer<Channel> favorite) {
        items.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (items instanceof ListView) ((ListView)items).setItemsCanFocus(false);
        items.setSelector(Ui.tvSelection(items.getContext()));
        items.setDrawSelectorOnTop(true);
        items.setFocusable(true);
        // Never consume the first phone tap merely to enter touch focus.
        items.setFocusableInTouchMode(tv);
        items.setOnItemClickListener((parent, view, position, id) -> {
            Object value = parent.getItemAtPosition(position);
            if (value instanceof Channel) play.accept((Channel)value);
        });
        items.setOnItemLongClickListener((parent, view, position, id) -> {
            Object value = parent.getItemAtPosition(position);
            if (!(value instanceof Channel)) return false;
            favorite.accept((Channel)value); return true;
        });
    }
}
