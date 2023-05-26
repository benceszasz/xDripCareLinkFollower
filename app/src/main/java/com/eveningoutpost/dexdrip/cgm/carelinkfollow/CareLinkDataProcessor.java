package com.eveningoutpost.dexdrip.cgm.carelinkfollow;

import com.eveningoutpost.dexdrip.Home;
import com.eveningoutpost.dexdrip.Models.BgReading;
import com.eveningoutpost.dexdrip.Models.BloodTest;
import com.eveningoutpost.dexdrip.Models.DateUtil;
import com.eveningoutpost.dexdrip.Models.JoH;
import com.eveningoutpost.dexdrip.Models.Sensor;
import com.eveningoutpost.dexdrip.Models.Treatments;
import com.eveningoutpost.dexdrip.Models.UserError;
import com.eveningoutpost.dexdrip.R;
import com.eveningoutpost.dexdrip.UtilityModels.Inevitable;
import com.eveningoutpost.dexdrip.UtilityModels.Pref;
import com.eveningoutpost.dexdrip.UtilityModels.PumpStatus;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ActiveNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Alarm;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.Marker;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.ClearedNotification;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.RecentData;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.SensorGlucose;
import com.eveningoutpost.dexdrip.cgm.carelinkfollow.message.TextMap;
import com.eveningoutpost.dexdrip.wearintegration.ExternalStatusService;
import com.eveningoutpost.dexdrip.xdrip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static com.eveningoutpost.dexdrip.Models.BgReading.SPECIAL_FOLLOWER_PLACEHOLDER;
import static com.eveningoutpost.dexdrip.Models.Treatments.pushTreatmentSyncToWatch;

public class CareLinkDataProcessor {

    private static final String TAG = "CareLinkFollowDP";
    private static final boolean D = false;

    private static final String UUID_TAG_SENSOR_GLUCOSE = "SG";

    private static final String UUID_TAG_CARELINK_FOLLOW = "CF";
    private static final String SOURCE_CARELINK_FOLLOW = "CareLink Follow";


    private static String gs(int id) {
        return xdrip.getAppContext().getString(id);
    }

