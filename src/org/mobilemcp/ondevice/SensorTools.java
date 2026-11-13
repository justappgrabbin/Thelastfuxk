package org.mobilemcp.ondevice;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in, one-shot local Android hardware sensor readings. No background collection. */
final class SensorTools {
    private SensorTools() {}
    private static SensorManager manager(Context context) {
        return (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }
    static JSONObject list(Context context) throws Exception {
        JSONArray items = new JSONArray();
        SensorManager sm = manager(context);
        for (Sensor sensor : sm.getSensorList(Sensor.TYPE_ALL)) {
            items.put(new JSONObject().put("name",sensor.getName())
                .put("type",sensor.getType()).put("vendor",sensor.getVendor())
                .put("maximumRange",sensor.getMaximumRange())
                .put("resolution",sensor.getResolution()));
        }
        return new JSONObject().put("sensors",items);
    }
    static JSONObject read(Context context, JSONObject args) throws Exception {
        int type=args.getInt("type");
        SensorManager sm=manager(context);
        Sensor sensor=sm.getDefaultSensor(type);
        if(sensor==null) throw new IllegalArgumentException("Sensor type unavailable: "+type);
        CountDownLatch latch=new CountDownLatch(1);
        AtomicReference<float[]> values=new AtomicReference<>();
        SensorEventListener listener=new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {
                if(values.compareAndSet(null,event.values.clone())) latch.countDown();
            }
            @Override public void onAccuracyChanged(Sensor sensor,int accuracy) {}
        };
        if(!sm.registerListener(listener,sensor,SensorManager.SENSOR_DELAY_NORMAL))
            throw new IllegalStateException("Unable to register sensor listener");
        try {
            if(!latch.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Sensor reading timed out");
            JSONArray data=new JSONArray();
            for(float value:values.get()) data.put((double)value);
            return new JSONObject().put("type",type).put("name",sensor.getName())
                .put("values",data).put("units","Android sensor type-specific SI units");
        } finally { sm.unregisterListener(listener); }
    }
}
