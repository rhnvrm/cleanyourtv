package com.gathin.tvhome;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ShelfRepositoryTest {
 @Test public void newestFirstAndUnknownDatesStayInSourceOrder(){
  ShelfRepository.Item unknownA=new ShelfRepository.Item(),unknownB=new ShelfRepository.Item(),old=new ShelfRepository.Item(),recent=new ShelfRepository.Item();
  old.lastSeen=100;recent.lastSeen=200;
  List<ShelfRepository.Item> items=new ArrayList<>(Arrays.asList(unknownA,old,unknownB,recent));
  Collections.sort(items,ShelfRepository::compare);
  assertEquals(Arrays.asList(recent,old,unknownA,unknownB),items);
 }
 @Test public void firstSnapshotCannotPretendToBeRecentlyWatched(){
  long now=1800000000000L;
  assertEquals(0,ShelfRepository.validTimestamp(0,now));
  assertEquals(0,ShelfRepository.validTimestamp(now+600000,now));
  assertEquals(now-1000,ShelfRepository.validTimestamp(now-1000,now));
 }
 @Test public void posterRequestsStayOnApprovedHttpsHosts(){
  assertTrue(ShelfImages.allowedRemote("https://i.ytimg.com/vi/example/default.jpg"));
  assertFalse(ShelfImages.allowedRemote("http://i.ytimg.com/vi/example/default.jpg"));
  assertFalse(ShelfImages.allowedRemote("https://i.ytimg.com.attacker.invalid/image.jpg"));
  assertFalse(ShelfImages.allowedRemote("https://user:password@i.ytimg.com/image.jpg"));
  assertFalse(ShelfImages.allowedRemote("https://127.0.0.1/image.jpg"));
  assertFalse(ShelfImages.allowedRemote("file:///data/private"));
  assertFalse(ShelfImages.allowedRemote("https://i.ytimg.com:8443/image.jpg"));
 }
 @Test public void completedItemsDoNotStayInContinueWatching(){
  assertTrue(ShelfRepository.unfinished(500,1000));
  assertTrue(ShelfRepository.unfinished(979,1000));
  assertFalse(ShelfRepository.unfinished(980,1000));
  assertFalse(ShelfRepository.unfinished(1100,1000));
  assertTrue(ShelfRepository.unfinished(1000,0));
 }
}
