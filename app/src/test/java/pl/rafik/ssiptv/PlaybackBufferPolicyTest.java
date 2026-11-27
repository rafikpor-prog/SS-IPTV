package pl.rafik.ssiptv;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackBufferPolicyTest {
 @Test public void allProfilesBounded(){for(int m=0;m<3;m++)for(boolean vod:new boolean[]{false,true})for(boolean small:new boolean[]{false,true}){PlaybackBufferPolicy.Plan p=PlaybackBufferPolicy.plan(m,vod,small);assertTrue(p.maxMs>=p.minMs);assertTrue(p.minMs>=p.startMs&&p.minMs>=p.rebufferMs);assertTrue(p.targetMiB<=32&&p.targetMiB>0);}}
 @Test public void stabilityAndLatency(){for(boolean vod:new boolean[]{false,true}){assertTrue(PlaybackBufferPolicy.plan(1,vod,false).minMs>PlaybackBufferPolicy.plan(0,vod,false).minMs);assertTrue(PlaybackBufferPolicy.plan(2,vod,false).minMs<PlaybackBufferPolicy.plan(0,vod,false).minMs);assertTrue(PlaybackBufferPolicy.vlcCache(1,vod)>PlaybackBufferPolicy.vlcCache(0,vod));assertTrue(PlaybackBufferPolicy.vlcCache(2,vod)<PlaybackBufferPolicy.vlcCache(0,vod));}}
}
