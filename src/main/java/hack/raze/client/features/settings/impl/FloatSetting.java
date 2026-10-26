package hack.raze.client.features.settings.impl;

import lombok.Getter;
import lombok.Setter;

import java.util.function.Predicate;

import hack.raze.client.features.settings.Setting;

@Getter
@Setter
public class FloatSetting extends Setting {
    private float value;
    private float minValue;
    private float maxValue;
    private float increment;
    private CharSequence suffixSequence = "";
    private String suffix = "";

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
    }

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment,
            CharSequence suffix) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
        this.suffixSequence = suffix;
        this.suffix = null;
    }

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment,
            Predicate<Object> dependency) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
        this.setDependency(dependency);
    }

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment,
            CharSequence suffix, Predicate<Object> dependency) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
        this.suffixSequence = suffix;
        this.suffix = null;
        this.setDependency(dependency);
    }

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment,
            String suffix) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
        this.suffixSequence = suffix;
        this.suffix = suffix;
    }

    public FloatSetting(CharSequence name, float defaultValue, float minValue, float maxValue, float increment,
            String suffix, Predicate<Object> dependency) {
        super(name);
        this.value = defaultValue;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.increment = increment;
        this.suffixSequence = suffix;
        this.suffix = suffix;
        this.setDependency(dependency);
    }

    public FloatSetting(String name, float defaultValue, float minValue, float maxValue, float increment,
            Predicate<Object> dependency) {
        this((CharSequence) name, defaultValue, minValue, maxValue, increment, dependency);
    }

    public FloatSetting(String name, float defaultValue, float minValue, float maxValue, float increment) {
        this((CharSequence) name, defaultValue, minValue, maxValue, increment);
    }

    public FloatSetting(String name, float defaultValue, float minValue, float maxValue, float increment,
            String suffix) {
        this((CharSequence) name, defaultValue, minValue, maxValue, increment, suffix);
    }

    public FloatSetting(String name, float defaultValue, float minValue, float maxValue, float increment, String suffix,
            Predicate<Object> dependency) {
        this((CharSequence) name, defaultValue, minValue, maxValue, increment, suffix, dependency);
    }

    public String getSuffix() {
        if (this.suffix != null)
            return this.suffix;
        if (this.suffixSequence == null)
            return null;
        this.suffix = this.suffixSequence.toString();
        return this.suffix;
    }

    public CharSequence getSuffixSequence() {
        return this.suffixSequence;
    }

    public void increment() {
        setValue(Math.min(value + increment, maxValue));
    }

    public void decrement() {
        setValue(Math.max(value - increment, minValue));
    }

    public void setValue(float value) {
        this.value = quantize(value);
        notifyChange();
    }

    public void setValueNoNotify(float value) {
        this.value = quantize(value);
    }

    /** Snaps to {@link #increment} so sliders land on real steps (3.0 stays 3.0, not 2.87). */
    private float quantize(float value) {
        float clamped = Math.max(minValue, Math.min(maxValue, value));
        if (increment <= 0.0F) return clamped;
        float steps = Math.round((clamped - minValue) / increment);
        float snapped = minValue + steps * increment;
        float decimals = Math.max(0, Math.round(-(float) Math.log10(increment)));
        float scale = (float) Math.pow(10.0D, Math.min(6, decimals));
        return Math.max(minValue, Math.min(maxValue, Math.round(snapped * scale) / scale));
    }

    @Override
    public String getTypeId() { return "flt"; }
}
