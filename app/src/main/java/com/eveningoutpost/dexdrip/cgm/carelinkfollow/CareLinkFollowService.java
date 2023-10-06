package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.text.SpannableString;

import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.UtilityModels.Constants;
import com.eveningoutpost.dexdrip.UtilityModels.Inevitable;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.UtilityModels.StatusItem;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.auth.CareLinkCredentialStore;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.utils.Logger;
import com.eveningoutpost.dexdrip.utils.DexCollectionType;
import com.eveningoutpost.dexdrip.utils.framework.BuggySamsung;
import com.eveningoutpost.dexdrip.utils.framework.ForegroundService;
import com.eveningoutpost.dexdrip.utils.framework.WakeLockTrampoline;
import com.eveningoutpost.dexdrip.xdrip;

import java.util.ArrayList;
import java.util.List;

import static com.eveningoutpost.dexdrip.Models.JoH.emptyString;
import static com.eveningoutpost.dexdrip.Models.JoH.msSince;
import static com.eveningoutpost.dexdrip.UtilityModels.BgGraphBuilder.DEXCOM_PERIOD;

import org.apache.commons.math3.complex.Quaternion;

public class CareLinkFollowService extends ForegroundService {

    private static final String TAG = "CareLinkFollow";
    private static final long SAMPLE_PERIOD = DEXCOM_PERIOD;
    private static final int RATE_LIMIT_SECONDS = 20;
    private static final String RATE_LIMIT_NAME = "last-carelink-follow-poll";
    private static final int RATE_LIMIT_SAFETY = 10;

    protected static volatile String lastState = "";

    private static BuggySamsung buggySamsung;
    private static volatile long wakeup_time = 0;
    private static volatile long last_wakeup = 0;
    private static volatile long lastPoll = 0;
    private static volatile BgReading lastBg;
    private static volatile long bgReceiveDelay;
    private static volatile long lastBgTime;

    private static CareLinkFollowDownloader downloader;
    private static volatile int gracePeriod = 0;
    private static volatile int pollInterval = 0;
    private static volatile int renewBefore = 0;
    private static volatile int renewInterval = 0;

    private static final long WAKE_UP_GRACE_SECOND = 60;


    @Override
    public void onCreate() {
        Logger.Log(TAG, "onCreate");
        super.onCreate();
        resetInstance(); // manage static reference life cycle
    }


    /**
     * Update observedDelay if new bg reading is available
     */
    static void updateBgReceiveDelay() {
        Logger.Log(TAG, "updateBgReceiveDelay");
        lastBg = BgReading.lastNoSenssor();
        if (lastBg != null && lastBgTime != lastBg.timestamp) {
            bgReceiveDelay = JoH.msSince(lastBg.timestamp);
            lastBgTime = lastBg.timestamp;
        }
    }

    public synchronized static void resetInstanceAndInvalidateSession() {
        Logger.Log(TAG, "resetInstanceAndInvalidateSession");
        try {
            if (downloader != null) {
                downloader.invalidateSession();
            }
        } catch (Exception e) {
            // concurrency related
        }
        resetInstance();
    }

    public static void resetInstance() {
        downloader = null;
        gracePeriod = 0;
        pollInterval = 0;
        renewBefore = 0;
        renewInterval = 0;
    }

    private static boolean shouldServiceRun() {
        return DexCollectionType.getDexCollectionType() == DexCollectionType.CLFollow;
    }

    private static long getGraceMillis() {
        //return Constants.SECOND_IN_MS * WAKE_UP_GRACE_SECOND;
        return Constants.SECOND_IN_MS * gracePeriod;
    }

    private static long getRenewBeforeMillis() {
        return Constants.MINUTE_IN_MS * renewBefore;
    }

    private static long getRenewIntervalMillis() {
        return Constants.MINUTE_IN_MS * renewInterval;
    }

    private static long getIntervalMillis() {
        return Constants.MINUTE_IN_MS * pollInterval;
    }

    private static CareLinkFollowDownloader getDownloader(){
        if (downloader == null) {
            downloader = new CareLinkFollowDownloader(
                    Pref.getString("clfollow_user", ""),
                    Pref.getString("clfollow_pass", ""),
                    Pref.getString("clfollow_country", "").toLowerCase(),
                    Pref.getString("clfollow_patient", "")
            );
        }
        return downloader;
    }

