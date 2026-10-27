package com.gathin.tvhome;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/** Optional Android 8–11 shortcut; no privileged access is granted by the app. */
public final class PhotoScreensaver {
    private static final String GOOGLE="com.google.android.backdrop/.Backdrop";
    private static final String KEY="screensaver_components";
    private static BroadcastReceiver started;
    private static long awaitingUntil;
    private static SharedPreferences state(Context c){return c.getSharedPreferences("photo-screensaver",0);}
    private static Intent launchIntent(){return new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(
            "com.android.systemui","com.android.systemui.Somnambulator")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
    private static PendingIntent rollback(Context c){return PendingIntent.getBroadcast(c,0,
            new Intent(c,RestoreReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    public static String unavailable(Context c){
        if(Build.VERSION.SDK_INT>30)return "Photos shortcut currently supports Android TV 8–11 only.";
        if(c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED)
            return "Photos needs optional ADB setup. On your computer run: adb shell pm grant "+c.getPackageName()+" android.permission.WRITE_SECURE_SETTINGS. This also allows changes to other protected settings.";
        try{if(!c.getPackageManager().getServiceInfo(ComponentName.unflattenFromString(GOOGLE),0).enabled)
                return "Google Ambient Mode is disabled.";}
        catch(PackageManager.NameNotFoundException e){return "Google Ambient Mode is not installed.";}
        if(c.getPackageManager().resolveActivity(launchIntent(),0)==null)return "This TV has no compatible screensaver shortcut.";
        return null;
    }
    private static ScreensaverSession session(Context context){
        Context c=context.getApplicationContext();
        return new ScreensaverSession(new ScreensaverSession.Host(){
            public boolean pending(){return state(c).getBoolean("pending",false);}
            public String original(){return state(c).getString("original",null);}
            public String current(){return Settings.Secure.getString(c.getContentResolver(),KEY);}
            public void save(String value){if(!state(c).edit().putString("original",value).putBoolean("pending",true).commit())throw new IllegalStateException("Could not save the idle screensaver.");}
            public void select(String value){if(!Settings.Secure.putString(c.getContentResolver(),KEY,value))throw new IllegalStateException("Could not change the screensaver.");}
            public void armRecovery(){
                AlarmManager alarm=c.getSystemService(AlarmManager.class);
                if(alarm==null)throw new IllegalStateException("Screensaver recovery timer unavailable.");
                // Recovery is inexact: no additional exact-alarm permission. Startup confirmation
                // normally restores immediately; this delayed alarm is only a fallback.
                alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+10000,rollback(c));
            }
            public void watchStart(Runnable callback){
                awaitingUntil=SystemClock.elapsedRealtime()+10000;
                started=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
                    try{callback.run();}catch(RuntimeException e){Log.e("PhotoScreensaver","Restore pending",e);}
                }};
                // Protected system broadcast. Android 9 sends this after attaching the chosen dream.
                c.registerReceiver(started,new IntentFilter(Intent.ACTION_DREAMING_STARTED));
            }
            public void launch(){c.startActivity(launchIntent());}
            public void clear(){
                if(!state(c).edit().clear().commit())throw new IllegalStateException("Could not finish screensaver recovery.");
                if(started!=null){try{c.unregisterReceiver(started);}catch(IllegalArgumentException ignored){}started=null;}
                AlarmManager alarm=c.getSystemService(AlarmManager.class);if(alarm!=null)alarm.cancel(rollback(c));
            }
        },GOOGLE);
    }
    public static void start(Context c){
        String reason=unavailable(c);if(reason!=null){Toast.makeText(c,reason,Toast.LENGTH_LONG).show();return;}
        if(started!=null)return;
        try{session(c).start();}catch(RuntimeException e){Log.e("PhotoScreensaver","Launch failed",e);Toast.makeText(c,"Photos could not start. Open Home to recover your idle screensaver.",Toast.LENGTH_LONG).show();}
    }
    public static void restore(Context c){
        try{session(c).restore();}catch(RuntimeException e){Log.e("PhotoScreensaver","Idle screensaver recovery pending",e);}
    }
    public static void recoverOnHome(Context c){if(started==null||SystemClock.elapsedRealtime()>=awaitingUntil)restore(c);}
    public static final class RestoreReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context c,Intent intent){restore(c);}
    }
}