    static synchronized void processRecentData(final RecentData recentData, final boolean live) {

        List<SensorGlucose> filteredSgList;
        List<Marker> filteredMarkerList;
        String noteText;

        UserError.Log.d(TAG, "Start processsing data...");

        //SKIP ALL IF EMPTY!!!
        if (recentData == null) {
            UserError.Log.e(TAG, "Recent data is null, processing stopped!");
            return;
        }

        //PUMP INFO (Pump Status)
        if (recentData.isNGP()) {
            PumpStatus.setReservoir(recentData.reservoirRemainingUnits);
            PumpStatus.setBattery(recentData.medicalDeviceBatteryLevelPercent);
            if (recentData.activeInsulin != null)
                PumpStatus.setBolusIoB(recentData.activeInsulin.amount);
            PumpStatus.syncUpdate();
        }

        //EXTERNAL STATUS INFO (External Status)
        try {
            final StringBuilder sb = new StringBuilder();
            if (recentData.sensorState.equals("UNKNOWN")) {
                sb.append("?");
            } else if (recentData.sensorState.equals(RecentData.SYSTEM_STATUS_SENSOR_OFF)) {
                sb.append("-");
            } else {
                //Guardian Connect
                if (recentData.isGM()) {
                    sb.append("\u23F1" + String.format("%dh", recentData.timeToNextCalibHours) + " ");
                    sb.append("\uD83D\uDCC5" + String.format("%dd%dh", recentData.sensorDurationHours / 24, recentData.sensorDurationHours % 24));
                    //Pump (NGP)
                } else if (recentData.isNGP()) {
                    sb.append("\uD83D\uDD3A" + JoH.qs(recentData.maxAutoBasalRate, 3) + gs(R.string.insulin_unit) + " ");
                    sb.append("\u23F1" + String.format("%dh%dm", recentData.timeToNextCalibrationMinutes / 60, recentData.sensorDurationMinutes % 24) + " ");
                    sb.append("\uD83D\uDCC5" + String.format("%dd%dh", recentData.sensorDurationHours / 24, recentData.sensorDurationHours % 24));
                }
            }
            ExternalStatusService.update(JoH.tsl(), sb.toString(), true);
        } catch (Exception ex) {
            UserError.Log.d(TAG, "External status update error: " + ex.getMessage());
        }

        //SKIP DATA processing if NO PUMP CONNECTION (time shift seems to be different in this case, needs further analysis)
        if (recentData.isNGP() && !recentData.pumpCommunicationState) {
            UserError.Log.d(TAG, "Not connected to pump => time can be wrong, leave processing!");
            return;
        }


        //SENSOR GLUCOSE
        if (recentData.sgs != null) {

            final BgReading lastBg = BgReading.lastNoSenssor();
            final long lastBgTimestamp = lastBg != null ? lastBg.timestamp : 0;

            //SENSOR GLUCOSE
            //filter SGs
            filteredSgList = new ArrayList<>();
            for (SensorGlucose sg : recentData.sgs) {
                if (sg != null && sg.datetimeAsDate != null){
                    filteredSgList.add(sg);
                }
            }

            if(filteredSgList.size() > 0) {

                //UserError.Log.d(TAG, "Create Sensor");
                final Sensor sensor = Sensor.createDefaultIfMissing();

                //SENSOR
                //Sensor status
                //CHECK SENSOR & CONNECTION PRESENT
                //sensor.latest_battery_level = connectData.medicalDeviceBatteryLevelPercent;
                //sensor.latest_battery_level
                //sensor.started_at
                //sensor.uuid
                sensor.save();

                // place in order of oldest first
                Collections.sort(filteredSgList, (o1, o2) -> o1.datetimeAsDate.compareTo(o2.datetimeAsDate));

                for (final SensorGlucose sg : filteredSgList) {

                    //Not EPOCH 0 (warmup?)
                    if (sg.datetimeAsDate.getTime() > 1) {

                        //Not in the FUTURE
                        if (sg.datetimeAsDate.getTime() < new Date().getTime() + 300_000) {

                            //Not 0 SG (not calibrated?)
                            if (sg.sg != null && sg.sg > 0) {

                                //newer than last BG
                                if (sg.datetimeAsDate.getTime() > lastBgTimestamp) {

                                    if (sg.datetimeAsDate.getTime() > 0) {

                                        final BgReading existing = BgReading.getForPreciseTimestamp(sg.datetimeAsDate.getTime(), 10_000);
                                        if (existing == null) {
                                            UserError.Log.d(TAG, "NEW NEW NEW New entry: " + sg.toS());

                                            if (live) {
                                                final BgReading bg = new BgReading();
                                                bg.timestamp = sg.datetimeAsDate.getTime();
                                                bg.calculated_value = (double) sg.sg;
                                                bg.raw_data = SPECIAL_FOLLOWER_PLACEHOLDER;
                                                bg.filtered_data = (double) sg.sg;
                                                bg.noise = "";
                                                bg.uuid = UUID.randomUUID().toString();
                                                bg.calculated_value_slope = 0;
                                                bg.sensor = sensor;
                                                bg.sensor_uuid = sensor.uuid;
                                                bg.source_info = SOURCE_CARELINK_FOLLOW;
                                                bg.save();
                                                bg.find_slope();
                                                Inevitable.task("entry-proc-post-pr", 500, () -> bg.postProcess(false));
                                            }
                                        }
                                    } else {
                                        UserError.Log.e(TAG, "Could not parse a timestamp from: " + sg.toS());
                                    }

                                }

                            } else {
                                UserError.Log.d(TAG, "SG is 0 (calibration missed?)");
                            }


                        } else {
                            UserError.Log.d(TAG, "SG is in the future!");
                        }

                    } else {
                        UserError.Log.d(TAG, "SG DateTime is 0 (warmup phase?)");
                    }

                }
            }

        }

        //MARKERS (if available)
        if (recentData.markers != null) {

            //Filter markers
            filteredMarkerList = new ArrayList<>();
            for (Marker marker : recentData.markers) {
                if (marker != null && marker.type != null && marker.dateTime != null) {
                    filteredMarkerList.add(marker);
                }
            }

            //Process filtered markers
            if (filteredMarkerList.size() > 0) {
                //sort markers by time
                Collections.sort(filteredMarkerList, (o1, o2) -> o1.dateTime.compareTo(o2.dateTime));

                //process markers one-by-one
                for (Marker marker : filteredMarkerList) {

                    //FINGER BG
                    if (marker.isBloodGlucose() && Pref.getBooleanDefaultFalse("clfollow_download_finger_bgs")) {
                        //check required value
                        if (marker.value != null && !marker.value.equals(0)) {
                            //new blood test
                            final BloodTest existingBloodTest = BloodTest.getForPreciseTimestamp(marker.dateTime.getTime(), 10000);
                            if (existingBloodTest == null) {
                                final BloodTest bt = BloodTest.create(marker.dateTime.getTime(), marker.value, SOURCE_CARELINK_FOLLOW);
                                if (bt != null) {
                                    //    bt.saveit();
                                }
                            }
                        }

                    //INSULIN, MEAL => Treatment
                    } else if ((marker.type.equals(Marker.MARKER_TYPE_INSULIN) && Pref.getBooleanDefaultFalse("clfollow_download_boluses"))
                            || (marker.type.equals(Marker.MARKER_TYPE_MEAL) && Pref.getBooleanDefaultFalse("clfollow_download_meals"))) {

                        //insulin, meal only for pumps (not value in case of GC)
                        if (recentData.isNGP()) {

                            final Treatments t;
                            double carbs = 0;
                            double insulin = 0;

                            //Extract treament infos (carbs, insulin)
                            if (marker.type.equals(Marker.MARKER_TYPE_INSULIN)) {
                                carbs = 0;
                                if (marker.deliveredExtendedAmount != null && marker.deliveredFastAmount != null) {
                                    insulin = marker.deliveredExtendedAmount + marker.deliveredFastAmount;
                                }
                                //SKIP if insulin = 0
                                if (insulin == 0) continue;
                            } else if (marker.type.equals(Marker.MARKER_TYPE_MEAL)) {
                                if (marker.amount != null) {
                                    carbs = marker.amount;
                                }
                                insulin = 0;
                                //SKIP if carbs = 0
                                if (carbs == 0) continue;
                            }

                            //new Treatment
                            if (newTreatment(carbs, insulin, marker.dateTime.getTime())) {
                                t = Treatments.create(carbs, insulin, marker.dateTime.getTime());
                                if (t != null) {
                                    t.enteredBy = SOURCE_CARELINK_FOLLOW;
                                    t.save();
                                    if (Home.get_show_wear_treatments())
                                        pushTreatmentSyncToWatch(t, true);
                                }
                            }
                        }

                    }

                }
            }
        }

        // LAST ALARM -> NOTE (only for GC)
        if (Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {

            // Only Guardian Connect, NGP has all in notifications
            if (recentData.isGM() && recentData.lastAlarm != null) {
                //Add notification from alarm
                if (recentData.lastAlarm.datetimeAsDate != null && recentData.lastAlarm.kind != null)
                    addNotification(recentData.lastAlarm.datetimeAsDate, recentData.getDeviceFamily(), recentData.lastAlarm);
            }
        }

        //NOTIFICATIONS -> NOTE
        if (Pref.getBooleanDefaultFalse("clfollow_download_notifications")) {
            if (recentData.notificationHistory != null) {
                //Active Notifications
                if (recentData.notificationHistory.activeNotifications != null) {
                    for (ActiveNotification activeNotification : recentData.notificationHistory.activeNotifications) {
                        addNotification(activeNotification.dateTime, recentData.getDeviceFamily(), activeNotification.messageId, activeNotification.faultId);
                    }
                }
                //Cleared Notifications
                if (recentData.notificationHistory.clearedNotifications != null) {
                    for (ClearedNotification clearedNotification : recentData.notificationHistory.clearedNotifications) {
                        addNotification(clearedNotification.triggeredDateTime, recentData.getDeviceFamily(), clearedNotification.messageId, clearedNotification.faultId);
                    }
                }
            }
        }
    }

    //Create notification from CareLink messageId
    protected static boolean addNotification(Date date, String deviceFamily, String messageId, int faultId){

        if(deviceFamily != null && messageId != null)
            return addNotification(date, TextMap.getNotificationMessage(deviceFamily, messageId, faultId));
        else
            return false;

    }

    //Create notification from CareLink Alarm
    protected static boolean addNotification(Date date, String deviceFamily, Alarm alarm){

        if(deviceFamily != null && alarm != null && alarm.kind != null)
            return addNotification(date, TextMap.getAlarmMessage(deviceFamily, alarm));
        else
            return false;

    }

    protected static boolean addNotification(Date date, String noteText){

        //Valid date and text
        if(date != null && noteText != null) {
            //New note
            if (newNote(noteText, date.getTime())) {
                //create_note in Treatment is not good, because of automatic link to other treatments in 5 mins range
                Treatments note = new Treatments();
                note.notes = noteText;
                note.timestamp = date.getTime();
                note.created_at = DateUtil.toISOString(note.timestamp);
                note.uuid = UUID.randomUUID().toString();
                note.enteredBy = SOURCE_CARELINK_FOLLOW;
                note.save();
                if (Home.get_show_wear_treatments())
                    pushTreatmentSyncToWatch(note, true);
                return true;
            }
        }

        return false;

    }

    protected static boolean newNote(String note, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and note text exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.notes.contains(note))
                    return  false;
            }
        }
        return  true;

    }

    protected static boolean newTreatment(double carbs, double insulin, long timestamp){

        List<Treatments> treatmentsList;
        //Treatment with same timestamp and carbs + insulin exists?
        treatmentsList = Treatments.listByTimestamp(timestamp);
        if(treatmentsList != null) {
            for (Treatments treatments : treatmentsList) {
                if (treatments.carbs == carbs && treatments.insulin == insulin)
                    return  false;
            }
        }
        return  true;
    }



}
