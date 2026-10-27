package com.gathin.tvhome;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ShelfFiltersTest {
 private ShelfRepository.Item item(String pkg,String title,long position,long duration,long seen){ShelfRepository.Item i=new ShelfRepository.Item();i.pkg=pkg;i.title=title;i.key=pkg+"|"+title;i.position=position;i.duration=duration;i.lastSeen=seen;i.channel="feed";return i;}
 private Set<String> all(){return new HashSet<>(ShelfRepository.APPS.keySet());}
 @Test public void defaultContinueShowsOnlyStartedUnfinishedItems(){
  ShelfRepository.Item started=item(ShelfRepository.STREMIO,"Started",100,1000,0),next=item(ShelfRepository.STREMIO,"Next",0,1000,200),done=item(ShelfRepository.STREMIO,"Done",999,1000,300);next.next=true;
  assertEquals(Collections.singletonList(started),ShelfFilters.continuing(Arrays.asList(next,done,started),all(),"recent",false,false));
 }
 @Test public void sourceAndDateFiltersWorkWithoutChangingOriginalData(){
  ShelfRepository.Item stremio=item(ShelfRepository.STREMIO,"A",100,1000,0),prime=item("com.amazon.amazonvideo.livingroom","B",100,1000,200);
  List<ShelfRepository.Item> input=Arrays.asList(stremio,prime);
  assertEquals(Collections.singletonList(stremio),ShelfFilters.continuing(input,Collections.singleton(ShelfRepository.STREMIO),"recent",false,false));
  assertEquals(Collections.singletonList(prime),ShelfFilters.continuing(input,all(),"recent",false,true));assertEquals(2,input.size());
 }
 @Test public void progressSortPutsUnknownDurationLast(){
  ShelfRepository.Item unknown=item(ShelfRepository.STREMIO,"Unknown",300,0,300),half=item(ShelfRepository.STREMIO,"Half",500,1000,200),most=item(ShelfRepository.STREMIO,"Most",900,1000,100);
  assertEquals(Arrays.asList(most,half,unknown),ShelfFilters.continuing(Arrays.asList(unknown,half,most),all(),"progress",false,false));
 }
 @Test public void recommendationsBalanceServicesAndRespectRankWithinEachChannel(){
  String prime="com.amazon.amazonvideo.livingroom",netflix="com.netflix.ninja";
  ShelfRepository.Item low=item(prime,"Low",0,0,0),high=item(prime,"High",0,0,0),other=item(netflix,"Other",0,0,0);high.weight=100;low.weight=1;
  assertEquals(Arrays.asList(high,other,low),ShelfFilters.recommendations(Arrays.asList(low,other,high),Collections.emptyList(),all(),"balanced",1));
 }
 @Test public void recommendationsExcludeStartedSeriesAndDuplicateTitles(){
  String p="com.amazon.amazonvideo.livingroom",n="com.netflix.ninja";
  ShelfRepository.Item started=item(p,"Started",100,1000,100),same=item(p,"Started",0,0,0),a=item(p,"New",0,0,0),duplicate=item(n,"NEW",0,0,0);
  assertEquals(Collections.singletonList(a),ShelfFilters.recommendations(Arrays.asList(same,a,duplicate),Collections.singletonList(started),all(),"balanced",1));
 }
 @Test public void dailyMixIsStableDespiteProviderInsertionOrder(){
  List<ShelfRepository.Item> items=new ArrayList<>();for(int i=0;i<8;i++)items.add(item(ShelfRepository.STREMIO,"Title"+i,0,0,0));
  List<ShelfRepository.Item> first=ShelfFilters.recommendations(items,Collections.emptyList(),all(),"mix",10);Collections.reverse(items);
  assertEquals(first,ShelfFilters.recommendations(items,Collections.emptyList(),all(),"mix",10));
 }
 @Test public void recentAppsAreUniqueLaunchableAndExcludePinnedApps(){
  Map<String,Long> foreground=new HashMap<>();foreground.put("pinned",100L);foreground.put("old",1L);foreground.put("recent",50L);foreground.put("system",80L);foreground.put("newest",90L);
  assertEquals(Arrays.asList("newest","recent"),ShelfFilters.recentApps(foreground,Collections.singleton("pinned"),new HashSet<>(Arrays.asList("pinned","old","recent","newest")),2));
 }
}
