package com.gathin.tvhome;
import java.util.*;

/** Pure sorting/filtering rules; these never change the app-published playback records. */
final class ShelfFilters {
 static List<ShelfRepository.Item> continuing(List<ShelfRepository.Item> input,Set<String> sources,String sort,boolean includeUnstarted,boolean datedOnly){
  List<ShelfRepository.Item> out=new ArrayList<>();
  for(ShelfRepository.Item item:input){
   if(!sources.contains(item.pkg)||(!includeUnstarted&&item.position<=0)||(datedOnly&&item.lastSeen<=0))continue;
   if(!item.next&&!ShelfRepository.unfinished(item.position,item.duration))continue;
   out.add(item);
  }
  Comparator<ShelfRepository.Item> comparator;
  if("progress".equals(sort))comparator=Comparator.comparingDouble(ShelfFilters::progress).reversed().thenComparing(ShelfRepository::compare);
  else if("title".equals(sort))comparator=Comparator.comparing(i->i.title.toLowerCase(Locale.ROOT));
  else if("app".equals(sort))comparator=Comparator.comparing((ShelfRepository.Item i)->ShelfRepository.APPS.getOrDefault(i.pkg,i.pkg)).thenComparing(ShelfRepository::compare);
  else comparator=ShelfRepository::compare;
  out.sort(comparator);return out;
 }
 private static double progress(ShelfRepository.Item item){return item.duration>0?(double)item.position/item.duration:-1;}
 static List<ShelfRepository.Item> recommendations(List<ShelfRepository.Item> input,List<ShelfRepository.Item> continuing,Set<String> sources,String sort,long day){
  Set<String> started=new HashSet<>();for(ShelfRepository.Item i:continuing)if(i.position>0)started.add(titleKey(i));
  Map<String,List<ShelfRepository.Item>> channels=new LinkedHashMap<>();
  for(ShelfRepository.Item item:input)if(sources.contains(item.pkg)&&!started.contains(titleKey(item)))channels.computeIfAbsent(item.pkg+"/"+item.channel,k->new ArrayList<>()).add(item);
  Map<String,ArrayDeque<ShelfRepository.Item>> apps=new LinkedHashMap<>();
  for(String pkg:ShelfRepository.APPS.keySet())for(List<ShelfRepository.Item> channel:channels.values())if(!channel.isEmpty()&&channel.get(0).pkg.equals(pkg)){
   // Weights are comparable within a channel only, never across different services.
   channel.sort(Comparator.comparingLong((ShelfRepository.Item i)->i.weight).reversed());
   apps.computeIfAbsent(pkg,k->new ArrayDeque<>()).addAll(channel);
  }
  List<ShelfRepository.Item> out=new ArrayList<>();Set<String> titles=new HashSet<>();boolean added=true;
  while(added){added=false;for(ArrayDeque<ShelfRepository.Item> queue:apps.values())while(!queue.isEmpty()){
   ShelfRepository.Item item=queue.remove();if(titles.add(item.title.trim().toLowerCase(Locale.ROOT))){out.add(item);added=true;break;}
  }}
  if("title".equals(sort))out.sort(Comparator.comparing(i->i.title.toLowerCase(Locale.ROOT)));
  else if("app".equals(sort))out.sort(Comparator.comparing(i->ShelfRepository.APPS.getOrDefault(i.pkg,i.pkg)));
  else if("mix".equals(sort)){
   // Stable for the day and source selection, even when provider insertion order changes.
   out.sort(Comparator.comparing(i->i.key));Collections.shuffle(out,new Random(day));
  }
  return out.size()>60?new ArrayList<>(out.subList(0,60)):out;
 }
 private static String titleKey(ShelfRepository.Item item){return item.pkg+"|"+item.title.trim().toLowerCase(Locale.ROOT);}
 static List<String> recentApps(Map<String,Long> foreground,Set<String> pinned,Set<String> launchable,int limit){
  List<String> out=new ArrayList<>();for(String pkg:foreground.keySet())if(!pinned.contains(pkg)&&launchable.contains(pkg))out.add(pkg);
  out.sort(Comparator.comparingLong((String p)->foreground.get(p)).reversed().thenComparing(p->p));
  return out.size()>limit?new ArrayList<>(out.subList(0,limit)):out;
 }
}
