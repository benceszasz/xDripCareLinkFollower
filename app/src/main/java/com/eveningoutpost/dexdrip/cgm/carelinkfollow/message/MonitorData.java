package com.eveningoutpost.dexdrip.cgm.carelinkfollow.message;

public class MonitorData {

    public String deviceFamily;

    public boolean isBleX() {
        return deviceFamily.contains("BLE_X");
    }

}
