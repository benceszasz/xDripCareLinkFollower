package com.eveningoutpost.dexdrip.cgm.carelinkfollow.message;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SensorGlucose {

    public Integer sg;
    public String datetime;
    public Date datetimeAsDate;
    public boolean timeChange;
    public String kind;
    public int version;
    public String sensorState;
    public int relativeOffset;

    public String toS() {
        String dt;
        if (datetime == null) { dt = ""; } else{ dt = datetime.toString(); }
        return dt + " "  + String.valueOf(sg);
    }

}
