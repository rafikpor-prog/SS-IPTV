package pl.rafik.ssiptv.epg;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Systemowy harmonogram EPG działa przy dostępnej sieci, także poza ekranem aplikacji. */
public final class EpgJobService extends JobService {
    private Thread worker;

    @Override public boolean onStartJob(JobParameters params) {
        worker = new Thread(() -> {
            EpgSync.refresh(getApplicationContext());
            jobFinished(params, false);
        }, "ss-epg-job");
        worker.start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (worker != null) worker.interrupt();
        return true;
    }
}
