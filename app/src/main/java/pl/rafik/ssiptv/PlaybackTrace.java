package pl.rafik.ssiptv;
import android.content.Context;
import android.content.SharedPreferences;
/** Last local activation only. Never pass a stream URL, title, credential or exception text. */
final class PlaybackTrace {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("playback-trace-v1", Context.MODE_PRIVATE); }
    static void begin(Context c, String mode) {
        SharedPreferences p=prefs(c);
        p.edit().putString("trace","Tryb: "+mode+" | Odebrano aktywację").putLong("number",p.getLong("number",0)+1).apply();
    }
    static void stage(Context c,String stage) {
        SharedPreferences p=prefs(c);
        String old=p.getString("trace","");
        String next=old.isEmpty()?stage:old+" → "+stage;
        if(next.length()>1024)next=next.substring(next.length()-1024);
        p.edit().putString("trace",next).apply();
    }
    static String read(Context c) { SharedPreferences p=prefs(c);return "#"+p.getLong("number",0)+" | "+p.getString("trace","Brak aktywacji"); }
}
