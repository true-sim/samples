package com.truesim.samples.showcase.day;

import com.truesim.effects.weather.WeatherPreset;

/**
 * What the day is at a moment: the hour, the weather and what goes with it. The tour's script ({@link DayScript})
 * fills it from its timeline; the interactive app from its controls. {@link DayDriver} makes the world follow it.
 */
public final class DayState {

    /** Local time (CEST), hours since midnight. */
    public double hour = 12;
    /** The weather to be, and over how many seconds of weather it comes (0: at once). */
    public WeatherPreset weather = WeatherPreset.FAIR;
    public double weatherTransition = 0;
    /** How many seconds of weather pass in a second of the frames' (clouds drift and grow faster than life). */
    public double weatherRate = 1;
    /** A thunderstorm cell over the valley (lightning). */
    public boolean storm;
    /** The valley's height fog: extinction per metre at the valley floor, and how fast it thins with height. */
    public double fog = 0.0004;
    public double fogFalloff = 0.02;
    /** The mist in the valley at dawn: the fog volumes' density (0: none). */
    public double mist;
    /** Snowfall (0 to 1: the flakes' rate) and how much snow lies (the cover, 0 to 1). */
    public double snowfall;
    public double snowCover;
    /** Rain, 0 to 1. */
    public double rain;
    /** Street lamps and lanterns, rooms' lights, 0 to 1. */
    public double lamps;
    public double rooms;
    /** The hearths burn, the chimneys smoke. */
    public boolean fires = true;
    /** The fireworks show over the square. */
    public boolean fireworks;
    /** Depth of field's f-number for this shot (large: everything sharp). */
    public double fStop = 16;

    /** Copies {@code other} into this. */
    public DayState set(DayState other) {
        hour = other.hour;
        weather = other.weather;
        weatherTransition = other.weatherTransition;
        weatherRate = other.weatherRate;
        storm = other.storm;
        fog = other.fog;
        fogFalloff = other.fogFalloff;
        mist = other.mist;
        snowfall = other.snowfall;
        snowCover = other.snowCover;
        rain = other.rain;
        lamps = other.lamps;
        rooms = other.rooms;
        fires = other.fires;
        fireworks = other.fireworks;
        fStop = other.fStop;
        return this;
    }
}
