package com.example.androidthings.imageclassifier.model;

import java.io.Serializable;

/**
 * Created by markie26 on 2/20/18.
 */

public class Settings implements Serializable{
    private int time_trigger;

    public Settings(){}

    public Settings(int time_trigger) {
        this.time_trigger = time_trigger;
    }

    public int getTime_trigger() {
        return time_trigger;
    }

    public void setTime_trigger(int time_trigger) {
        this.time_trigger = time_trigger;
    }
}