    static void scheduleWakeUp() {

        Logger.Log(TAG, "scheduleWakeUp");
        String scheduleReason;
        long next;

        final BgReading lastBg = BgReading.lastNoSenssor();
        final long last = lastBg != null ? lastBg.timestamp : 0;

        final long nextTokenRefresh = anticipateNextTokenRefresh(JoH.tsl(), CareLinkCredentialStore.getInstance().getExpiresOn(), getRenewBeforeMillis(), getRenewIntervalMillis());
        final long nextDataPoll = anticipateNextDataPoll(JoH.tsl(), last, SAMPLE_PERIOD, getGraceMillis(), getIntervalMillis());

        // Token needs to refreshed sooner
        if(nextTokenRefresh <= nextDataPoll){
            next = nextTokenRefresh;
            scheduleReason = " as login expires: ";
        // Data is required sooner
        } else {
            next = nextDataPoll;
            scheduleReason = " as last BG timestamp: ";
        }

        if(JoH.msTill(next) < (RATE_LIMIT_SECONDS * Constants.SECOND_IN_MS))
            next = JoH.tsl() + (RATE_LIMIT_SECONDS * Constants.SECOND_IN_MS);

        UserError.Log.d(TAG, "Anticipate next: " + JoH.dateTimeText(next) + scheduleReason + JoH.dateTimeText(last));

        wakeup_time = next;
        JoH.wakeUpIntent(xdrip.getAppContext(), JoH.msTill(next), WakeLockTrampoline.getPendingIntent(CareLinkFollowService.class, Constants.CARELINKFOLLOW_SERVICE_FAILOVER_ID));
    }

    public static long anticipateNextWakeUp(long now, final long last, final long period, final long grace, final long interval) {

        long nextDataPoll = anticipateNextDataPoll(now, last, period, grace, interval);

        return  nextDataPoll;

    }

    private static long anticipateNextTokenRefresh(long now, final long expiry, final long before, final long interval){

        long next;

        // refresh should happen before expiration
        next = expiry - before;
        // add retry interval until future
        while (next <= now) {
            next += interval;
        }

        return next;

    }

    private static long anticipateNextDataPoll(long now, final long last, final long period, final long grace, final long interval) {

        long next;

        //recent reading (less then data period) => last + period + grace
        if ((now - last) < period) {
            next = last + period + grace;
        }
        //old reading => anticipated next + grace
        else {
            //last expected
            next = now + ((last - now) % period);
            //add poll interval until next time is reached
            while (next <= now) {
                next += interval;
            }
            //add grace
            next += grace;
        }

        return next;

    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        final PowerManager.WakeLock wl = JoH.getWakeLock("ConnectFollow-osc", 60000);
        try {

            Logger.Log(TAG, "onStartCommand");
            UserError.Log.d(TAG, "WAKE UP WAKE UP WAKE UP");

            // Check service should be running
            if (!shouldServiceRun()) {
                UserError.Log.d(TAG, "Stopping service due to shouldServiceRun() result");
                msg("Stopping");
                stopSelf();
                return START_NOT_STICKY;
            }

            buggySamsungCheck();

            last_wakeup = JoH.tsl();

            if (gracePeriod == 0)
                gracePeriod = Pref.getStringToInt("clfollow_grace_period", 30);
            if (pollInterval == 0)
                pollInterval = Pref.getStringToInt("clfollow_poll_interval", 5);
            if(renewBefore == 0)
                renewBefore = 10;
            if(renewInterval == 0)
                renewInterval = 1;
            lastBg = BgReading.lastNoSenssor();
            if (lastBg != null) {
                lastBgTime = lastBg.timestamp;
            }
            // Check if downloader needs to be started (last BG old or token needs to be renewed)
            final boolean refreshToken =  (JoH.msTill(CareLinkCredentialStore.getInstance().getExpiresOn())< getRenewBeforeMillis()) ? true : false;
            final boolean downloadData = (lastBg == null || msSince(lastBg.timestamp) > SAMPLE_PERIOD + getGraceMillis()) ? true : false;
            if (refreshToken || downloadData) {
                //Only start if rate limit is not exceeded
                if (JoH.ratelimit(RATE_LIMIT_NAME, RATE_LIMIT_SAFETY)) {
                    Inevitable.task("CareLink-Follow-Work", 200, () -> {
                        try {
                            getDownloader().doEverything(refreshToken, downloadData);
                        } catch (NullPointerException e) {
                            UserError.Log.e(TAG, "Caught concurrency exception when trying to run doeverything");
                        }
                    });
                    lastPoll = JoH.tsl();
                }
            } else {
                UserError.Log.d(TAG, "Already have recent reading: " + msSince(lastBg.timestamp));
            }
            scheduleWakeUp();
        } finally {
            JoH.releaseWakeLock(wl);
        }
        return START_STICKY;
    }

