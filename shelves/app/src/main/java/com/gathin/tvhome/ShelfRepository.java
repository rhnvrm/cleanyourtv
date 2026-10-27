package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import java.util.*;

/** Reads only public TV-provider rows. Account APIs and account credentials are never used. */
final class ShelfRepository {
    static final String STREMIO="com.stremio.one";
    static final Map<String,String> APPS=new LinkedHashMap<>();
    static {
        APPS.put(STREMIO,"Stremio"); APPS.put("com.amazon.amazonvideo.livingroom","Prime Video");
        APPS.put("com.google.android.youtube.tv","YouTube"); APPS.put("com.netflix.ninja","Netflix");
        APPS.put("com.spotify.tv.android","Spotify"); APPS.put("in.startv.hotstar","JioHotstar");
    }
    static final class Item {
        String key,pkg,title,subtitle,image,intent,channel; long position,duration,lastSeen,weight; int type;
        boolean next;
    }
    static final class Data {
        final List<Item> continuing=new ArrayList<>(), recommendations=new ArrayList<>();
        final Map<String,List<Item>> libraries=new LinkedHashMap<>();
        String error;
    }
    private final Context context;
    private final SharedPreferences history;
    ShelfRepository(Context c){context=c.getApplicationContext(); history=context.getSharedPreferences("shelf-observed-progress",0);}
    private static String value(Cursor c,String key){int i=c.getColumnIndex(key);return i<0||c.isNull(i)?"":c.getString(i);}
    private static long number(Cursor c,String key){try{return Long.parseLong(value(c,key));}catch(NumberFormatException e){return 0;}}
    static boolean unfinished(long position,long duration){return duration<=0 || position<duration*0.98;}
    static long validTimestamp(long stamp,long now){return stamp>946684800000L && stamp<=now+300000?stamp:0;}
    static int compare(Item a,Item b){return Long.compare(b.lastSeen,a.lastSeen);}
    Data load(){
        Data result=new Data(); Map<String,String> channels=new HashMap<>(); long now=System.currentTimeMillis();
        SharedPreferences.Editor observed=history.edit(); Set<String> dedup=new HashSet<>();
        try {
            try(Cursor c=context.getContentResolver().query(Uri.parse("content://android.media.tv/channel"),null,null,null,null)){
                if(c!=null)while(c.moveToNext())if(APPS.containsKey(value(c,"package_name")))channels.put(value(c,"_id"),value(c,"display_name"));
            }
            // Watch Next first: its real engagement timestamp wins over a duplicate preview row.
            for(String table:new String[]{"watch_next_program","preview_program"}){
                try(Cursor c=context.getContentResolver().query(Uri.parse("content://android.media.tv/"+table),null,null,null,null)){
                    if(c==null)continue;
                    while(c.moveToNext()){
                        String pkg=value(c,"package_name"); if(!APPS.containsKey(pkg))continue;
                        Item item=new Item(); item.pkg=pkg; item.title=value(c,"title");
                        item.intent=value(c,"intent_uri"); item.image=value(c,"poster_art_uri");
                        if(item.title.isEmpty()||item.intent.isEmpty())continue;
                        item.channel=channels.getOrDefault(value(c,"channel_id"),"");
                        String season=value(c,"season_display_number"),episode=value(c,"episode_display_number");
                        String episodeTitle=value(c,"episode_title");
                        item.subtitle=APPS.get(pkg)+(episode.isEmpty()?"":" · "+(season.isEmpty()?"":"S"+season+" ")+"E"+episode);
                        if(!episodeTitle.isEmpty())item.subtitle+=" · "+episodeTitle;
                        // Stable across provider row replacement. No playback URLs are persisted.
                        item.key=pkg+"|"+item.title+"|"+season+"|"+episode;
                        item.position=number(c,"last_playback_position_millis"); item.duration=number(c,"duration_millis");
                        item.weight=number(c,"weight");
                        item.type=(int)number(c,"watch_next_type"); item.next=table.equals("watch_next_program")&&item.type==1;
                        item.lastSeen=validTimestamp(number(c,"last_engagement_time_utc_millis"),now);
                        boolean cw=table.equals("watch_next_program") || (pkg.equals(STREMIO)&&item.channel.toLowerCase(Locale.ROOT).contains("continue"));
                        String pref=Integer.toHexString(item.key.hashCode())+":"+item.key;
                        if(cw){
                            long previous=history.getLong(pref+":position",-1);
                            long changed=history.getLong(pref+":seen",0);
                            // A first snapshot is not evidence that the content was just watched.
                            if(previous>=0 && previous!=item.position){changed=now;observed.putLong(pref+":seen",now);}
                            observed.putLong(pref+":position",item.position);
                            item.lastSeen=Math.max(item.lastSeen,changed);
                            if(!item.next&&!unfinished(item.position,item.duration))continue;
                            if(item.type==3&&table.equals("watch_next_program"))continue; // watchlist, not continue
                            if(dedup.add("cw:"+item.key))result.continuing.add(item);
                        }else if(pkg.equals(STREMIO)){
                            String label=item.channel.startsWith("Library - ")?item.channel.replace("Library - ","Stremio · "):"Stremio · "+item.channel;
                            List<Item> row=result.libraries.get(label);
                            if(row==null){row=new ArrayList<>();result.libraries.put(label,row);}
                            if(dedup.add("lib:"+item.key))row.add(item);
                        }else if(dedup.add("rec:"+item.key))result.recommendations.add(item);
                    }
                }
            }
            observed.apply();
            Collections.sort(result.continuing,ShelfRepository::compare); // stable source order for unknown dates
            Set<String> resumed=new HashSet<>();for(Item i:result.continuing)resumed.add(i.key);
            result.recommendations.removeIf(i->resumed.contains(i.key));
        }catch(SecurityException e){result.error="Allow TV listings to show Continue Watching.";}
        catch(Exception e){result.error="Content is temporarily unavailable. Your apps still open below.";android.util.Log.w("MediaShelves","Provider query failed",e);}
        return result;
    }
}
