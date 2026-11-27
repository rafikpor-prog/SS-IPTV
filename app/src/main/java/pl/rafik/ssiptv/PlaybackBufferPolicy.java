package pl.rafik.ssiptv;
/** Pure policy. Target buffer bytes is not a hard limit on decoder/process memory. */
public final class PlaybackBufferPolicy {
    private PlaybackBufferPolicy() {}
    public static final class Plan {
        public final int minMs,maxMs,startMs,rebufferMs,targetMiB;
        Plan(int min,int max,int start,int rebuffer,int mib){minMs=min;maxMs=max;startMs=start;rebufferMs=rebuffer;targetMiB=mib;}
    }
    public static Plan plan(int mode,boolean vod,boolean small) {
        if(mode==1) return vod ? new Plan(10000,35000,1200,3000,small?16:32) : new Plan(6000,20000,1200,3000,small?12:24);
        if(mode==2) return vod ? new Plan(3500,18000,500,1000,small?10:20) : new Plan(1500,8000,400,900,small?6:12);
        return vod ? new Plan(6000,25000,600,1500,small?12:24) : new Plan(2500,12000,600,1500,small?8:16);
    }
    public static int vlcCache(int mode,boolean vod) {
        if(mode==1) return vod?6500:3500;
        if(mode==2) return vod?2500:1000;
        return vod?4500:1800;
    }
}