    /**
     * MegaStatus for CareLink Follower
     */
    public static List<StatusItem> megaStatus() {
        final BgReading lastBg = BgReading.lastNoSenssor();

        long hightlightGrace = Constants.SECOND_IN_MS * getGraceMillis() * 2 + Constants.SECOND_IN_MS * 10; // 2 times garce + 20 seconds for processing

        // Status for BG receive delay (time from bg was recorded till received in xdrip)
        String ageOfBgLastPoll = "n/a";
        StatusItem.Highlight ageOfLastBgPollHighlight = StatusItem.Highlight.NORMAL;
        if (bgReceiveDelay > 0) {
            ageOfBgLastPoll = JoH.niceTimeScalar(bgReceiveDelay);
            if (bgReceiveDelay > SAMPLE_PERIOD / 2) {
                ageOfLastBgPollHighlight = StatusItem.Highlight.BAD;
            }
            if (bgReceiveDelay > SAMPLE_PERIOD * 2) {
                ageOfLastBgPollHighlight = StatusItem.Highlight.CRITICAL;
            }
        }

        // Status for time since latest BG
        String ageLastBg = "n/a";
        StatusItem.Highlight bgAgeHighlight = StatusItem.Highlight.NORMAL;
        if (lastBg != null) {
            long age = JoH.msSince(lastBg.timestamp);
            ageLastBg = JoH.niceTimeScalar(age);
            if (age > SAMPLE_PERIOD + hightlightGrace) {
                bgAgeHighlight = StatusItem.Highlight.BAD;
            }
        }

        // Last response code
        int lastResponseCode = downloader != null ? downloader.getLastResponseCode() : 0;

        StatusItem.Highlight authHighlight = StatusItem.Highlight.NORMAL;

        if (lastBg != null) {
            long age = JoH.msSince(lastBg.timestamp);
            ageLastBg = JoH.niceTimeScalar(age);
            if (age > SAMPLE_PERIOD + hightlightGrace) {
                bgAgeHighlight = StatusItem.Highlight.BAD;
            }
        }
        String authStatus = null;
        switch (CareLinkCredentialStore.getInstance().getAuthStatus()) {
            case CareLinkCredentialStore.NOT_AUTHENTICATED:
                authStatus = "NOT AUTHENTICATED";
                authHighlight = StatusItem.Highlight.CRITICAL;
                break;
            case CareLinkCredentialStore.AUTHENTICATED:
                authHighlight = StatusItem.Highlight.GOOD;
                authStatus = "AUTHENTICATED";
                break;
            case CareLinkCredentialStore.TOKEN_EXPIRED:
                authHighlight = StatusItem.Highlight.BAD;
                authStatus = "TOKEN EXPIRED";
                break;
        }

        //Create status screeen
        List<StatusItem> megaStatus = new ArrayList<>();
        megaStatus.add(new StatusItem("Authentication status", authStatus, authHighlight));
        megaStatus.add(new StatusItem("Login expires in", JoH.niceTimeScalar(CareLinkCredentialStore.getInstance().getExpiresIn())));
        megaStatus.add(new StatusItem());
        megaStatus.add(new StatusItem("Latest BG", ageLastBg + (lastBg != null ? " ago" : ""), bgAgeHighlight));
        megaStatus.add(new StatusItem("BG receive delay", ageOfBgLastPoll, ageOfLastBgPollHighlight));
        megaStatus.add(new StatusItem("Data period:", JoH.niceTimeScalar(SAMPLE_PERIOD)));
        megaStatus.add(new StatusItem("Grace period:", JoH.niceTimeScalar(getGraceMillis())));
        megaStatus.add(new StatusItem("Missed poll interval:", JoH.niceTimeScalar(getIntervalMillis())));
        megaStatus.add(new StatusItem());
        megaStatus.add(new StatusItem("Last poll", lastPoll > 0 ? JoH.niceTimeScalar(JoH.msSince(lastPoll)) + " ago" : "n/a"));
        megaStatus.add(new StatusItem("Last wakeup", last_wakeup > 0 ? JoH.niceTimeScalar(JoH.msSince(last_wakeup)) + " ago" : "n/a"));
        megaStatus.add(new StatusItem("Next poll in", JoH.niceTimeScalar(wakeup_time - JoH.tsl())));
        if (lastBg != null) {
            megaStatus.add(new StatusItem("Last BG time", JoH.dateTimeText(lastBg.timestamp)));
        }
        megaStatus.add(new StatusItem("Last poll time", lastPoll > 0 ? JoH.dateTimeText(lastPoll) : "n/a"));
        megaStatus.add(new StatusItem("Next poll time", JoH.dateTimeText(wakeup_time)));
        megaStatus.add(new StatusItem());
        megaStatus.add(new StatusItem("Last response code", lastResponseCode));
        megaStatus.add(new StatusItem());
        megaStatus.add(new StatusItem("Buggy Samsung", JoH.buggy_samsung ? "Yes" : "No"));

        return megaStatus;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Logger.Log(TAG, "onDestroy");
        super.onDestroy();
        resetInstance(); // manage static reference life cycle
    }

    private void buggySamsungCheck() {
        if (buggySamsung == null) {
            buggySamsung = new BuggySamsung(TAG);
        }
        buggySamsung.evaluate(wakeup_time);
        wakeup_time = 0;
    }

    private static void msg(final String msg) {
        lastState = msg;
    }

    private static String getBestStatusMessage() {
        // service state overrides downloader state reply
        if (emptyString(lastState)) {
            if (downloader != null) {
                return downloader.getStatus();
            }
        } else {
            return lastState;
        }
        return null;
    }

    public static SpannableString nanoStatus() {
        final String current_state = getBestStatusMessage();
        return emptyString(current_state) ? null : new SpannableString(current_state);
    }


}
